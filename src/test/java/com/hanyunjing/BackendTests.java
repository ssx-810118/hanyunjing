package com.hanyunjing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.llm.enabled=false", "app.llm.api-key=", "app.tryon.enabled=false"})
@AutoConfigureMockMvc
class BackendTests {
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedStore(org.springframework.test.context.DynamicPropertyRegistry properties) {
        String path=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"backend-accounts-"+UUID.randomUUID()+".json").toString();
        properties.add("app.account.store",()->path);
        properties.add("app.support.store",()->path+"-support.json");
        properties.add("app.admin.setup-token-file",()->path+"-admin-token.txt");
        properties.add("spring.datasource.url",()->"jdbc:h2:mem:backend-tests;MODE=MySQL;DB_CLOSE_DELAY=-1");
        properties.add("spring.datasource.username",()->"sa");
        properties.add("spring.datasource.password",()->"");
    }
    @Autowired CoreService core;
    @Autowired TryOnService tryon;
    @Autowired MockMvc mvc;
    @Autowired TraceBus bus;
    @Autowired OnlineAgent online;
    @Autowired AccountStore accounts;
    @Test void goldenAndReplan(){
        var first=core.chat(new Models.Chat("gold","我第一次穿去城墙，身高168cm，体重120斤，唐制"));
        assertEquals("DONE",first.status());assertFalse(first.recommendations().isEmpty());assertTrue(first.recommendations().size()<=3);
        assertEquals(60.0,core.body("gold").weightKg());assertEquals(first.recommendations().size(),first.funnel().returned());
        var next=core.chat(new Models.Chat("gold","颜色太亮，显瘦，换宋制"));assertEquals("wall-dark",next.slots().scene());assertEquals("宋",next.slots().dynasty());assertFalse(next.recommendations().isEmpty());
        assertTrue(next.recommendations().stream().allMatch(r->r.product().dynasty().equals("宋")));
        assertFalse(core.traces("gold").toString().contains("168"));
    }
    @Test void missingCollectedOnce(){var r=core.chat(new Models.Chat("missing","想去芙蓉园"));assertEquals("NEED_SLOT",r.status());assertTrue(r.missingFields().containsAll(List.of("firstWear","height","weightKg")));}
    @Test void firstWearSynonymsAreParsedConsistently(){
        assertTrue(core.chat(new Models.Chat("first-positive","去芙蓉园，汉制，初次穿")).slots().firstWear());
        assertFalse(core.chat(new Models.Chat("first-negative","去芙蓉园，汉制，不是初次穿")).slots().firstWear());
    }
    @Test void gapAndSupplement(){
        assertTrue(core.searchKnowledge("冷门纹样测试").abstained());
        core.add(new Models.KnowledgeAdd("纹样说明","测试","测试资料",Models.Kind.COMMON,"本地运营资料",List.of("冷门纹样测试"),"pattern-test","A"));
        assertFalse(core.searchKnowledge("冷门纹样测试").abstained());
        core.add(new Models.KnowledgeAdd("冲突说明","测试","不同结论",Models.Kind.COMMON,"本地运营资料",List.of("冷门纹样测试"),"pattern-test","B"));
        assertTrue(core.searchKnowledge("冷门纹样测试").abstained());assertTrue(core.searchKnowledge("正式婚礼").humanRequired());
    }
    @Test void sizeBoundaries(){
        var p=core.product("p1");assertEquals("低",core.advice(p,new Models.Body(165.,55.,null,null,null,false)).confidence());
        assertEquals("M",core.advice(p,new Models.Body(163.,55.,86.,72.,93.,false)).size());
        assertEquals("L",core.advice(p,new Models.Body(163.,55.,86.,72.,93.,true)).size());
        assertFalse(core.advice(p,new Models.Body(210.,100.,130.,120.,140.,false)).inRange());
    }
    @Test void cartStockMoneyAndIsolation(){
        for(String id:List.of("buy","other")) accounts.addAccount(new AccountStore.Account(id,id,id,"salt","hash",1,java.time.Instant.now()));
        var c=core.addCart("buy",new Models.CartAdd("p1","p1-M",2));assertEquals(0,new BigDecimal("398").compareTo(c.total()));assertTrue(core.cart("other").lines().isEmpty());
        assertThrows(NoSuchElementException.class,()->core.updateCart("other",c.lines().get(0).id(),2));
        assertThrows(IllegalStateException.class,()->core.addCart("buy",new Models.CartAdd("p1","p1-M",99)));
        var o=core.order("buy");assertEquals("PENDING_PAYMENT",o.status());assertTrue(o.demo());assertEquals(10,core.product("p1").skus().stream().filter(s->s.id().equals("p1-M")).findFirst().orElseThrow().stock());
        assertEquals("DEMO_PAID",core.payOrder("buy",o.id(),new Models.Payment("ALIPAY")).status());
    }
    @Test void privacyUploadCancelAndDelete()throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(480,640,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var image=new MockMultipartFile("file","test.png","image/png",bytes.toByteArray());
        var service=new TryOnService(core,(product,sku,portraitPng)->portraitPng.clone());
        try {
            assertThrows(IllegalArgumentException.class,()->service.upload("photo",image,false,true));
            assertThrows(IllegalArgumentException.class,()->service.upload("photo",image,true,false));
            assertThrows(IllegalArgumentException.class,()->service.upload("photo",new MockMultipartFile("file","fake.png","image/png","fake".getBytes()),true,true));
            var p=service.upload("photo",image,true,true);assertEquals(480,p.width());
            var task=service.generate(new Models.Generate("photo",p.id(),"p2","p2-M"));service.cancel(task.id(),"photo");
            assertEquals("CANCELLED",service.get(task.id(),"photo").status());assertThrows(IllegalStateException.class,()->service.result(task.id(),"photo"));
            assertThrows(NoSuchElementException.class,()->service.get(task.id(),"other"));service.deletePortrait(p.id(),"photo");assertThrows(NoSuchElementException.class,()->service.generate(new Models.Generate("photo",p.id(),"p2","p2-M")));
        } finally { service.close(); }
    }
    @Test void envelopeValidation()throws Exception{
        mvc.perform(get("/api/products").param("sessionId","http")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(25));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var anonymous=mvc.perform(get("/api/auth/session")).andReturn().getResponse();
        var cookie=new jakarta.servlet.http.Cookie(AuthService.COOKIE,anonymous.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1]);
        String csrf=json.readTree(anonymous.getContentAsString()).path("data").path("csrfToken").asText();
        var signed=mvc.perform(post("/api/auth/register").cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"username\":\"validation_user\",\"password\":\"test-password\"}")).andExpect(status().isOk()).andReturn().getResponse();
        cookie=new jakarta.servlet.http.Cookie(AuthService.COOKIE,signed.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1]);
        csrf=json.readTree(signed.getContentAsString()).path("data").path("csrfToken").asText();
        mvc.perform(post("/api/cart").param("sessionId","http").cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"productId\":\"p1\",\"skuId\":\"p1-M\",\"quantity\":0}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertFalse(online.available());
    }
    @Test void traceBoundedReplay(){var b=new TraceBus();for(int i=0;i<5010;i++)b.publish("trace","TEST","safe");assertEquals(5000,b.events(null,0).size());assertEquals(10,b.events("trace",5000).size());b.clear("trace");assertTrue(b.events(null,0).isEmpty());}
}
