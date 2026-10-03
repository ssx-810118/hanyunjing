package com.hanyunjing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class VolcengineTryOnRendererTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
    private static final String PRIVATE_MARKER = "PRIVATE-UPSTREAM-DATA-test-sk-portrait-base64";
    private static final String SUBMITTED = "{\"code\":10000,\"data\":{\"task_id\":\"example-task\"}}";

    @Test void submitsPortraitThenExactGarmentOnceAndPollsToValidPng() throws Exception {
        byte[] expected = image("png", 80, 96);
        var polls = new AtomicInteger();
        try (var service = new MockService(request -> {
            if (request.action().equals("CVSubmitTask")) return new Reply(200, SUBMITTED);
            return new Reply(200, switch (polls.incrementAndGet()) {
                case 1 -> state("in_queue");
                case 2 -> state("generating");
                default -> done(expected);
            });
        })) {
            var product = product();
            byte[] portrait = image("png", 96, 128);
            var renderer = renderer(service);
            var stages = new CopyOnWriteArrayList<TryOnService.RenderStage>();
            assertArrayEquals(expected, renderer.render(product, product.skus().get(0), portrait, stages::add));
            assertEquals(List.of(TryOnService.RenderStage.PREPARING_IMAGE, TryOnService.RenderStage.UPLOADING,
                    TryOnService.RenderStage.REMOTE_QUEUED, TryOnService.RenderStage.RENDERING), stages);
            assertEquals(1920, renderer.maxPortraitDimension());
            service.assertHealthy();
            assertEquals(4, service.requests.size());
            assertEquals(List.of("CVSubmitTask", "CVGetResult", "CVGetResult", "CVGetResult"),
                    service.requests.stream().map(Request::action).toList());

            var submit = service.requests.get(0).json();
            assertEquals("dressing_diffusionV2", submit.path("req_key").asText());
            assertEquals(0, submit.path("req_image_store_type").asInt(-1));
            var images = submit.path("binary_data_base64");
            assertEquals(2, images.size());
            byte[] sentPortrait = Base64.getDecoder().decode(images.get(0).asText());
            assertEquals(0xff, sentPortrait[0] & 0xff);
            assertEquals(0xd8, sentPortrait[1] & 0xff);
            var decodedPortrait = ImageIO.read(new ByteArrayInputStream(sentPortrait));
            assertNotNull(decodedPortrait);
            assertEquals(96, decodedPortrait.getWidth());
            assertEquals(128, decodedPortrait.getHeight());
            try (var garment = getClass().getResourceAsStream("/static/images/tryon-garments/p14.jpg")) {
                assertNotNull(garment, "The exact catalogue JPEG must be packaged");
                assertArrayEquals(garment.readAllBytes(), Base64.getDecoder().decode(images.get(1).asText()));
            }
            assertEquals(1, submit.path("garment").path("data").size());
            assertEquals("full", submit.path("garment").path("data").get(0).path("type").asText());
            assertFalse(submit.has("prompt"), "V2 uses structured garment inputs");
            assertFalse(submit.has("model") && submit.path("model").has("url"));

            for (var request : service.requests) {
                assertEquals("POST", request.method());
                assertEquals("/", request.uri().getPath());
                assertEquals("Action=" + request.action() + "&Version=2022-08-31", request.uri().getRawQuery());
                assertEquals("application/json", request.header("Content-Type"));
                assertEquals("20261003T000000Z", request.header("X-Date"));
                var signed = VolcengineV4Signer.sign(service.endpoint(), request.action(), request.body(),
                        "test-ak", "test-sk", CLOCK.instant());
                assertEquals(signed.get("Authorization"), request.header("Authorization"));
                assertEquals(signed.get("X-Content-Sha256"), request.header("X-Content-Sha256"));
                assertFalse(new String(request.body(), StandardCharsets.UTF_8).contains("test-sk"));
            }
            for (var request : service.requests.subList(1, service.requests.size())) {
                assertEquals("example-task", request.json().path("task_id").asText());
                assertEquals("dressing_diffusionV2", request.json().path("req_key").asText());
                assertTrue(request.json().path("req_json").isTextual());
                assertFalse(JSON.readTree(request.json().path("req_json").asText()).path("return_url").asBoolean(true));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void httpFailuresAreSafeAndNeverResubmit(int status) throws Exception {
        try (var service = new MockService(request -> new Reply(status, PRIVATE_MARKER))) {
            var error = assertThrows(VolcengineTryOnRenderer.ServiceFailure.class, () -> render(renderer(service)));
            assertEquals(status, error.status());
            assertSafe(error);
            assertEquals(1, service.requests.size());
            service.assertHealthy();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {50200, 50205, 50207, 50213, 50400, 50411, 50511, 50429, 50500})
    void serviceErrorsInHttp200AreNotSuccessOrRetried(int code) throws Exception {
        try (var service = new MockService(request -> new Reply(200,
                "{\"code\":" + code + ",\"message\":\"" + PRIVATE_MARKER + "\","
                        + "\"data\":{\"task_id\":\"must-not-be-polled\"}}"))) {
            assertSafe(assertThrows(Exception.class, () -> render(renderer(service))));
            assertEquals(1, service.requests.size());
            service.assertHealthy();
        }
    }

    @Test void gatewayErrorTakesPrecedenceOverMisleadingSuccessFields() throws Exception {
        try (var service = new MockService(request -> new Reply(200,
                "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"SignatureDoesNotMatch\",\"Message\":\""
                        + PRIVATE_MARKER + "\"}},\"code\":10000,\"data\":{\"task_id\":\"must-not-be-polled\"}}"))) {
            assertSafe(assertThrows(Exception.class, () -> render(renderer(service))));
            assertEquals(1, service.requests.size());
        }
    }

    @Test void malformedJsonDoesNotLeakParserInputOrRetry() throws Exception {
        try (var service = new MockService(request -> new Reply(200, "{\"data\":" + PRIVATE_MARKER + "}"))) {
            assertSafe(assertThrows(Exception.class, () -> render(renderer(service))));
            assertEquals(1, service.requests.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "not_found", "unknown_state", ""})
    void missingExpiredAndUnknownTasksFailWithoutAnotherSubmission(String status) throws Exception {
        try (var service = new MockService(request -> new Reply(200,
                request.action().equals("CVSubmitTask") ? SUBMITTED : state(status)))) {
            assertSafe(assertThrows(Exception.class, () -> render(renderer(service))));
            assertEquals(List.of("CVSubmitTask", "CVGetResult"),
                    service.requests.stream().map(Request::action).toList());
        }
    }

    @Test void doneWithFailureCodeDoesNotAcceptImage() throws Exception {
        String failure = done(image("png", 80, 96)).replace("\"code\":10000", "\"code\":50511");
        try (var service = new MockService(request -> new Reply(200,
                request.action().equals("CVSubmitTask") ? SUBMITTED : failure))) {
            assertSafe(assertThrows(Exception.class, () -> render(renderer(service))));
            assertEquals(2, service.requests.size());
        }
    }

    @Test void successfulSubmissionWithoutTaskIdFailsBeforePolling() throws Exception {
        try (var service = new MockService(request -> new Reply(200, "{\"code\":10000,\"data\":{}}"))) {
            assertSafe(assertThrows(Exception.class, () -> render(renderer(service))));
            assertEquals(1, service.requests.size());
        }
    }

    @Test void failedResultPollNeverStartsAnotherPaidTask() throws Exception {
        try (var service = new MockService(request -> request.action().equals("CVSubmitTask")
                ? new Reply(200, SUBMITTED) : new Reply(503, PRIVATE_MARKER))) {
            var error = assertThrows(VolcengineTryOnRenderer.ServiceFailure.class, () -> render(renderer(service)));
            assertEquals(503, error.status());
            assertSafe(error);
            assertEquals(List.of("CVSubmitTask", "CVGetResult"),
                    service.requests.stream().map(Request::action).toList());
        }
    }

    @Test
    @Timeout(5)
    void resultWaitTimeoutDoesNotResubmit() throws Exception {
        try (var service = new MockService(request -> new Reply(200,
                request.action().equals("CVSubmitTask") ? SUBMITTED : state("generating")))) {
            var renderer = new VolcengineTryOnRenderer(true, "test-ak", "test-sk", service.endpoint(),
                    Duration.ofSeconds(2), Duration.ofSeconds(30), CLOCK);
            assertThrows(java.util.concurrent.TimeoutException.class, () -> render(renderer));
            assertEquals(1, service.requests.stream().filter(r -> r.action().equals("CVSubmitTask")).count());
            assertEquals(2, service.requests.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "invalid-base64", "jpeg", "empty", "url-only", "truncated-png"})
    void rejectsUnusableImageOutputs(String kind) throws Exception {
        String completed = switch (kind) {
            case "invalid-base64" -> "{\"code\":10000,\"data\":{\"status\":\"done\",\"binary_data_base64\":[\"%%%\"]}}";
            case "jpeg" -> done(image("jpg", 80, 96));
            case "empty" -> "{\"code\":10000,\"data\":{\"status\":\"done\",\"binary_data_base64\":[]}}";
            case "url-only" -> "{\"code\":10000,\"data\":{\"status\":\"done\",\"image_urls\":[\"https://example.invalid/private.png\"]}}";
            case "truncated-png" -> done(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0});
            default -> done(new byte[]{1, 2, 3, 4});
        };
        try (var service = new MockService(request -> new Reply(200,
                request.action().equals("CVSubmitTask") ? SUBMITTED : completed))) {
            assertSafe(assertThrows(Exception.class, () -> render(renderer(service))));
            assertEquals(2, service.requests.size());
        }
    }

    @Test void missingCredentialsAndDisabledModeCannotGenerate() throws Exception {
        for (var renderer : List.of(
                new VolcengineTryOnRenderer(false, "test-ak", "test-sk", 30),
                new VolcengineTryOnRenderer(true, "", "test-sk", 30),
                new VolcengineTryOnRenderer(true, "test-ak", " ", 30),
                new VolcengineTryOnRenderer(true, null, null, 30))) {
            assertFalse(renderer.available());
            assertThrows(IllegalStateException.class, () -> render(renderer));
        }
        assertTrue(new VolcengineTryOnRenderer(true, "test-ak", "test-sk", 30).available());
    }

    @Test void malformedPortraitIsRejectedBeforeSendingImages() throws Exception {
        try (var service = new MockService(request -> new Reply(200, SUBMITTED))) {
            var product = product();
            var renderer = renderer(service);
            assertThrows(Exception.class, () -> renderer.render(product, product.skus().get(0), new byte[]{1, 2, 3}));
            assertTrue(service.requests.isEmpty());
        }
    }

    @Test
    @Timeout(10)
    void reportsLocalSlotWaitAndCancelsWithoutAnotherSubmission() throws Exception {
        var firstPoll = new CountDownLatch(1);
        var queued = new CountDownLatch(1);
        var releaseFirst = new java.util.concurrent.atomic.AtomicBoolean();
        byte[] output = image("png", 80, 96);
        try (var service = new MockService(request -> {
            if (request.action().equals("CVSubmitTask")) return new Reply(200, SUBMITTED);
            firstPoll.countDown();
            return new Reply(200, releaseFirst.get() ? done(output) : state("generating"));
        })) {
            var renderer = renderer(service);
            var firstFailure = new AtomicReference<Throwable>();
            var secondFailure = new AtomicReference<Throwable>();
            var first = new Thread(() -> {
                try { render(renderer); } catch (Throwable error) { firstFailure.set(error); }
            }, "volc-first-slot-test");
            var second = new Thread(() -> {
                try {
                    var product = product();
                    renderer.render(product, product.skus().get(0), image("png", 96, 128), stage -> {
                        if (stage == TryOnService.RenderStage.WAITING_SLOT) queued.countDown();
                    });
                } catch (Throwable error) { secondFailure.set(error); }
            }, "volc-waiting-slot-test");
            first.setDaemon(true);
            second.setDaemon(true);
            try {
                first.start();
                assertTrue(firstPoll.await(3, TimeUnit.SECONDS));
                second.start();
                assertTrue(queued.await(3, TimeUnit.SECONDS));
                assertEquals(1, service.requests.stream().filter(r -> r.action().equals("CVSubmitTask")).count());
                second.interrupt();
                second.join(2000);
                assertFalse(second.isAlive());
                assertInstanceOf(InterruptedException.class, secondFailure.get());
                releaseFirst.set(true);
                first.join(2000);
                assertFalse(first.isAlive());
                assertNull(firstFailure.get());
                assertEquals(1, service.requests.stream().filter(r -> r.action().equals("CVSubmitTask")).count());
                service.assertHealthy();
            } finally {
                releaseFirst.set(true);
                first.interrupt();
                second.interrupt();
                first.join(1000);
                second.join(1000);
            }
        }
    }

    @Test
    @Timeout(10)
    void interruptionStopsPollingWithoutResubmittingPaidTask() throws Exception {
        var firstPoll = new CountDownLatch(1);
        try (var service = new MockService(request -> {
            if (request.action().equals("CVSubmitTask")) return new Reply(200, SUBMITTED);
            firstPoll.countDown();
            return new Reply(200, state("generating"));
        })) {
            var renderer = new VolcengineTryOnRenderer(true, "test-ak", "test-sk", service.endpoint(),
                    Duration.ofSeconds(60), Duration.ofSeconds(30), CLOCK);
            var failure = new AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                try { render(renderer); }
                catch (Throwable error) { failure.set(error); }
            }, "volc-cancellation-test");
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(firstPoll.await(5, TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(3000);
                assertFalse(worker.isAlive(), "Cancellation must interrupt an active poll or its wait");
                assertInstanceOf(InterruptedException.class, failure.get());
                assertEquals(1, service.requests.stream().filter(r -> r.action().equals("CVSubmitTask")).count());
                assertEquals(2, service.requests.size());
            } finally {
                worker.interrupt();
                worker.join(1000);
            }
        }
    }

    private static Models.Product product() { return new CoreService(new TraceBus()).product("p14"); }

    private static byte[] render(VolcengineTryOnRenderer renderer) throws Exception {
        var product = product();
        return renderer.render(product, product.skus().get(0), image("png", 96, 128));
    }

    private static VolcengineTryOnRenderer renderer(MockService service) {
        return new VolcengineTryOnRenderer(true, "test-ak", "test-sk", service.endpoint(),
                Duration.ofSeconds(5), Duration.ofMillis(1), CLOCK);
    }

    private static String state(String state) {
        return "{\"code\":10000,\"data\":{\"status\":\"" + state + "\"}}";
    }

    private static String done(byte[] bytes) {
        return "{\"code\":10000,\"data\":{\"status\":\"done\",\"binary_data_base64\":[\""
                + Base64.getEncoder().encodeToString(bytes) + "\"]}}";
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(30, 80, 160));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(220, 180, 140));
        graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out));
        image.flush();
        return out.toByteArray();
    }

    private static void assertSafe(Throwable failure) {
        for (int depth = 0; failure != null && depth < 10; failure = failure.getCause(), depth++) {
            String message = String.valueOf(failure.getMessage());
            assertFalse(message.contains(PRIVATE_MARKER), "Upstream details must not appear even in exception causes");
            assertFalse(message.contains("test-sk"));
            assertFalse(message.contains("private.png"));
        }
    }

    private record Reply(int status, String body) {}

    private record Request(String method, URI uri, Map<String, List<String>> headers, byte[] body) {
        String action() {
            for (String part : uri.getRawQuery().split("&"))
                if (part.startsWith("Action=")) return part.substring("Action=".length());
            return "";
        }
        JsonNode json() throws IOException { return JSON.readTree(body); }
        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
        }
    }

    @FunctionalInterface private interface Handler { Reply reply(Request request) throws Exception; }

    private static final class MockService implements AutoCloseable {
        final HttpServer server;
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

        MockService(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI(),
                            Map.copyOf(exchange.getRequestHeaders()), exchange.getRequestBody().readAllBytes());
                    requests.add(request);
                    Reply reply = handler.reply(request);
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable error) {
                    handlerFailure.compareAndSet(null, error);
                    try { exchange.sendResponseHeaders(500, -1); } catch (IOException ignored) {}
                } finally { exchange.close(); }
            });
            server.start();
        }
        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
        void assertHealthy() { assertNull(handlerFailure.get(), "Local mock handler failed"); }
        @Override public void close() { server.stop(0); }
    }
}
