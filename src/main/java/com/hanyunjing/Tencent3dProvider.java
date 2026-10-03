package com.hanyunjing;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Real Hunyuan 3D Pro: one paid submit, polling the same JobId, then a bounded GLB download. */
@Component
public class Tencent3dProvider implements OrbitService.Provider {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final boolean enabled;private final String secretId,secretKey,region,model;
    private final URI endpoint;private final Duration timeout,pollInterval;private final Clock clock;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    @Autowired public Tencent3dProvider(@Value("${app.orbit.enabled:false}") boolean enabled,
        @Value("${app.orbit.tencent.secret-id:}") String id,@Value("${app.orbit.tencent.secret-key:}") String key,
        @Value("${app.orbit.tencent.region:ap-guangzhou}") String region,@Value("${app.orbit.tencent.model:3.0}") String model,
        @Value("${app.orbit.timeout-seconds:900}") int timeout){
        this(enabled,id,key,region,model,URI.create("https://ai3d.tencentcloudapi.com/"),Duration.ofSeconds(Math.max(30,Math.min(900,timeout))),Duration.ofSeconds(5),Clock.systemUTC());
    }
    Tencent3dProvider(boolean enabled,String id,String key,String region,String model,URI endpoint,Duration timeout,Duration pollInterval,Clock clock){
        this.enabled=enabled;secretId=id.trim();secretKey=key.trim();this.region=region;this.model=model;this.endpoint=endpoint;this.timeout=timeout;this.pollInterval=pollInterval;this.clock=clock;
    }
    @Override public boolean available(){return enabled&&!secretId.isBlank()&&!secretKey.isBlank()&&!secretId.contains("${")&&!secretKey.contains("${")&&Set.of("3.0","3.1").contains(model);}
    @Override public byte[] generate(byte[] png,Consumer<String> progress)throws Exception{
        if(!available())throw new IllegalStateException("腾讯混元3D尚未配置");
        long deadline=System.nanoTime()+timeout.toNanos();byte[] jpeg=null,body=null;
        try{
            jpeg=prepare(png);body=JSON.writeValueAsBytes(Map.of("ImageBase64",Base64.getEncoder().encodeToString(jpeg),"Model",model,"GenerateType","Normal","EnablePBR",true,"FaceCount",100000));
            progress.accept("SUBMITTING");
            var submitted=request("SubmitHunyuanTo3DProJob",body,deadline);
            String id=submitted.path("JobId").asText();if(id.isBlank()||id.length()>200)throw new IllegalStateException("腾讯云未返回有效3D任务编号");
            Arrays.fill(body,(byte)0);body=null;Arrays.fill(jpeg,(byte)0);jpeg=null;
            byte[] query=JSON.writeValueAsBytes(Map.of("JobId",id));
            while(true){
                var reply=request("QueryHunyuanTo3DProJob",query,deadline);
                switch(reply.path("Status").asText()){
                    case "WAIT","RUN" -> {progress.accept(reply.path("Status").asText().equals("WAIT")?"REMOTE_QUEUED":"MODELING");TimeUnit.NANOSECONDS.sleep(Math.min(remaining(deadline),pollInterval.toNanos()));}
                    case "FAIL" -> throw failure(reply.path("ErrorCode").asText());
                    case "DONE" -> {
                        String url=null;for(var file:reply.path("ResultFile3Ds"))if(file.path("Type").asText().equalsIgnoreCase("GLB")){url=file.path("Url").asText();break;}
                        if(url==null||url.isBlank())throw new IllegalStateException("腾讯云未返回GLB模型，未生成环绕结果");
                        progress.accept("DOWNLOADING");URI uri=URI.create(url);validateDownload(uri);
                        var response=send(HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.min(remaining(deadline),Duration.ofSeconds(120).toNanos()))).GET().build(),GlbValidator.MAX_BYTES,deadline);
                        if(response.statusCode()!=200)throw new IllegalStateException("腾讯云模型文件下载失败");
                        byte[] glb=response.body();GlbValidator.validate(glb);return glb;
                    }
                    default -> throw new IllegalStateException("腾讯云返回了未知3D任务状态");
                }
            }
        }finally{if(jpeg!=null)Arrays.fill(jpeg,(byte)0);if(body!=null)Arrays.fill(body,(byte)0);}
    }
    private JsonNode request(String action,byte[] body,long deadline)throws Exception{
        var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(Math.min(remaining(deadline),Duration.ofSeconds(60).toNanos())));
        TencentV3Signer.sign(endpoint,action,body,secretId,secretKey,region,clock.instant()).forEach(builder::header);
        var response=send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),1024*1024,deadline);
        if(response.statusCode()!=200)throw failure(response.statusCode()==401||response.statusCode()==403?"AuthFailure":response.statusCode()==429?"RequestLimitExceeded":"");
        JsonNode root=JSON.readTree(response.body());if(root==null||!root.path("Response").isObject())throw new IllegalStateException("腾讯云3D响应格式错误");
        var data=root.path("Response");if(data.has("Error"))throw failure(data.path("Error").path("Code").asText());return data;
    }
    private HttpResponse<byte[]> send(HttpRequest request,int max,long deadline)throws Exception{
        var pending=http.sendAsync(request,info->new BoundedBody(max));
        try{return pending.get(remaining(deadline),TimeUnit.NANOSECONDS);}finally{pending.cancel(true);}
    }
    private static long remaining(long deadline)throws Exception{if(Thread.currentThread().isInterrupted())throw new InterruptedException();long left=deadline-System.nanoTime();if(left<=0)throw new TimeoutException();return left;}
    private static IllegalStateException failure(String code){
        String message=code.startsWith("AuthFailure")||code.startsWith("UnauthorizedOperation")?"腾讯混元3D鉴权失败，请核对SecretId、SecretKey及服务权限":
            code.contains("Balance")||code.contains("ResourceInsufficient")||code.contains("Quota")?"腾讯混元3D额度不足，请检查账户余额与额度":
            code.startsWith("RequestLimitExceeded")||code.startsWith("LimitExceeded")?"腾讯混元3D服务繁忙，请稍后手动重试":
            code.startsWith("InvalidParameter")?"腾讯混元3D无法处理本次图片或配置，请检查服务配置并更换清晰全身图":"腾讯混元3D未完成本次生成，请稍后手动重试";
        return new IllegalStateException(message);
    }
    private void validateDownload(URI uri){
        // Local protocol tests only. Production endpoint and hosts cannot be supplied by clients.
        if(endpoint.getScheme().equals("http")&&Set.of("127.0.0.1","localhost").contains(endpoint.getHost())&&endpoint.getHost().equals(uri.getHost())&&endpoint.getPort()==uri.getPort())return;
        String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||
            !(host.endsWith(".myqcloud.com")||host.endsWith(".tencentcos.cn")||host.endsWith(".tencentcos.com")))throw new IllegalStateException("腾讯云模型下载域名不在支持范围，未请求该地址");
    }
    static byte[] prepare(byte[] png)throws Exception{
        if(png==null||png.length>10*1024*1024)throw new IllegalArgumentException("换装图片无效");
        BufferedImage source;try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(png))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("换装图片无法解码");var reader=readers.next();
            try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<128||h<128||w>4096||h>4096||(long)w*h>16000000)throw new IllegalArgumentException("3D输入图片边长须至少128像素，请重新生成清晰的全身图");source=reader.read(0);}finally{reader.dispose();}
        }
        double ratio=Math.max(Math.min(1,2048.0/Math.max(source.getWidth(),source.getHeight())),128.0/Math.min(source.getWidth(),source.getHeight()));
        var clean=new BufferedImage(Math.max(128,(int)(source.getWidth()*ratio)),Math.max(128,(int)(source.getHeight()*ratio)),BufferedImage.TYPE_INT_RGB);
        var graphics=clean.createGraphics();try{graphics.setColor(Color.WHITE);graphics.fillRect(0,0,clean.getWidth(),clean.getHeight());graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(source,0,0,clean.getWidth(),clean.getHeight(),null);}finally{graphics.dispose();source.flush();}
        var writer=ImageIO.getImageWritersByFormatName("jpeg").next();var output=new ByteArrayOutputStream();
        try(var stream=new MemoryCacheImageOutputStream(output)){writer.setOutput(stream);var params=writer.getDefaultWriteParam();params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);params.setCompressionQuality(.9f);writer.write(null,new IIOImage(clean,null,null),params);stream.flush();}
        finally{writer.dispose();clean.flush();}
        if(output.size()>4*1024*1024)throw new IllegalArgumentException("3D输入图片过大，请使用较小的全身图");return output.toByteArray();
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]>{
        final int max;final CompletableFuture<byte[]> future=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        BoundedBody(int max){this.max=max;}
        public CompletionStage<byte[]> getBody(){return future;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> chunks){for(var chunk:chunks){int length=chunk.remaining();if((long)bytes.size()+length>max){subscription.cancel();future.completeExceptionally(new IOException("3D response too large"));return;}byte[] part=new byte[length];chunk.get(part);bytes.writeBytes(part);}subscription.request(1);}
        public void onError(Throwable t){future.completeExceptionally(t);}public void onComplete(){future.complete(bytes.toByteArray());}
    }
}
