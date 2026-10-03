package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Local image fixtures and controlled renderers only; no image provider is contacted. */
@Timeout(15)
class TryOnServiceTests {
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final Duration WAIT = Duration.ofSeconds(5);
    private final List<TryOnService> services = new ArrayList<>();
    private final List<ControlledRenderer> controlledRenderers = new ArrayList<>();
    private CoreService core;
    private TraceBus bus;
    private byte[] png;

    @BeforeEach void setup() throws Exception {
        bus = new TraceBus();
        core = new CoreService(bus);
        png = image("png", 96, 128);
    }

    @AfterEach void close() {
        // A renderer deliberately ignores interruption so cancellation tests can simulate late responses.
        controlledRenderers.forEach(renderer -> renderer.release.countDown());
        services.forEach(TryOnService::close);
    }

    @Test void bothExplicitConsentsAreRequiredAndPngMetadataIsRemoved() throws Exception {
        var service = service((product, sku, portrait) -> png);
        String privateMetadata = "private-location-and-camera-test-marker";
        byte[] withMetadata = withTextChunk(png, privateMetadata);
        var file = file("portrait.png", "image/png", withMetadata);
        for (Boolean authorization : Arrays.asList(false, null)) {
            assertThrows(IllegalArgumentException.class,
                () -> service.upload("consent", file, authorization, true));
            assertThrows(IllegalArgumentException.class,
                () -> service.upload("consent", file, true, authorization));
        }

        var portrait = service.upload("consent", file, true, true);
        byte[] normalized = service.portrait(portrait.id(), "consent");
        assertPng(normalized, 96, 128);
        assertEquals(96, portrait.width());
        assertEquals(128, portrait.height());
        assertFalse(new String(normalized, StandardCharsets.ISO_8859_1).contains(privateMetadata));
        assertPixelsEqual(png, normalized);
        assertThrows(NoSuchElementException.class, () -> service.portrait(portrait.id(), "other"));
    }

    @Test void invalidOversizedUnsupportedAndOutOfBoundsUploadsAreRejected() throws Exception {
        var service = service((product, sku, portrait) -> png);
        List<MockMultipartFile> invalid = List.of(
            file("empty.png", "image/png", new byte[0]),
            file("fake.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8)),
            file("large.png", "image/png", new byte[5 * 1024 * 1024 + 1]),
            file("portrait.gif", "image/gif", image("gif", 96, 128)),
            file("small.png", "image/png", image("png", 63, 128)),
            file("wide.png", "image/png", withDimensions(png, 4097, 128)),
            file("pixels.png", "image/png", withDimensions(png, 4001, 4000))
        );
        for (var file : invalid) {
            assertThrows(IllegalArgumentException.class,
                () -> service.upload("invalid", file, true, true), file.getOriginalFilename());
        }
    }

    @Test void uploadsAreResizedOnceToTheProvidersExistingInputLimitWithoutUpscaling() throws Exception {
        var service = service(new TryOnService.Renderer() {
            @Override public int maxPortraitDimension() { return 1920; }
            @Override public byte[] render(Models.Product product, Models.Sku sku, byte[] portrait) { return png; }
        });
        byte[] large = image("jpeg", 2400, 3200);
        var portrait = service.upload("scaled", file("portrait.jpg", "image/jpeg", large), true, true);
        assertEquals(1440, portrait.width());
        assertEquals(1920, portrait.height());
        assertPng(service.portrait(portrait.id(), "scaled"), 1440, 1920);
        var small = upload(service, "scaled");
        assertPng(service.portrait(small.id(), "scaled"), 96, 128);
        assertTrue(core.traces("scaled").stream().noneMatch(trace -> trace.type().startsWith("TRYON_")));
    }

    @Test void readingAndNormalizingAnUploadDoesNotLockTaskStatusAndCancellation() throws Exception {
        var renderer = controlled(png);
        var service = service(renderer);
        var existing = upload(service, "existing");
        var task = service.generate(request("existing", existing));
        renderer.awaitEntered();
        var reading = new CountDownLatch(1);
        var releaseUpload = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var slowFile = new MockMultipartFile("file", "portrait.png", "image/png", png) {
            @Override public byte[] getBytes() {
                reading.countDown();
                try {
                    if (!releaseUpload.await(5, TimeUnit.SECONDS)) throw new AssertionError("Upload was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return png.clone();
            }
        };
        var uploadThread = new Thread(() -> {
            try { service.upload("uploading", slowFile, true, true); }
            catch (Throwable error) { failure.set(error); }
        }, "slow-upload-test");
        uploadThread.setDaemon(true);
        try {
            uploadThread.start();
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                assertTrue(service.status().ready());
                assertEquals("RUNNING", service.get(task.id(), "existing").status());
                service.cancel(task.id(), "existing");
                assertEquals("CANCELLED", service.get(task.id(), "existing").status());
            });
        } finally {
            releaseUpload.countDown();
            renderer.release.countDown();
            uploadThread.join(2000);
        }
        assertFalse(uploadThread.isAlive());
        assertNull(failure.get());
    }

    @Test void providerProgressReportsQueueUploadAndGenerationWithoutDuplicateTraceEntries() throws Exception {
        var prepared = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var report = new AtomicReference<java.util.function.Consumer<TryOnService.RenderStage>>();
        var service = service(new TryOnService.Renderer() {
            @Override public byte[] render(Models.Product product, Models.Sku sku, byte[] portrait) {
                throw new AssertionError("The progress-aware renderer should be used");
            }
            @Override public byte[] render(Models.Product product, Models.Sku sku, byte[] portrait,
                                            java.util.function.Consumer<TryOnService.RenderStage> progress) throws Exception {
                report.set(progress);
                progress.accept(TryOnService.RenderStage.PREPARING_IMAGE);
                prepared.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Renderer was not released");
                return png;
            }
        });
        var portrait = upload(service, "progress");
        var task = service.generate(request("progress", portrait));
        try {
            assertTrue(prepared.await(3, TimeUnit.SECONDS));
            assertEquals("PREPARING_IMAGE", service.get(task.id(), "progress").stage());
            assertEquals(0, service.get(task.id(), "progress").attempts());
            report.get().accept(TryOnService.RenderStage.WAITING_SLOT);
            assertEquals("WAITING_SLOT", service.get(task.id(), "progress").stage());
            assertEquals(0, service.get(task.id(), "progress").attempts());
            report.get().accept(TryOnService.RenderStage.UPLOADING);
            assertEquals(1, service.get(task.id(), "progress").attempts());
            report.get().accept(TryOnService.RenderStage.REMOTE_QUEUED);
            report.get().accept(TryOnService.RenderStage.REMOTE_QUEUED);
            assertEquals("REMOTE_QUEUED", service.get(task.id(), "progress").stage());
            assertEquals(1, core.traces("progress").stream()
                .filter(trace -> trace.type().equals("TRYON_REMOTE_QUEUED")).count());
            report.get().accept(TryOnService.RenderStage.RENDERING);
            assertEquals("RENDERING", service.get(task.id(), "progress").stage());
            service.cancel(task.id(), "progress");
            report.get().accept(TryOnService.RenderStage.REMOTE_QUEUED);
            assertEquals("CANCELLED", service.get(task.id(), "progress").status());
            assertEquals("RENDERING", service.get(task.id(), "progress").stage());
        } finally { release.countDown(); }
    }

    @Test void rendererReceivesNormalizedJpegBytesAndTheExactSelectedSku() throws Exception {
        byte[] jpeg = image("jpeg", 112, 144);
        var renderer = controlled(png);
        var service = service(renderer);
        var portrait = service.upload("render", file("portrait.jpg", "image/jpeg", jpeg), true, true);
        byte[] normalized = service.portrait(portrait.id(), "render");
        assertPng(normalized, 112, 144);
        assertPixelsEqual(jpeg, normalized);

        var task = service.generate(new Models.Generate("render", portrait.id(), "p8", "p8-L"));
        renderer.awaitEntered();
        assertEquals(core.product("p8"), renderer.product.get());
        assertEquals(core.product("p8").skus().stream().filter(s -> s.id().equals("p8-L"))
            .findFirst().orElseThrow(), renderer.sku.get());
        assertArrayEquals(normalized, renderer.portrait.get());
        assertFalse(task.demo());
        renderer.release.countDown();

        var done = awaitStatus(service, task.id(), "render", "DONE");
        assertFalse(done.demo());
        assertNull(done.error());
        assertNotNull(done.resultUrl());
        assertNotNull(done.originalUrl());
        assertTrue(done.originalUrl().contains(portrait.id()));
        assertArrayEquals(png, service.result(task.id(), "render"));
        assertEquals(1, renderer.calls.get());
        assertThrows(NoSuchElementException.class, () -> service.result(task.id(), "other"));
        mvc(service).perform(get(done.resultUrl()))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(content().bytes(png));
        mvc(service).perform(get(done.originalUrl()))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(content().bytes(normalized));
    }

    @Test void portraitHttpEndpointRequiresBothConsentsAndEnforcesOwnershipAndDeletion() throws Exception {
        var service = service((product, sku, portrait) -> png);
        var mvc = mvc(service);
        var upload = file("portrait.png", "image/png", png);
        mvc.perform(multipart("/api/tryon/portrait").file(upload)
                .param("sessionId", "http-owner").param("authorized", "true"))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/tryon/portrait").file(upload)
                .param("sessionId", "http-owner").param("aiAuthorized", "true"))
            .andExpect(status().isBadRequest());
        String response = mvc.perform(multipart("/api/tryon/portrait").file(upload)
                .param("sessionId", "http-owner").param("authorized", "true").param("aiAuthorized", "true"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(response).path("data").path("id").asText();
        assertFalse(id.isBlank());
        String path = "/api/tryon/portrait/" + id;
        mvc.perform(get(path + "/image").param("sessionId", "http-owner"))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(content().bytes(service.portrait(id, "http-owner")));
        mvc.perform(get(path + "/image").param("sessionId", "other"))
            .andExpect(status().isNotFound());
        mvc.perform(delete(path).param("sessionId", "other"))
            .andExpect(status().isNotFound());
        assertNotNull(service.portrait(id, "http-owner"));
        mvc.perform(delete(path).param("sessionId", "http-owner")).andExpect(status().isOk());
        mvc.perform(get(path + "/image").param("sessionId", "http-owner"))
            .andExpect(status().isNotFound());
    }

    @Test void providerFailureIsSafeTerminalAndNeverRetriesOrFabricatesSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String privateError = "sk-private-test-key https://private-provider.example/private-query";
        var service = service((product, sku, portrait) -> {
            calls.incrementAndGet();
            throw new IOException(privateError);
        });
        var portrait = upload(service, "failure");
        var task = service.generate(request("failure", portrait));
        var failed = awaitStatus(service, task.id(), "failure", "FAILED");
        assertFalse(failed.demo());
        assertNull(failed.resultUrl());
        assertNotNull(failed.error());
        assertFalse(failed.error().isBlank());
        assertFalse(failed.toString().contains("sk-private-test-key"));
        assertFalse(failed.toString().contains("private-provider.example"));
        assertFalse(core.traces("failure").toString().contains(privateError));
        assertThrows(IllegalStateException.class, () -> service.result(task.id(), "failure"));
        await().during(Duration.ofMillis(150)).atMost(WAIT).untilAsserted(() -> {
            assertEquals(1, calls.get());
            assertEquals("FAILED", service.get(task.id(), "failure").status());
        });
    }

    @Test void invalidRendererOutputsFailWithoutRetryOrFallback() throws Exception {
        List<byte[]> invalid = Arrays.asList(null, new byte[]{1, 2, 3}, image("jpeg", 96, 128),
            image("png", 63, 128), withDimensions(png, 4097, 128),
            withDimensions(png, 4001, 4000), new byte[10 * 1024 * 1024 + 1]);
        for (int i = 0; i < invalid.size(); i++) {
            byte[] output = invalid.get(i);
            AtomicInteger calls = new AtomicInteger();
            var service = service((product, sku, portrait) -> {
                calls.incrementAndGet();
                return output;
            });
            String sid = "bad-output-" + i;
            var task = service.generate(request(sid, upload(service, sid)));
            var failed = awaitStatus(service, task.id(), sid, "FAILED");
            assertFalse(failed.demo());
            assertNull(failed.resultUrl());
            assertNotNull(failed.error());
            assertEquals(1, calls.get());
            assertThrows(IllegalStateException.class, () -> service.result(task.id(), sid));
        }
    }

    @Test void cancellingAnInflightRenderCannotResurrectTheResultOrStartAnotherPaidCall() throws Exception {
        var renderer = controlled(png);
        var service = service(renderer);
        var portrait = upload(service, "cancel");
        var task = service.generate(request("cancel", portrait));
        renderer.awaitEntered();
        assertThrows(NoSuchElementException.class, () -> service.cancel(task.id(), "other"));
        service.cancel(task.id(), "cancel");
        assertEquals("CANCELLED", service.get(task.id(), "cancel").status());
        assertThrows(IllegalStateException.class, () -> service.generate(
            new Models.Generate("cancel", portrait.id(), "p1", "p1-L")));
        renderer.release.countDown();
        renderer.awaitReturned();
        await().during(Duration.ofMillis(150)).atMost(WAIT).untilAsserted(() -> {
            var cancelled = service.get(task.id(), "cancel");
            assertEquals("CANCELLED", cancelled.status());
            assertNull(cancelled.resultUrl());
            assertThrows(IllegalStateException.class, () -> service.result(task.id(), "cancel"));
            assertEquals(1, renderer.calls.get());
        });
        assertArrayEquals(png, service.portrait(portrait.id(), "cancel"));
    }

    @Test void deletingAPortraitDuringRenderingRemovesEveryAssociatedResource() throws Exception {
        var renderer = controlled(png);
        var service = service(renderer);
        var portrait = upload(service, "delete");
        var task = service.generate(request("delete", portrait));
        renderer.awaitEntered();
        assertThrows(NoSuchElementException.class, () -> service.deletePortrait(portrait.id(), "other"));
        service.deletePortrait(portrait.id(), "delete");
        renderer.release.countDown();
        renderer.awaitReturned();
        await().during(Duration.ofMillis(150)).atMost(WAIT).untilAsserted(() -> {
            assertThrows(NoSuchElementException.class, () -> service.portrait(portrait.id(), "delete"));
            assertThrows(NoSuchElementException.class, () -> service.get(task.id(), "delete"));
            assertThrows(NoSuchElementException.class, () -> service.result(task.id(), "delete"));
        });
        assertThrows(NoSuchElementException.class, () -> service.generate(request("delete", portrait)));
        assertEquals(1, renderer.calls.get());
    }

    @Test void expiryDuringRenderingCannotMakeAPortraitOrLateResultAccessibleAgain() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        var renderer = controlled(png);
        var service = service(renderer, clock);
        var portrait = upload(service, "expiry");
        var task = service.generate(request("expiry", portrait));
        renderer.awaitEntered();
        clock.set(portrait.expiresAt().plusSeconds(1));
        assertThrows(NoSuchElementException.class, () -> service.portrait(portrait.id(), "expiry"));
        assertThrows(NoSuchElementException.class, () -> service.get(task.id(), "expiry"));
        service.cleanup();
        renderer.release.countDown();
        renderer.awaitReturned();
        await().during(Duration.ofMillis(150)).atMost(WAIT).untilAsserted(() -> {
            assertThrows(NoSuchElementException.class, () -> service.portrait(portrait.id(), "expiry"));
            assertThrows(NoSuchElementException.class, () -> service.get(task.id(), "expiry"));
            assertThrows(NoSuchElementException.class, () -> service.result(task.id(), "expiry"));
        });
        assertThrows(NoSuchElementException.class, () -> service.generate(request("expiry", portrait)));
        assertEquals(1, renderer.calls.get());
    }

    @Test void anActiveRequestIsDeduplicatedAndDifferentRequestsConflictOnlyWithinItsSession() throws Exception {
        var renderer = controlled(png);
        var service = service(renderer);
        var portrait = upload(service, "dedup");
        var request = request("dedup", portrait);
        var task = service.generate(request);
        renderer.awaitEntered();
        assertEquals(task.id(), service.generate(request).id());
        assertEquals(task.id(), service.generate(request).id());
        assertThrows(IllegalStateException.class, () -> service.generate(
            new Models.Generate("dedup", portrait.id(), "p1", "p1-L")));
        var otherPortrait = upload(service, "independent");
        var independent = service.generate(request("independent", otherPortrait));
        assertNotEquals(task.id(), independent.id());
        renderer.release.countDown();
        awaitStatus(service, task.id(), "dedup", "DONE");
        awaitStatus(service, independent.id(), "independent", "DONE");
        assertEquals(2, renderer.calls.get());
    }

    @Test void readinessAndCapabilityStatusAreHonestAndUnavailableRenderersCannotRun() throws Exception {
        var readyService = service((product, sku, portrait) -> png);
        var ready = readyService.status();
        assertTrue(ready.ready());
        assertEquals("image-edit", ready.mode());
        assertEquals("configured-image-service", ready.provider());
        assertTrue(ready.comparison());
        assertFalse(ready.multiAngle());
        assertFalse(ready.visualCheck());
        AtomicInteger calls = new AtomicInteger();
        var unavailable = service(new TryOnService.Renderer() {
            @Override public boolean available() { return false; }
            @Override public byte[] render(Models.Product product, Models.Sku sku, byte[] portrait) {
                calls.incrementAndGet();
                throw new AssertionError("An unavailable provider must never be invoked");
            }
        });
        assertFalse(unavailable.status().ready());
        assertNotNull(unavailable.status().message());
        assertFalse(unavailable.status().message().isBlank());
        var portrait = upload(unavailable, "unavailable");
        assertThrows(IllegalStateException.class, () -> unavailable.generate(request("unavailable", portrait)));
        assertEquals(0, calls.get());
        mvc(unavailable).perform(get("/api/tryon/status"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(jsonPath("$.data.ready").value(false))
            .andExpect(jsonPath("$.data.mode").value("image-edit"))
            .andExpect(jsonPath("$.data.provider").value("configured-image-service"))
            .andExpect(jsonPath("$.data.comparison").value(true))
            .andExpect(jsonPath("$.data.multiAngle").value(false))
            .andExpect(jsonPath("$.data.visualCheck").value(false));
    }

    @Test void foreignPortraitsAndSkusFromOtherProductsNeverReachTheRenderer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service = service((product, sku, portrait) -> {
            calls.incrementAndGet();
            return png;
        });
        var portrait = upload(service, "owner");
        assertThrows(NoSuchElementException.class, () -> service.generate(
            new Models.Generate("other", portrait.id(), "p1", "p1-M")));
        assertThrows(NoSuchElementException.class, () -> service.generate(
            new Models.Generate("owner", portrait.id(), "p1", "p2-M")));
        assertThrows(NoSuchElementException.class, () -> service.generate(
            new Models.Generate("owner", portrait.id(), "missing-product", "p1-M")));
        assertEquals(0, calls.get());
    }

    @Test void portraitCapacityIsBoundedPerSessionAndGloballyAndDeletionReleasesSlots() throws Exception {
        var service = service((product, sku, portrait) -> png);
        var first = upload(service, "photo-limit");
        var second = upload(service, "photo-limit");
        var third = upload(service, "photo-limit");
        assertThrows(IllegalStateException.class, () -> upload(service, "photo-limit"));
        service.deletePortrait(first.id(), "photo-limit");
        assertThrows(NoSuchElementException.class, () -> service.portrait(first.id(), "photo-limit"));
        var replacement = upload(service, "photo-limit");
        assertNotEquals(first.id(), replacement.id());
        assertThrows(IllegalStateException.class, () -> upload(service, "photo-limit"));

        var globalPortraits = new ArrayList<Models.Portrait>();
        for (int i = 0; i < 61; i++) globalPortraits.add(upload(service, "global-photo-" + i));
        assertThrows(IllegalStateException.class, () -> upload(service, "global-overflow"));
        assertArrayEquals(png, service.portrait(second.id(), "photo-limit"));
        assertArrayEquals(png, service.portrait(third.id(), "photo-limit"));
        assertArrayEquals(png, service.portrait(replacement.id(), "photo-limit"));
        for (var portrait : globalPortraits)
            assertArrayEquals(png, service.portrait(portrait.id(), portrait.sessionId()));

        var removed = globalPortraits.get(0);
        service.deletePortrait(removed.id(), removed.sessionId());
        var admitted = upload(service, "global-overflow");
        assertArrayEquals(png, service.portrait(admitted.id(), "global-overflow"));
        assertThrows(NoSuchElementException.class,
            () -> service.portrait(removed.id(), removed.sessionId()));
        assertThrows(IllegalStateException.class, () -> upload(service, "global-still-full"));
    }

    @Test void boundedQueueRejectsCleanlyAndCancellingQueuedWorkReleasesCapacityWithoutRenderingIt() throws Exception {
        var renderer = controlled(png);
        var service = service(renderer);
        var portraits = new ArrayList<Models.Portrait>();
        for (int i = 0; i < 7; i++) portraits.add(upload(service, "queue-" + i));
        var tasks = new ArrayList<Models.TryOn>();
        for (int i = 0; i < 2; i++) tasks.add(service.generate(request("queue-" + i, portraits.get(i))));
        await().atMost(WAIT).pollInterval(Duration.ofMillis(10))
            .untilAsserted(() -> assertEquals(2, renderer.calls.get()));
        for (int i = 2; i < 6; i++) tasks.add(service.generate(request("queue-" + i, portraits.get(i))));
        for (int i = 0; i < 6; i++)
            assertEquals(i < 2 ? "RUNNING" : "QUEUED", service.get(tasks.get(i).id(), "queue-" + i).status());

        var overflow = request("queue-6", portraits.get(6));
        // More rejections than the task-record limit expose leaked task records as well as a leaked session lock.
        for (int i = 0; i < 130; i++)
            assertThrows(IllegalStateException.class, () -> service.generate(overflow));
        assertEquals(2, renderer.calls.get());
        assertTrue(core.traces("queue-6").stream().noneMatch(t -> t.type().equals("TRYON_QUEUED")));

        var cancelled = tasks.get(2);
        service.cancel(cancelled.id(), "queue-2");
        assertEquals("CANCELLED", service.get(cancelled.id(), "queue-2").status());
        var admitted = service.generate(overflow);
        assertEquals("QUEUED", admitted.status());
        assertEquals(admitted.id(), service.generate(overflow).id());
        assertEquals(2, renderer.calls.get());
        renderer.release.countDown();
        for (int i = 0; i < tasks.size(); i++)
            if (i != 2) awaitStatus(service, tasks.get(i).id(), "queue-" + i, "DONE");
        awaitStatus(service, admitted.id(), "queue-6", "DONE");
        await().during(Duration.ofMillis(150)).atMost(WAIT).untilAsserted(() -> {
            assertEquals(6, renderer.calls.get());
            assertEquals("CANCELLED", service.get(cancelled.id(), "queue-2").status());
            assertThrows(IllegalStateException.class, () -> service.result(cancelled.id(), "queue-2"));
        });
    }

    private TryOnService service(TryOnService.Renderer renderer) {
        var service = new TryOnService(core, renderer);
        services.add(service);
        return service;
    }

    private TryOnService service(TryOnService.Renderer renderer, Clock clock) {
        var service = new TryOnService(core, renderer, clock);
        services.add(service);
        return service;
    }

    private ControlledRenderer controlled(byte[] result) {
        var renderer = new ControlledRenderer(result);
        controlledRenderers.add(renderer);
        return renderer;
    }

    private Models.Portrait upload(TryOnService service, String sid) throws Exception {
        return service.upload(sid, file("portrait.png", "image/png", png), true, true);
    }

    private static Models.Generate request(String sid, Models.Portrait portrait) {
        return new Models.Generate(sid, portrait.id(), "p1", "p1-M");
    }

    private MockMvc mvc(TryOnService service) {
        return MockMvcBuilders.standaloneSetup(new ApiController(core, service, mock(OnlineAgent.class), bus))
            .setControllerAdvice(new ApiErrors()).build();
    }

    private static Models.TryOn awaitStatus(TryOnService service, String id, String sid, String expected) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(10))
            .untilAsserted(() -> assertEquals(expected, service.get(id, sid).status()));
        return service.get(id, sid);
    }

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(33, 71, 103));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(207, 159, 83));
            graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }

    private static void assertPng(byte[] bytes, int width, int height) throws IOException {
        assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(bytes, PNG_SIGNATURE.length));
        var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertEquals(width, decoded.getWidth());
        assertEquals(height, decoded.getHeight());
    }

    private static void assertPixelsEqual(byte[] expected, byte[] actual) throws IOException {
        var first = ImageIO.read(new ByteArrayInputStream(expected));
        var second = ImageIO.read(new ByteArrayInputStream(actual));
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getWidth(), second.getWidth());
        assertEquals(first.getHeight(), second.getHeight());
        assertArrayEquals(first.getRGB(0, 0, first.getWidth(), first.getHeight(), null, 0, first.getWidth()),
            second.getRGB(0, 0, second.getWidth(), second.getHeight(), null, 0, second.getWidth()));
    }

    private static byte[] withTextChunk(byte[] png, String text) throws IOException {
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        byte[] value = ("Comment\0" + text).getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(value);
        var output = new ByteArrayOutputStream();
        var data = new DataOutputStream(output);
        data.write(png, 0, 33); // PNG signature and complete IHDR chunk.
        data.writeInt(value.length);
        data.write(type);
        data.write(value);
        data.writeInt((int) crc.getValue());
        data.write(png, 33, png.length - 33);
        return output.toByteArray();
    }

    /** Invalid dimensions are checked from IHDR before attempting an expensive decode. */
    private static byte[] withDimensions(byte[] png, int width, int height) {
        byte[] altered = png.clone();
        ByteBuffer.wrap(altered).putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(altered, 12, 17);
        ByteBuffer.wrap(altered).putInt(29, (int) crc.getValue());
        return altered;
    }

    private static final class ControlledRenderer implements TryOnService.Renderer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Models.Product> product = new AtomicReference<>();
        final AtomicReference<Models.Sku> sku = new AtomicReference<>();
        final AtomicReference<byte[]> portrait = new AtomicReference<>();
        final byte[] output;

        ControlledRenderer(byte[] output) { this.output = output; }

        @Override public byte[] render(Models.Product product, Models.Sku sku, byte[] portrait) {
            calls.incrementAndGet();
            this.product.set(product);
            this.sku.set(sku);
            this.portrait.set(portrait.clone());
            entered.countDown();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Renderer was not released");
                        return output;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                returned.countDown();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Renderer did not receive the request");
        }

        void awaitReturned() throws InterruptedException {
            assertTrue(returned.await(5, TimeUnit.SECONDS), "Renderer did not return after release");
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(Instant now) { this(new AtomicReference<>(now), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
