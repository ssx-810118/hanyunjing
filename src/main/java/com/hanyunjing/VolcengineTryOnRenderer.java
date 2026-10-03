package com.hanyunjing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Dedicated image dressing V2: one signed submission, followed by read-only result polls. */
@Component
public class VolcengineTryOnRenderer implements TryOnService.Renderer {
    static final String REQUEST_KEY = "dressing_diffusionV2";
    private static final URI ENDPOINT = URI.create("https://visual.volcengineapi.com/");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_INPUT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 20 * 1024 * 1024;
    private static final String NOT_CONFIGURED = "火山引擎图片换装 V2 尚未配置，请在服务端配置 Access Key / Secret Key 并开通该服务。";
    private final boolean enabled;
    private final String accessKey;
    private final String secretKey;
    private final URI endpoint;
    private final Duration timeout;
    private final Duration pollInterval;
    private final Clock clock;
    private final HttpClient client;
    private final Semaphore remoteSlot = new Semaphore(1, true);
    @Value("${app.media.directory:.local/media}") private String mediaDirectory=".local/media";

    enum Reason {
        AUTH("火山引擎鉴权或服务权限校验失败，请检查 AK/SK 并确认已开通图片换装 V2。"),
        QUOTA("火山引擎额度或账户余额不足，请到火山引擎控制台检查。"),
        BUSY("火山引擎图片换装服务繁忙，请稍后手动重试。"),
        INPUT("火山引擎无法处理这张图片，请更换清晰的人像照片后重试。"),
        REVIEW("图片未通过火山引擎内容审核，本次未生成可用结果。"),
        EXPIRED("火山引擎任务已失效，本次未收到结果，请稍后手动重试。"),
        RESPONSE("火山引擎未返回可用的试穿图片，请稍后手动重试。");

        final String message;
        Reason(String message) { this.message = message; }
    }

    /** Only fixed messages and fixed reason identifiers can reach the UI or trace. */
    public static final class ServiceFailure extends IllegalStateException {
        private final int status;
        private final Reason reason;
        ServiceFailure(int status, Reason reason) {
            super(reason.message);
            this.status = status;
            this.reason = reason;
        }
        public int status() { return status; }
        public String safeCode() { return reason.name(); }
    }

    @Autowired
    public VolcengineTryOnRenderer(
            @Value("${app.tryon.enabled:false}") boolean enabled,
            @Value("${app.tryon.volcengine.access-key:}") String accessKey,
            @Value("${app.tryon.volcengine.secret-key:}") String secretKey,
            @Value("${app.tryon.timeout-seconds:600}") int timeoutSeconds) {
        this(enabled, accessKey, secretKey, ENDPOINT,
            Duration.ofSeconds(Math.max(10, Math.min(timeoutSeconds, 600))), Duration.ofSeconds(2), Clock.systemUTC());
    }

    // Endpoint injection is package-private and only used by local transport tests.
    VolcengineTryOnRenderer(boolean enabled, String accessKey, String secretKey, URI endpoint,
                           Duration timeout, Duration pollInterval, Clock clock) {
        this.enabled = enabled;
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
        this.clock = clock;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public boolean available() { return enabled && !accessKey.isBlank() && !secretKey.isBlank(); }
    @Override public String mode() { return "virtual-tryon-v2"; }
    @Override public String provider() { return "volcengine-virtual-tryon-v2"; }
    @Override public int maxPortraitDimension() { return 1920; }
    @Override public String statusMessage(boolean ready) {
        return ready ? "火山引擎图片换装 V2 已配置；实际可用性取决于服务开通、凭据和账户额度。" : NOT_CONFIGURED;
    }

    @Override public byte[] render(Models.Product product, Models.Sku sku, byte[] portraitPng) throws Exception {
        return render(product, sku, portraitPng, stage -> {});
    }

    @Override public byte[] render(Models.Product product, Models.Sku sku, byte[] portraitPng,
                                   Consumer<TryOnService.RenderStage> progress) throws Exception {
        if (!available()) throw new IllegalStateException(NOT_CONFIGURED);
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean slotAcquired = false;
        byte[] portraitJpeg = null;
        byte[] submission = null;
        try {
            checkInterrupted();
            progress.accept(TryOnService.RenderStage.PREPARING_IMAGE);
            portraitJpeg = portraitJpeg(portraitPng);
            if (product == null || !product.id().matches("[A-Za-z0-9_-]{1,64}"))
                throw new ServiceFailure(400, Reason.INPUT);
            byte[] garment;
            String image=product.images().isEmpty()?"":product.images().get(0);
            if(image.matches("/api/media/[A-Za-z0-9_-]+\\.png")) {
                var root=java.nio.file.Path.of(mediaDirectory).toAbsolutePath().normalize();
                var file=root.resolve(image.substring("/api/media/".length())).normalize();
                if(!file.startsWith(root)||!java.nio.file.Files.isRegularFile(file))throw new ServiceFailure(400,Reason.INPUT);
                try(var input=java.nio.file.Files.newInputStream(file)){garment=input.readNBytes(MAX_INPUT_BYTES);}
            } else {
                var reference = new ClassPathResource("static/images/tryon-garments/" + product.id() + ".jpg");
                try (var input = reference.getInputStream()) { garment = input.readNBytes(MAX_INPUT_BYTES); }
            }
            if (garment.length == 0 || garment.length >= MAX_INPUT_BYTES)
                throw new ServiceFailure(400, Reason.INPUT);
            var body = JSON.createObjectNode();
            body.put("req_key", REQUEST_KEY);
            body.put("req_image_store_type", 0);
            body.putArray("binary_data_base64").add(Base64.getEncoder().encodeToString(portraitJpeg))
                .add(Base64.getEncoder().encodeToString(garment));
            body.putObject("garment").putArray("data").addObject().put("type", "full");
            body.putObject("inference_config").put("keep_head", true).put("num_steps", 16);
            submission = JSON.writeValueAsBytes(body);
            // Preparation happens before the remote slot, so image conversion cannot delay
            // an already prepared task. The slot still prevents overlapping paid requests.
            slotAcquired = remoteSlot.tryAcquire(0, TimeUnit.NANOSECONDS);
            if (!slotAcquired) {
                progress.accept(TryOnService.RenderStage.WAITING_SLOT);
                slotAcquired = remoteSlot.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS);
                if (!slotAcquired) throw new TimeoutException();
            }
            // A timeout or failure never causes another paid CVSubmitTask request.
            progress.accept(TryOnService.RenderStage.UPLOADING);
            JsonNode submitted = request("CVSubmitTask", submission, deadline);
            String taskId = submitted.path("data").path("task_id").asText("");
            if (taskId.isBlank() || taskId.length() > 1024) throw new ServiceFailure(502, Reason.RESPONSE);
            Arrays.fill(submission, (byte) 0);
            submission = null;
            Arrays.fill(portraitJpeg, (byte) 0);
            portraitJpeg = null;
            byte[] query = JSON.writeValueAsBytes(Map.of("req_key", REQUEST_KEY, "task_id", taskId,
                "req_json", "{\"return_url\":false}"));
            while (true) {
                checkInterrupted();
                JsonNode data = request("CVGetResult", query, deadline).path("data");
                switch (data.path("status").asText("")) {
                    case "done": return resultImage(data);
                    case "in_queue", "generating":
                        progress.accept(data.path("status").asText().equals("in_queue")
                            ? TryOnService.RenderStage.REMOTE_QUEUED : TryOnService.RenderStage.RENDERING);
                        TimeUnit.NANOSECONDS.sleep(Math.min(remaining(deadline), pollInterval.toNanos()));
                        break;
                    case "not_found", "expired": throw new ServiceFailure(410, Reason.EXPIRED);
                    default: throw new ServiceFailure(502, Reason.RESPONSE);
                }
            }
        } finally {
            if (portraitJpeg != null) Arrays.fill(portraitJpeg, (byte) 0);
            if (submission != null) Arrays.fill(submission, (byte) 0);
            if (slotAcquired) remoteSlot.release();
        }
    }

    private JsonNode request(String action, byte[] payload, long deadline) throws Exception {
        checkInterrupted();
        Duration requestTimeout = Duration.ofNanos(Math.min(remaining(deadline), Duration.ofSeconds(60).toNanos()));
        URI uri = URI.create(endpoint.toString().replaceAll("/+$", "") + "/?" + VolcengineV4Signer.query(action));
        var builder = HttpRequest.newBuilder(uri).timeout(requestTimeout);
        VolcengineV4Signer.sign(endpoint, action, payload, accessKey, secretKey, clock.instant()).forEach(builder::header);
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        var pending = client.sendAsync(request, info -> new BoundedBody());
        try {
            var response = pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
            JsonNode root;
            try { root = JSON.readTree(response.body()); }
            catch (IOException invalid) { throw httpFailure(response.statusCode()); }
            if (root == null || !root.isObject()) throw httpFailure(response.statusCode());
            JsonNode metadataError = root.path("ResponseMetadata").path("Error");
            if (!metadataError.isMissingNode() && !metadataError.isNull()) {
                String code = metadataError.path("Code").asText("");
                Reason reason = switch (code) {
                    case "InvalidAccessKeyId", "SignatureDoesNotMatch", "AccessDenied", "InvalidSecretToken",
                         "MissingAuthenticationToken", "InvalidAuthorization", "Unauthorized", "InvalidCredential" -> Reason.AUTH;
                    case "QuotaExceeded", "InsufficientBalance", "AccountOverdue", "InsufficientQuota" -> Reason.QUOTA;
                    case "Throttling", "LimitExceeded", "RequestLimitExceeded", "ServiceUnavailable" -> Reason.BUSY;
                    default -> httpReason(response.statusCode());
                };
                throw new ServiceFailure(response.statusCode(), reason);
            }
            int code = root.path("code").asInt(-1);
            if (code != 10000) {
                Reason reason = switch (code) {
                    case 50400 -> Reason.AUTH;
                    case 50429 -> Reason.BUSY;
                    case 50205, 50207, 50213 -> Reason.INPUT;
                    case 50411, 50511, 50412, 50512, 50413 -> Reason.REVIEW;
                    default -> httpReason(response.statusCode());
                };
                throw new ServiceFailure(response.statusCode(), reason);
            }
            if (response.statusCode() != 200) throw httpFailure(response.statusCode());
            return root;
        } finally {
            pending.cancel(true);
        }
    }

    private static ServiceFailure httpFailure(int status) { return new ServiceFailure(status, httpReason(status)); }
    private static Reason httpReason(int status) {
        return switch (status) {
            case 401, 403 -> Reason.AUTH;
            case 402 -> Reason.QUOTA;
            case 429, 503 -> Reason.BUSY;
            default -> Reason.RESPONSE;
        };
    }

    private static byte[] resultImage(JsonNode data) throws ServiceFailure {
        JsonNode images = data.path("binary_data_base64");
        if (!images.isArray() || images.isEmpty() || !images.path(0).isTextual())
            throw new ServiceFailure(502, Reason.RESPONSE);
        String encoded = images.path(0).asText();
        if (encoded.isBlank() || encoded.length() > ((MAX_RESULT_BYTES + 2) / 3) * 4)
            throw new ServiceFailure(502, Reason.RESPONSE);
        try {
            byte[] png = Base64.getDecoder().decode(encoded);
            if (png.length == 0 || png.length > MAX_RESULT_BYTES) throw new IOException();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException();
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (!"PNG".equalsIgnoreCase(reader.getFormatName()) || width < 1 || height < 1
                        || width > 4096 || height > 4096 || (long) width * height > 16_000_000)
                        throw new IOException();
                    // Decoding here also rejects truncated/corrupt images before reporting success.
                    if (reader.read(0) == null) throw new IOException();
                } finally { reader.dispose(); }
            }
            return png;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ServiceFailure(502, Reason.RESPONSE);
        }
    }

    private static byte[] portraitJpeg(byte[] png) throws IOException {
        if (png == null || png.length == 0 || png.length > MAX_RESULT_BYTES)
            throw new ServiceFailure(400, Reason.INPUT);
        BufferedImage source;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ServiceFailure(400, Reason.INPUT);
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096
                    || (long) width * height > 16_000_000) throw new ServiceFailure(400, Reason.INPUT);
                source = reader.read(0);
            } finally { reader.dispose(); }
        }
        if (source == null) throw new ServiceFailure(400, Reason.INPUT);
        double scale = Math.min(1.0, 1920.0 / Math.max(source.getWidth(), source.getHeight()));
        var rgb = new BufferedImage(Math.max(1, (int) Math.round(source.getWidth() * scale)),
            Math.max(1, (int) Math.round(source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, rgb.getWidth(), rgb.getHeight(), null);
        } finally { graphics.dispose(); source.flush(); }
        var output = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(rgb, null, null), params);
            imageOutput.flush();
        } finally { writer.dispose(); rgb.flush(); }
        byte[] jpeg = output.toByteArray();
        if (jpeg.length == 0 || jpeg.length >= MAX_INPUT_BYTES) throw new ServiceFailure(400, Reason.INPUT);
        return jpeg;
    }

    private static long remaining(long deadline) throws TimeoutException, InterruptedException {
        checkInterrupted();
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException();
        return nanos;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                int count = chunk.remaining();
                if ((long) data.size() + count > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Image response exceeds limit"));
                    return;
                }
                byte[] bytes = new byte[count];
                chunk.get(bytes);
                data.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(data.toByteArray()); }
    }
}
