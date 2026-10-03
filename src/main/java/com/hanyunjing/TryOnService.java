package com.hanyunjing;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class TryOnService {
    public enum RenderStage { PREPARING_IMAGE, WAITING_SLOT, UPLOADING, REMOTE_QUEUED, RENDERING }

    public interface Renderer {
        byte[] render(Models.Product product, Models.Sku sku, byte[] portraitPng) throws Exception;
        default byte[] render(Models.Product product, Models.Sku sku, byte[] portraitPng,
                              Consumer<RenderStage> progress) throws Exception {
            progress.accept(RenderStage.RENDERING);
            return render(product, sku, portraitPng);
        }
        default int maxPortraitDimension() { return 4096; }
        default boolean available() { return true; }
        default String mode() { return "image-edit"; }
        default String provider() { return "configured-image-service"; }
        default String statusMessage(boolean ready) {
            return ready ? "图片试穿服务已配置，可使用已授权照片生成试穿。" : "图片试穿服务尚未配置，暂不可生成试穿。";
        }
    }

    public record Status(boolean ready, String mode, String message, String provider,
                         boolean comparison, boolean multiAngle, boolean visualCheck) {}

    private static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PORTRAITS = 64;
    private static final int MAX_PORTRAITS_PER_SESSION = 3;
    private static final int MAX_TASKS = 128;
    private static final int MAX_TASKS_PER_SESSION = 8;
    private static final long MAX_PORTRAIT_MEMORY = 64L * 1024 * 1024;
    private static final long MAX_OUTPUT_MEMORY = 128L * 1024 * 1024;
    private static final String SAFE_FAILURE = "试穿生成失败，未生成可用结果，请稍后手动重试。";
    private static final List<String> INPUT_CHECKS = List.of(
        "已验证照片使用授权、AI处理授权、图片解码、尺寸及有效SKU",
        "未执行人体识别、服装视觉一致性或多角度验证");
    private static final List<String> OUTPUT_CHECKS = List.of(
        "已验证照片使用授权、AI处理授权、图片解码、尺寸及有效SKU",
        "已验证生成图片可解码、PNG格式和像素上限",
        "未执行人体识别、服装视觉一致性或多角度验证");

    private final CoreService core;
    private final Renderer renderer;
    private final Clock clock;
    private final Map<String, PortraitData> portraits = new HashMap<>();
    private final Map<String, Models.TryOn> tasks = new HashMap<>();
    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, byte[]> outputs = new HashMap<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private final Map<String, String> activeBySession = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> expirations = new HashMap<>();
    private final ThreadPoolExecutor worker;
    private final ScheduledThreadPoolExecutor expiryWorker;
    private final Semaphore uploadSlots = new Semaphore(2);
    private long portraitMemory;
    private long outputMemory;
    private boolean closed;
    private final List<Consumer<String>> resultRemovalListeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    void onResultRemoved(Consumer<String> listener){resultRemovalListeners.add(listener);}

    private record PortraitData(Models.Portrait metadata, byte[] png) {}
    private record NormalizedImage(byte[] png, int width, int height) {}

    private final class Job implements Runnable {
        private final String id;
        private final String sessionId;
        private final Models.Product product;
        private final Models.Sku sku;
        private volatile boolean cancelled;
        private Thread runningThread;
        private long startedAt;

        private Job(String id, String sessionId, Models.Product product, Models.Sku sku) {
            this.id = id;
            this.sessionId = sessionId;
            this.product = product;
            this.sku = sku;
        }
        @Override public void run() { runJob(this); }
    }

    @Autowired
    public TryOnService(CoreService core, ObjectProvider<Renderer> provider) {
        this(core, provider.getIfAvailable(() -> new Renderer() {
            @Override public byte[] render(Models.Product product, Models.Sku sku, byte[] portraitPng) {
                throw new IllegalStateException("图片试穿服务尚未配置");
            }
            @Override public boolean available() { return false; }
        }), Clock.systemUTC());
    }

    TryOnService(CoreService core, Renderer renderer) {
        this(core, renderer, Clock.systemUTC());
    }

    TryOnService(CoreService core, Renderer renderer, Clock clock) {
        this.core = Objects.requireNonNull(core);
        this.renderer = Objects.requireNonNull(renderer);
        this.clock = Objects.requireNonNull(clock);
        var counter = new AtomicInteger();
        worker = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), runnable -> {
                var thread = new Thread(runnable, "tryon-render-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        expiryWorker = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "tryon-expiry");
            thread.setDaemon(true);
            return thread;
        });
        expiryWorker.setRemoveOnCancelPolicy(true);
    }

    public synchronized Status status() {
        boolean ready;
        try { ready = !closed && renderer.available(); }
        catch (RuntimeException ignored) { ready = false; }
        return new Status(ready, renderer.mode(), renderer.statusMessage(ready),
            renderer.provider(), true, false, false);
    }

    public Models.Portrait upload(String sid, MultipartFile file,
                                               Boolean authorized, Boolean aiAuthorized) throws Exception {
        WebSupport.session(sid);
        if (!Boolean.TRUE.equals(authorized) || !Boolean.TRUE.equals(aiAuthorized))
            throw new IllegalArgumentException("必须显式 authorized=true 且 aiAuthorized=true");
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES)
            throw new IllegalArgumentException("图片为空或超过5MB");
        synchronized (this) { checkUploadCapacity(sid); }
        // Decoding a phone photo must not block progress, cancellation, or other sessions.
        // Resize before lossless storage to the renderer's existing input limit; this avoids
        // encoding a full-resolution PNG only to decode and shrink it again during rendering.
        if (!uploadSlots.tryAcquire()) throw new IllegalStateException("正在整理其他照片，请稍后重新上传");
        NormalizedImage image = null;
        boolean retained = false;
        try {
            image = normalize(file.getBytes(), false, renderer.maxPortraitDimension());
            synchronized (this) {
                checkUploadCapacity(sid);
                if (portraitMemory + image.png().length > MAX_PORTRAIT_MEMORY)
                    throw new IllegalStateException("照片暂存空间已满，请稍后重试");
                var portrait = new Models.Portrait(UUID.randomUUID().toString(), sid, image.width(), image.height(),
                    "png", now().plusSeconds(1800));
                portraits.put(portrait.id(), new PortraitData(portrait, image.png()));
                retained = true;
                portraitMemory += image.png().length;
                expirations.put(portrait.id(), expiryWorker.schedule(() -> {
                    synchronized (TryOnService.this) { removePortrait(portrait.id()); }
                }, 1800, TimeUnit.SECONDS));
                core.trace(sid, "PORTRAIT_AUTHORIZED", "已获得照片使用与AI处理授权；去除元数据的PNG仅在内存中保留30分钟");
                return portrait;
            }
        } finally {
            if (!retained && image != null) Arrays.fill(image.png(), (byte) 0);
            uploadSlots.release();
        }
    }

    private void checkUploadCapacity(String sid) {
        ensureOpen();
        cleanupExpired();
        if (portraits.size() >= MAX_PORTRAITS || portraits.values().stream()
            .filter(p -> p.metadata().sessionId().equals(sid)).count() >= MAX_PORTRAITS_PER_SESSION)
            throw new IllegalStateException("照片暂存数量已达上限，请先删除旧照片");
    }

    public synchronized byte[] portrait(String id, String sid) {
        cleanupExpired();
        return requirePortrait(id, sid).png().clone();
    }

    public synchronized Models.TryOn generate(Models.Generate request) {
        WebSupport.session(request.sessionId());
        ensureOpen();
        cleanupExpired();
        var portrait = requirePortrait(request.portraitId(), request.sessionId());
        var product = core.product(request.productId());
        var sku = product.skus().stream()
            .filter(s -> s.id().equals(request.skuId()) && s.stock() > 0).findFirst().orElseThrow();
        String activeId = activeBySession.get(request.sessionId());
        if (activeId != null) {
            var existing = tasks.get(activeId);
            if (existing != null && active(existing) && request.portraitId().equals(owners.get(activeId))
                && existing.productId().equals(request.productId()) && existing.skuId().equals(request.skuId()))
                return existing;
            throw new IllegalStateException("当前会话已有试穿任务仍在执行，请等待结束");
        }
        var serviceStatus = status();
        if (!serviceStatus.ready()) throw new IllegalStateException(serviceStatus.message());
        if (tasks.size() >= MAX_TASKS || tasks.values().stream()
            .filter(t -> t.sessionId().equals(request.sessionId())).count() >= MAX_TASKS_PER_SESSION)
            throw new IllegalStateException("试穿暂存记录已达上限，请先删除旧照片及关联结果");
        if (outputMemory + (jobs.size() + 1L) * MAX_IMAGE_BYTES > MAX_OUTPUT_MEMORY)
            throw new IllegalStateException("试穿结果暂存空间已满，请先删除旧照片及关联结果");
        String id = UUID.randomUUID().toString();
        String originalUrl = "/api/tryon/portrait/" + request.portraitId() + "/image?sessionId=" + request.sessionId();
        var task = new Models.TryOn(id, request.sessionId(), product.id(), sku.id(), "QUEUED", "PARSING", 0,
            false, null, INPUT_CHECKS, portrait.metadata().expiresAt(), originalUrl, null);
        var job = new Job(id, request.sessionId(), product, sku);
        tasks.put(id, task);
        owners.put(id, request.portraitId());
        jobs.put(id, job);
        activeBySession.put(request.sessionId(), id);
        try { worker.execute(job); }
        catch (RejectedExecutionException rejected) {
            tasks.remove(id);
            owners.remove(id);
            releaseJob(job);
            throw new IllegalStateException("试穿服务繁忙，请稍后主动重试");
        }
        core.trace(request.sessionId(), "TRYON_QUEUED", "真实图片试穿任务已排队；不会自动重试付费请求");
        return task;
    }

    private void runJob(Job job) {
        byte[] portraitPng = null;
        try {
            synchronized (this) {
                job.runningThread = Thread.currentThread();
                job.startedAt = System.nanoTime();
                var task = liveTask(job.id);
                if (job.cancelled || task == null) return;
                portraitPng = portraits.get(owners.get(job.id)).png().clone();
                tasks.put(job.id, state(task, "RUNNING", "MATCHING", 0, null, null, INPUT_CHECKS));
                core.trace(task.sessionId(), "TRYON_MATCHING", "使用已选商品、颜色尺码和授权人像");
            }
            if (job.cancelled || Thread.currentThread().isInterrupted()) return;
            byte[] rendered = renderer.render(job.product, job.sku, portraitPng, stage -> updateProgress(job, stage));
            synchronized (this) {
                var task = liveTask(job.id);
                if (job.cancelled || task == null) return;
                tasks.put(job.id, state(task, "RUNNING", "SELF_CHECK", 1, null, null, INPUT_CHECKS));
            }
            if (rendered == null || rendered.length > MAX_IMAGE_BYTES)
                throw new IllegalArgumentException("生成图片无效");
            byte[] normalized = normalize(rendered, true).png();
            synchronized (this) {
                var task = liveTask(job.id);
                if (job.cancelled || task == null) return;
                if (outputMemory + normalized.length > MAX_OUTPUT_MEMORY)
                    throw new IllegalStateException("生成图片暂存空间已满");
                outputs.put(job.id, normalized);
                outputMemory += normalized.length;
                tasks.put(job.id, state(task, "DONE", "SELF_CHECK", 1,
                    "/api/tryon/result/" + job.id + "?sessionId=" + task.sessionId(), null, OUTPUT_CHECKS));
                core.trace(task.sessionId(), "TRYON_DONE", "真实生成结果已通过格式和尺寸检查；未执行视觉一致性检查");
            }
        } catch (Exception failure) {
            Throwable cause = failure;
            for (int depth = 0; depth < 4 && cause.getCause() != null && cause.getCause() != cause; depth++) cause = cause.getCause();
            boolean timeout = cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException;
            String message = timeout ? "图片服务等待超时，本次未收到结果。请稍后手动重试。" : SAFE_FAILURE;
            String failureKind = cause.getClass().getSimpleName();
            if (cause instanceof VolcengineTryOnRenderer.ServiceFailure serviceFailure) {
                failureKind += "_" + serviceFailure.safeCode() + "_HTTP_" + serviceFailure.status();
                message = serviceFailure.getMessage();
            }
            synchronized (this) {
                var task = liveTask(job.id);
                if (task != null && !job.cancelled) {
                    tasks.put(job.id, state(task, "FAILED", task.stage(), task.attempts(), null, message, INPUT_CHECKS));
                    core.trace(task.sessionId(), "TRYON_FAILED", "图片换装未得到可用结果；失败类型：" + failureKind + "；未自动重试");
                }
            }
        } finally {
            if (portraitPng != null) Arrays.fill(portraitPng, (byte) 0);
            synchronized (this) { releaseJob(job); }
        }
    }

    private synchronized void updateProgress(Job job, RenderStage stage) {
        var task = liveTask(job.id);
        if (job.cancelled || task == null || stage.name().equals(task.stage())) return;
        int attempts = switch (stage) {
            case UPLOADING, REMOTE_QUEUED, RENDERING -> 1;
            default -> task.attempts();
        };
        tasks.put(job.id, state(task, "RUNNING", stage.name(), attempts, null, null, INPUT_CHECKS));
        String detail = switch (stage) {
            case PREPARING_IMAGE -> "正在整理授权照片与所选服饰";
            case WAITING_SLOT -> "等待前一个试穿任务完成，尚未发送本次生成请求";
            case UPLOADING -> "正在向图片换装服务提交照片与服饰；只提交一次";
            case REMOTE_QUEUED -> "图片换装服务已接受请求，等待远端排队";
            case RENDERING -> "图片换装服务正在生成试穿效果";
        };
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.startedAt);
        core.trace(task.sessionId(), "TRYON_" + stage.name(), detail + "；任务已用时 " + elapsedMillis + " 毫秒");
    }

    public synchronized Models.TryOn get(String id, String sid) {
        WebSupport.session(sid);
        cleanupExpired();
        var task = tasks.get(id);
        if (task == null || !task.sessionId().equals(sid)) throw new NoSuchElementException();
        return task;
    }

    public synchronized byte[] result(String id, String sid) {
        var task = get(id, sid);
        byte[] output = outputs.get(id);
        if (!task.status().equals("DONE") || output == null)
            throw new IllegalStateException("任务尚未完成、已失败或已取消");
        return output.clone();
    }

    public synchronized void cancel(String id, String sid) {
        var task = get(id, sid);
        discardOutput(id);
        tasks.put(id, state(task, "CANCELLED", task.stage(), task.attempts(), null, null, task.checks()));
        cancelJob(id);
        core.trace(sid, "TRYON_CANCELLED", "任务已取消，结果已删除；已发送的服务请求可能仍在结束处理中");
    }

    public synchronized void deletePortrait(String id, String sid) {
        cleanupExpired();
        requirePortrait(id, sid);
        removePortrait(id);
        core.trace(sid, "PORTRAIT_DELETED", "内存人像、关联任务与结果已删除；在途响应将被丢弃");
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void cleanup() { cleanupExpired(); }

    private Instant now() { return Instant.now(clock); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("试穿服务已停止"); }
    private static boolean active(Models.TryOn task) {
        return task.status().equals("QUEUED") || task.status().equals("RUNNING");
    }

    private PortraitData requirePortrait(String id, String sid) {
        WebSupport.session(sid);
        var portrait = portraits.get(id);
        if (portrait == null || !portrait.metadata().sessionId().equals(sid)
            || !portrait.metadata().expiresAt().isAfter(now())) throw new NoSuchElementException();
        return portrait;
    }

    private Models.TryOn liveTask(String id) {
        var task = tasks.get(id);
        if (closed || task == null || !active(task) || !task.expiresAt().isAfter(now())) return null;
        var portrait = portraits.get(owners.get(id));
        return portrait == null || !portrait.metadata().expiresAt().isAfter(now()) ? null : task;
    }

    private Models.TryOn state(Models.TryOn task, String status, String stage, int attempts,
                               String resultUrl, String error, List<String> checks) {
        return new Models.TryOn(task.id(), task.sessionId(), task.productId(), task.skuId(), status, stage,
            attempts, false, resultUrl, checks, task.expiresAt(), task.originalUrl(), error);
    }

    private void releaseJob(Job job) {
        jobs.remove(job.id, job);
        activeBySession.remove(job.sessionId, job.id);
        job.runningThread = null;
    }

    private void cancelJob(String id) {
        var job = jobs.get(id);
        if (job == null) return;
        job.cancelled = true;
        if (worker.remove(job)) releaseJob(job);
        else if (job.runningThread != null) job.runningThread.interrupt();
    }

    private void discardOutput(String id) {
        resultRemovalListeners.forEach(listener->listener.accept(id));
        byte[] removed = outputs.remove(id);
        if (removed != null) {
            outputMemory -= removed.length;
            Arrays.fill(removed, (byte) 0);
        }
    }

    private void removePortrait(String id) {
        var expiration = expirations.remove(id);
        if (expiration != null) expiration.cancel(false);
        for (String taskId : new ArrayList<>(owners.keySet())) {
            if (id.equals(owners.get(taskId))) {
                cancelJob(taskId);
                discardOutput(taskId);
                tasks.remove(taskId);
                owners.remove(taskId);
            }
        }
        var removed = portraits.remove(id);
        if (removed != null) {
            portraitMemory -= removed.png().length;
            Arrays.fill(removed.png(), (byte) 0);
        }
    }

    private void cleanupExpired() {
        Instant now = now();
        for (var portrait : new ArrayList<>(portraits.values()))
            if (!portrait.metadata().expiresAt().isAfter(now)) removePortrait(portrait.metadata().id());
    }

    private static NormalizedImage normalize(byte[] bytes, boolean output) throws Exception {
        return normalize(bytes, output, 4096);
    }

    private static NormalizedImage normalize(byte[] bytes, boolean output, int maxDimension) throws Exception {
        if (bytes.length == 0 || bytes.length > (output ? MAX_IMAGE_BYTES : MAX_UPLOAD_BYTES))
            throw new IllegalArgumentException("图片为空或超过大小限制");
        if (output && (bytes.length < 8 || !Arrays.equals(Arrays.copyOf(bytes, 8),
            new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})))
            throw new IllegalArgumentException("生成图片必须为PNG");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("无法解码图片，只支持PNG/JPEG");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!(output ? format.equals("png") : List.of("png", "jpeg", "jpg").contains(format))
                    || width < 64 || height < 64 || width > 4096 || height > 4096
                    || (long) width * height > 16_000_000)
                    throw new IllegalArgumentException("仅支持PNG/JPEG，尺寸64-4096且像素不超过1600万");
                var decoded = reader.read(0);
                if (decoded == null) throw new IllegalArgumentException("图片解码失败");
                double scale = output ? 1.0 : Math.min(1.0, Math.max(64, Math.min(4096, maxDimension)) / (double) Math.max(width, height));
                int targetWidth = Math.max(1, (int) Math.round(width * scale));
                int targetHeight = Math.max(1, (int) Math.round(height * scale));
                var clean = new BufferedImage(targetWidth, targetHeight, decoded.getColorModel().hasAlpha()
                    ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var graphics = clean.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
                }
                finally { graphics.dispose(); decoded.flush(); }
                var out = new ByteArrayOutputStream();
                try (var imageOutput = new MemoryCacheImageOutputStream(out)) {
                    if (!ImageIO.write(clean, "png", imageOutput)) throw new IllegalArgumentException("无法规范化图片");
                    imageOutput.flush();
                    if (out.size() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("规范化图片超过10MB，请缩小尺寸后重试");
                    return new NormalizedImage(out.toByteArray(), targetWidth, targetHeight);
                } finally { clean.flush(); }
            } finally { reader.dispose(); }
        } catch (IOException malformed) {
            throw new IllegalArgumentException("无法完整解码图片，请使用有效PNG/JPEG");
        }
    }

    @PreDestroy public synchronized void close() {
        closed = true;
        for (String id : new ArrayList<>(portraits.keySet())) removePortrait(id);
        worker.shutdownNow();
        expiryWorker.shutdownNow();
    }
}
