package com.hanyunjing;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Local protocol and geometry fixtures only. These tests never contact Tencent Cloud. */
@Timeout(20)
class OrbitTests {
    static final ObjectMapper JSON=new ObjectMapper();
    static byte[] image()throws Exception{var image=new BufferedImage(160,240,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    static byte[] glb(String extra)throws Exception{
        String text="{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"buffers\":[{\"byteLength\":36}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[1,1,0]}]"+extra+"}";
        byte[] raw=text.getBytes(StandardCharsets.UTF_8);int padded=(raw.length+3)/4*4;var b=ByteBuffer.allocate(12+8+padded+8+36).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(padded).putInt(0x4e4f534a).put(raw);while(b.position()<20+padded)b.put((byte)' ');
        b.putInt(36).putInt(0x004e4942);for(float f:new float[]{0,0,0,1,0,0,0,1,0})b.putFloat(f);return b.array();
    }
    static Models.Portrait portrait(TryOnService source)throws Exception{return source.upload("owner",new MockMultipartFile("file","test.png","image/png",image()),true,true);}
    static Models.TryOn sourceTask(TryOnService source,Models.Portrait p){var t=source.generate(new Models.Generate("owner",p.id(),"p14","p14-M"));await().atMost(Duration.ofSeconds(4)).until(()->source.get(t.id(),"owner").status().equals("DONE"));return source.get(t.id(),"owner");}
    @Test void confirmationIsolationIdempotencyAndSourceDeletion()throws Exception{
        var core=new CoreService(new TraceBus());byte[] expected=glb("");AtomicInteger calls=new AtomicInteger();
        try(var source=new CloseTryon(new TryOnService(core,(p,s,b)->b.clone()))){
            var p=portrait(source.value);var t=sourceTask(source.value,p);
            try(var orbit=new CloseOrbit(new OrbitService(source.value,new OrbitService.Provider(){public boolean available(){return true;}public byte[] generate(byte[] b,java.util.function.Consumer<String> progress){calls.incrementAndGet();return expected.clone();}}))){
                assertThrows(IllegalArgumentException.class,()->orbit.value.generate(t.id(),"owner",new OrbitService.Generate(false,null)));
                assertThrows(NoSuchElementException.class,()->orbit.value.generate(t.id(),"other",new OrbitService.Generate(true,null)));
                var result=orbit.value.generate(t.id(),"owner",new OrbitService.Generate(true,null));
                assertEquals(result.id(),orbit.value.generate(t.id(),"owner",new OrbitService.Generate(true,null)).id());
                await().atMost(Duration.ofSeconds(3)).until(()->orbit.value.get(result.id(),"owner").status().equals("DONE"));
                assertEquals(1,calls.get());assertEquals(t.expiresAt(),orbit.value.get(result.id(),"owner").expiresAt());
                assertArrayEquals(expected,orbit.value.model(result.id(),"owner"));
                assertThrows(NoSuchElementException.class,()->orbit.value.model(result.id(),"other"));
                source.value.deletePortrait(p.id(),"owner");assertThrows(NoSuchElementException.class,()->orbit.value.model(result.id(),"owner"));
            }
        }
    }
    @Test void lateResultCannotResurrectCancelledTaskAndRetryRequiresOldId()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var source=new CloseTryon(new TryOnService(new CoreService(new TraceBus()),(p,s,b)->b.clone()))){
            var t=sourceTask(source.value,portrait(source.value));
            try(var orbit=new CloseOrbit(new OrbitService(source.value,new OrbitService.Provider(){public boolean available(){return true;}public byte[] generate(byte[] b,java.util.function.Consumer<String> progress)throws Exception{calls.incrementAndGet();entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return glb("");}}))){
                var first=orbit.value.generate(t.id(),"owner",new OrbitService.Generate(true,null));assertTrue(entered.await(2,TimeUnit.SECONDS));orbit.value.cancel(first.id(),"owner");release.countDown();
                assertEquals(first.id(),orbit.value.generate(t.id(),"owner",new OrbitService.Generate(true,null)).id());
                assertThrows(IllegalStateException.class,()->orbit.value.model(first.id(),"owner"));
                var retry=orbit.value.generate(t.id(),"owner",new OrbitService.Generate(true,first.id()));assertNotEquals(first.id(),retry.id());
                await().atMost(Duration.ofSeconds(3)).until(()->orbit.value.get(retry.id(),"owner").status().equals("DONE"));assertEquals(2,calls.get());assertEquals("CANCELLED",orbit.value.get(first.id(),"owner").status());
            }finally{release.countDown();}
        }
    }
    @Test void missingConfigurationNeverCallsProvider()throws Exception{
        try(var source=new CloseTryon(new TryOnService(new CoreService(new TraceBus()),(p,s,b)->b.clone()))){var t=sourceTask(source.value,portrait(source.value));
            try(var orbit=new CloseOrbit(new OrbitService(source.value,new Tencent3dProvider(false,"","","ap-guangzhou","3.0",900)))){
                assertFalse(orbit.value.status().ready());assertEquals(503,assertThrows(AuthService.Failure.class,()->orbit.value.generate(t.id(),"owner",new OrbitService.Generate(true,null))).status());assertNull(orbit.value.latest(t.id(),"owner"));
            }
        }
    }
    @Test void invalidGlbAndExternalAssetsAreRejected()throws Exception{
        GlbValidator.validate(glb(""));assertThrows(IllegalStateException.class,()->GlbValidator.validate("not a model".getBytes()));
        byte[] external=glb(",\"images\":[{\"uri\":\"https://private.invalid/portrait\"}]");assertThrows(IllegalStateException.class,()->GlbValidator.validate(external));
        byte[] truncated=glb("");truncated[8]=0;assertThrows(IllegalStateException.class,()->GlbValidator.validate(truncated));
    }
    @Test void tencentSignatureMatchesIndependentNodeCryptoVector(){
        var headers=TencentV3Signer.sign(URI.create("https://ai3d.tencentcloudapi.com/"),"QueryHunyuanTo3DProJob","{\"JobId\":\"unit-test\"}".getBytes(StandardCharsets.UTF_8),"dummy-id","dummy-key","ap-guangzhou",Instant.ofEpochSecond(1790985600));
        assertEquals("TC3-HMAC-SHA256 Credential=dummy-id/2026-10-03/ai3d/tc3_request, SignedHeaders=content-type;host;x-tc-action, Signature=79709e75a93d8688ec9b9ea076ffdf7e98fa326292de32c1aa50a6ba6652dae8",headers.get("Authorization"));
        assertEquals("2025-05-13",headers.get("X-TC-Version"));assertFalse(headers.containsKey("Host"));
    }
    @Test void providerSubmitsOncePollsSameJobAndDownloadsWithoutCredentials()throws Exception{
        try(var server=new ProtocolServer()){
            var stages=new ArrayList<String>();byte[] result=server.provider().generate(image(),stages::add);assertArrayEquals(glb(""),result);
            assertEquals(1,server.submits.get());assertEquals(3,server.polls.get());assertEquals(1,server.downloads.get());assertTrue(stages.containsAll(List.of("SUBMITTING","REMOTE_QUEUED","MODELING","DOWNLOADING")));
            var submitted=server.body.get();assertTrue(submitted.has("ImageBase64"));assertFalse(submitted.has("ImageUrl"));assertFalse(submitted.has("ResultFormat"));assertEquals("Normal",submitted.path("GenerateType").asText());assertTrue(submitted.path("EnablePBR").asBoolean());assertTrue(server.signed.get());assertFalse(server.downloadSigned.get());
        }
    }
    @Test void providerFailureIsSanitizedAndDoesNotResubmit()throws Exception{
        try(var server=new ProtocolServer()){server.fail=true;var e=assertThrows(IllegalStateException.class,()->server.provider().generate(image(),s->{}));assertTrue(e.getMessage().contains("鉴权"));assertFalse(e.getMessage().contains("private-marker"));assertEquals(1,server.submits.get());assertEquals(0,server.polls.get());}
    }
    @Test void providerRejectsNonTencentDownloadAndBrokenModel()throws Exception{
        try(var server=new ProtocolServer()){server.url="http://127.0.0.1:1/private";assertThrows(IllegalStateException.class,()->server.provider().generate(image(),s->{}));assertEquals(0,server.downloads.get());}
        try(var server=new ProtocolServer()){server.broken=true;assertThrows(IllegalStateException.class,()->server.provider().generate(image(),s->{}));assertEquals(1,server.downloads.get());}
    }
    static final class CloseTryon implements AutoCloseable{final TryOnService value;CloseTryon(TryOnService value){this.value=value;}public void close(){value.close();}}
    static final class CloseOrbit implements AutoCloseable{final OrbitService value;CloseOrbit(OrbitService value){this.value=value;}public void close(){value.close();}}
    static final class ProtocolServer implements AutoCloseable{
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final AtomicInteger submits=new AtomicInteger(),polls=new AtomicInteger(),downloads=new AtomicInteger();final AtomicBoolean signed=new AtomicBoolean(),downloadSigned=new AtomicBoolean();final AtomicReference<JsonNode> body=new AtomicReference<>();boolean fail,broken;String url;
        ProtocolServer()throws Exception{server.createContext("/",exchange->{try{
            byte[] reply;
            if(exchange.getRequestURI().getPath().equals("/model")){downloads.incrementAndGet();downloadSigned.set(exchange.getRequestHeaders().containsKey("Authorization"));reply=broken?"error".getBytes():glb("");}
            else{String action=exchange.getRequestHeaders().getFirst("X-TC-Action");signed.set(exchange.getRequestHeaders().getFirst("Authorization").startsWith("TC3-HMAC-SHA256 Credential=dummy-id/"));var input=JSON.readTree(exchange.getRequestBody());
                if("SubmitHunyuanTo3DProJob".equals(action)){submits.incrementAndGet();body.set(input);reply=JSON.writeValueAsBytes(Map.of("Response",fail?Map.of("Error",Map.of("Code","AuthFailure.SignatureFailure","Message","private-marker")):Map.of("JobId","job-test")));}
                else{assertEquals("job-test",input.path("JobId").asText());int count=polls.incrementAndGet();reply=JSON.writeValueAsBytes(Map.of("Response",count<3?Map.of("Status",count==1?"WAIT":"RUN"):Map.of("Status","DONE","ResultFile3Ds",List.of(Map.of("Type","GLB","Url",url==null?base()+"model":url)))));}
            }
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
        }catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}});server.start();}
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/";}
        Tencent3dProvider provider(){return new Tencent3dProvider(true,"dummy-id","dummy-key","ap-guangzhou","3.0",URI.create(base()),Duration.ofSeconds(5),Duration.ofMillis(5),Clock.systemUTC());}
        public void close(){server.stop(0);}
    }
}
