package com.hanyunjing;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountSecurityTests {
    @TempDir Path temporary;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    AccountStore store;
    AuthService auth;
    CoreService core;
    TryOnService tryon;
    OrbitService orbit;
    TraceBus bus;
    MockMvc mvc;
    final MutableClock clock=new MutableClock();
    record Client(Cookie cookie,String csrf,String id) {}
    @BeforeEach void setup(){
        store=new AccountStore(json,temporary.resolve("account-store.json"));
        auth=new AuthService(store,clock);bus=new TraceBus();core=new CoreService(bus,store);
        tryon=new TryOnService(core,(p,s,photo)->photo.clone());
        orbit=new OrbitService(tryon,new OrbitService.Provider(){public boolean available(){return true;}public byte[] generate(byte[] image,java.util.function.Consumer<String> progress)throws Exception{return OrbitTests.glb("");}});
        mvc=MockMvcBuilders.standaloneSetup(new ApiController(core,tryon,new OnlineAgent(core,false,"","",""),bus),new AuthController(auth),new OrbitController(orbit))
            .setControllerAdvice(new ApiErrors()).addFilters(new AccountSecurityFilter(auth,json,"http://127.0.0.1:5173,http://localhost:5173")).build();
    }
    @AfterEach void close(){orbit.close();tryon.close();}
    @Test void orbitHttpRequiresAuthConsentCsrfAndOwnsModel()throws Exception{
        mvc.perform(get("/api/tryon/orbit/status")).andExpect(status().isUnauthorized());
        Client one=register("orbit_owner"),two=register("orbit_other");
        String scoped=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((one.id()+":shared").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var portrait=tryon.upload(scoped,new MockMultipartFile("file","photo.png","image/png",OrbitTests.image()),true,true);
        var source=tryon.generate(new Models.Generate(scoped,portrait.id(),"p14","p14-M"));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->tryon.get(source.id(),scoped).status().equals("DONE"));
        String path="/api/tryon/tasks/"+source.id()+"/orbit";
        mvc.perform(post(path).cookie(one.cookie()).param("sessionId","shared").contentType("application/json").content("{\"authorized\":true}")).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(one.cookie()).header("X-CSRF-Token",one.csrf()).param("sessionId","shared").contentType("application/json").content("{\"authorized\":false}")).andExpect(status().isBadRequest());
        var response=mvc.perform(post(path).cookie(one.cookie()).header("X-CSRF-Token",one.csrf()).param("sessionId","shared").contentType("application/json").content("{\"authorized\":true}")).andExpect(status().isOk()).andReturn().getResponse();
        String id=json.readTree(response.getContentAsByteArray()).path("data").path("id").asText();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->orbit.get(id,scoped).status().equals("DONE"));
        String model="/api/tryon/orbit/tasks/"+id+"/model";
        mvc.perform(get(model).cookie(two.cookie()).param("sessionId","shared")).andExpect(status().isNotFound());
        mvc.perform(get(model).cookie(one.cookie()).param("sessionId","shared")).andExpect(status().isOk()).andExpect(content().contentType("model/gltf-binary")).andExpect(header().string("Cache-Control","no-store"));
        tryon.deletePortrait(portrait.id(),scoped);
        mvc.perform(get(model).cookie(one.cookie()).param("sessionId","shared")).andExpect(status().isNotFound());
    }
    Client client(MockHttpServletResponse response)throws Exception{
        String token=response.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1];
        JsonNode data=json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        return new Client(new Cookie(AuthService.COOKIE,token),data.path("csrfToken").asText(),data.path("user").path("id").asText());
    }
    Client anonymous()throws Exception{return client(mvc.perform(get("/api/auth/session")).andExpect(status().isOk()).andReturn().getResponse());}
    Client register(String username)throws Exception{
        Client a=anonymous();var result=mvc.perform(post("/api/auth/register").cookie(a.cookie()).header("X-CSRF-Token",a.csrf()).contentType("application/json")
            .content(json.writeValueAsBytes(Map.of("username",username,"password","test-password","displayName","测试昵称"))))
            .andReturn();
        assertEquals(200,result.getResponse().getStatus(),()->String.valueOf(result.getResolvedException()==null?null:result.getResolvedException().getCause()));
        assertTrue(json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("authenticated").asBoolean());
        return client(result.getResponse());
    }
    void cart(Client c,String product,int quantity)throws Exception{
        mvc.perform(post("/api/cart").cookie(c.cookie()).header("X-CSRF-Token",c.csrf()).param("sessionId","shared")
            .contentType("application/json").content(json.writeValueAsBytes(new Models.CartAdd(product,product+"-M",quantity))))
            .andExpect(status().isOk());
    }
    Models.Checkout checkout(String key){return new Models.Checkout("测试收货人","13800138000","陕西省 西安市 雁塔区","测试街道10号",key);}
    MvcResult order(Client c,Models.Checkout in)throws Exception{
        return mvc.perform(post("/api/orders").cookie(c.cookie()).header("X-CSRF-Token",c.csrf()).param("sessionId","shared")
            .contentType("application/json").content(json.writeValueAsBytes(in))).andExpect(status().isOk()).andReturn();
    }
    @Test void publicCatalogueAndStatusesRemainReadableButPrivateEndpointsRequireLogin()throws Exception{
        for(String path:List.of("/api/products","/api/knowledge/dynasties","/api/agent/status","/api/tryon/status"))
            mvc.perform(get(path).param("sessionId","anonymous")).andExpect(status().isOk());
        for(String path:List.of("/api/cart","/api/orders","/api/orders/unknown","/api/user/body-profile","/api/trace/events","/api/trace/stream","/api/tryon/tasks/id","/api/tryon/result/id","/api/tryon/portrait/id/image"))
            mvc.perform(get(path).param("sessionId","shared")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        for(String path:List.of("/api/agent/chat","/api/agent/langchain/chat","/api/tryon/generate","/api/tryon/portrait","/api/orders","/api/knowledge/add"))
            mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
    @Test void cookieRotationCsrfOriginAndLogoutAreEnforced()throws Exception{
        Client a=anonymous();
        mvc.perform(post("/api/auth/register").cookie(a.cookie()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        Client user=register("secure_user");
        mvc.perform(post("/api/cart").cookie(user.cookie()).param("sessionId","shared").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/cart").cookie(user.cookie()).header("X-CSRF-Token",user.csrf()).header("Origin","https://untrusted.example")
            .param("sessionId","shared").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        var loggedOut=mvc.perform(post("/api/auth/logout").cookie(user.cookie()).header("X-CSRF-Token",user.csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.authenticated").value(false)).andReturn().getResponse();
        assertTrue(loggedOut.getHeader("Set-Cookie").contains("HttpOnly"));
        assertTrue(loggedOut.getHeader("Set-Cookie").contains("SameSite=Lax"));
        assertNotEquals(user.cookie().getValue(),client(loggedOut).cookie().getValue());
        mvc.perform(get("/api/orders").cookie(user.cookie()).param("sessionId","shared")).andExpect(status().isUnauthorized());
    }
    @Test void registrationHashesPasswordsAndLoginRotatesAnonymousSession()throws Exception{
        register("new_account");
        String disk=Files.readString(temporary.resolve("account-store.json"));
        assertFalse(disk.contains("test-password"));assertTrue(disk.contains("600000"));
        assertEquals(16,Base64.getDecoder().decode(store.account("new_account").salt()).length);
        Client anon=anonymous();
        mvc.perform(post("/api/auth/login").cookie(anon.cookie()).header("X-CSRF-Token",anon.csrf()).contentType("application/json")
            .content("{\"username\":\"new_account\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("用户名或密码不正确"));
        var response=mvc.perform(post("/api/auth/login").cookie(anon.cookie()).header("X-CSRF-Token",anon.csrf()).contentType("application/json")
            .content("{\"username\":\"NEW_ACCOUNT\",\"password\":\"test-password\"}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertNotEquals(anon.cookie().getValue(),client(response).cookie().getValue());
        assertNotEquals(anon.csrf(),client(response).csrf());
        assertFalse(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("passwordHash"));
    }
    @Test void expiredCookieCannotAccessPrivateDataAndSessionRefreshIsAnonymous()throws Exception{
        Client user=register("expiry_user");clock.advance(Duration.ofHours(9));
        mvc.perform(get("/api/orders").cookie(user.cookie()).param("sessionId","shared")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/session").cookie(user.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.authenticated").value(false));
    }
    @Test void accountOwnsCartAndProfileAcrossClientSessionsButCannotReadAnotherAccount()throws Exception{
        Client alice=register("alice_user"),bob=register("bob_user");cart(alice,"p1",2);
        mvc.perform(get("/api/cart").cookie(alice.cookie()).param("sessionId","another-device")).andExpect(jsonPath("$.data.quantity").value(2));
        mvc.perform(get("/api/cart").cookie(bob.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data.quantity").value(0));
        mvc.perform(put("/api/user/body-profile").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","shared")
            .contentType("application/json").content("{\"height\":165,\"loose\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/user/body-profile").cookie(alice.cookie()).param("sessionId","second")).andExpect(jsonPath("$.data.height").value(165));
        mvc.perform(get("/api/user/body-profile").cookie(bob.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void orderUsesServerPricesPersistsAcrossRestartAndIsAccountPrivate()throws Exception{
        Client alice=register("orders_alice"),bob=register("orders_bob");cart(alice,"p1",2);
        var response=order(alice,checkout("order-key-001"));
        JsonNode saved=json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertEquals("PENDING_PAYMENT",saved.path("status").asText());assertTrue(saved.path("demo").asBoolean());
        assertEquals(398,saved.path("cart").path("total").asInt());assertEquals("测试收货人",saved.path("recipient").asText());
        assertTrue(saved.path("orderNumber").asText().startsWith("HYJ"));
        String id=saved.path("id").asText();
        mvc.perform(get("/api/orders").cookie(alice.cookie()).param("sessionId","new-session")).andExpect(jsonPath("$.data[0].id").value(id));
        mvc.perform(get("/api/orders/"+id).cookie(bob.cookie()).param("sessionId","shared")).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").cookie(bob.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data").isEmpty());
        var restored=new AccountStore(json,temporary.resolve("account-store.json"));var restoredCore=new CoreService(new TraceBus(),restored);
        assertEquals(id,restoredCore.orders(alice.id()).get(0).id());
        assertEquals(10,restoredCore.product("p1").skus().stream().filter(s->s.id().equals("p1-M")).findFirst().orElseThrow().stock());
        assertNotNull(restored.account("orders_alice"));
        assertTrue(core.cart(alice.id()).lines().isEmpty());
        assertFalse(bus.events(null,0).toString().contains("13800138000"));assertFalse(bus.events(null,0).toString().contains("测试街道"));
    }
    @Test void paymentEndpointsRequireOwnershipCsrfAndSupportedMethod()throws Exception{
        Client alice=register("pay_alice"),bob=register("pay_bob");cart(alice,"p1",1);
        String id=json.readTree(order(alice,checkout("payment-api-key")).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("id").asText();
        String path="/api/orders/"+id;
        mvc.perform(post(path+"/pay").param("sessionId","shared").contentType("application/json").content("{\"paymentMethod\":\"ALIPAY\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(path+"/pay").cookie(alice.cookie()).param("sessionId","shared").contentType("application/json").content("{\"paymentMethod\":\"ALIPAY\"}")).andExpect(status().isForbidden());
        mvc.perform(post(path+"/pay").cookie(bob.cookie()).header("X-CSRF-Token",bob.csrf()).param("sessionId","shared").contentType("application/json").content("{\"paymentMethod\":\"ALIPAY\"}")).andExpect(status().isNotFound());
        mvc.perform(post(path+"/cancel").cookie(bob.cookie()).header("X-CSRF-Token",bob.csrf()).param("sessionId","shared")).andExpect(status().isNotFound());
        mvc.perform(post(path+"/pay").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","shared").contentType("application/json").content("{\"paymentMethod\":\"REAL_BANK\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(path+"/pay").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","different-device").contentType("application/json").content("{\"paymentMethod\":\"WECHAT\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DEMO_PAID")).andExpect(jsonPath("$.data.paymentMethod").value("WECHAT")).andExpect(jsonPath("$.data.paidAt").isNotEmpty());
    }
    @Test void duplicateConcurrentCheckoutCreatesExactlyOneOrderAndSingleStockDeduction()throws Exception{
        core.addCart("account",new Models.CartAdd("p2","p2-M",2));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            var futures=executor.invokeAll(List.of(()->core.order("account","first",checkout("same-checkout-key")),()->core.order("account","second",checkout("same-checkout-key"))));
            assertEquals(futures.get(0).get(),futures.get(1).get());assertEquals(1,core.orders("account").size());
            assertEquals(10,core.product("p2").skus().stream().filter(s->s.id().equals("p2-M")).findFirst().orElseThrow().stock());
        }finally{executor.shutdownNow();}
        assertThrows(IllegalStateException.class,()->core.order("account","first",new Models.Checkout("别的收货人","13800138000","西安","测试街道10号","same-checkout-key")));
    }
    @Test void failingDiskWriteKeepsCartStockAndOrderStateUnchanged()throws Exception{
        Path blocked=temporary.resolve("blocked");Files.writeString(blocked,"not-a-directory");
        var broken=new CoreService(new TraceBus(),new AccountStore(json,blocked.resolve("orders.json")));
        broken.addCart("owner",new Models.CartAdd("p3","p3-M",1));
        assertThrows(AuthService.Failure.class,()->broken.order("owner","test",checkout("fail-write-key")));
        assertEquals(1,broken.cart("owner").quantity());assertTrue(broken.orders("owner").isEmpty());
        assertEquals(12,broken.product("p3").skus().stream().filter(s->s.id().equals("p3-M")).findFirst().orElseThrow().stock());
    }
    @Test void tryonRequiresAccountAndSameClientSessionAndKeepsExternalUrls()throws Exception{
        Client alice=register("portrait_alice"),bob=register("portrait_bob");
        var image=new java.awt.image.BufferedImage(480,640,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        var response=mvc.perform(multipart("/api/tryon/portrait").file(new MockMultipartFile("file","photo.png","image/png",bytes.toByteArray()))
            .cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","shared").param("authorized","true").param("aiAuthorized","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value("shared")).andReturn().getResponse();
        String portraitId=json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("id").asText();
        String path="/api/tryon/portrait/"+portraitId+"/image";
        mvc.perform(get(path).cookie(bob.cookie()).param("sessionId","shared")).andExpect(status().isNotFound());
        mvc.perform(get(path).cookie(alice.cookie()).param("sessionId","different")).andExpect(status().isNotFound());
        mvc.perform(get(path).cookie(alice.cookie()).param("sessionId","shared")).andExpect(status().isOk());
        var task=mvc.perform(post("/api/tryon/generate").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).contentType("application/json")
            .content(json.writeValueAsBytes(new Models.Generate("shared",portraitId,"p1","p1-M"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value("shared"))
            .andExpect(jsonPath("$.data.originalUrl").value(path+"?sessionId=shared")).andReturn().getResponse();
        String taskId=json.readTree(task.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("id").asText();
        for(int i=0;i<100;i++){
            var current=mvc.perform(get("/api/tryon/tasks/"+taskId).cookie(alice.cookie()).param("sessionId","shared")).andExpect(status().isOk()).andReturn().getResponse();
            JsonNode data=json.readTree(current.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
            if(data.path("status").asText().equals("DONE")){assertEquals("/api/tryon/result/"+taskId+"?sessionId=shared",data.path("resultUrl").asText());break;}
            if(i==99)fail("test renderer did not complete");Thread.sleep(10);
        }
        mvc.perform(get("/api/tryon/result/"+taskId).cookie(bob.cookie()).param("sessionId","shared")).andExpect(status().isNotFound());
        mvc.perform(get("/api/tryon/result/"+taskId).cookie(alice.cookie()).param("sessionId","shared")).andExpect(status().isOk());
    }
    @Test void traceNeverAllowsGlobalOrOtherAccountsEventsAndRetainsClientSession()throws Exception{
        Client alice=register("trace_alice"),bob=register("trace_bob");
        mvc.perform(post("/api/ops/handoff").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","shared")
            .contentType("application/json").content("{\"reason\":\"test\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value("shared"));
        mvc.perform(get("/api/trace/events").cookie(alice.cookie())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/trace/events").cookie(alice.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].sessionId").value("shared"));
        mvc.perform(get("/api/trace/events").cookie(bob.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/trace/events").cookie(alice.cookie()).param("sessionId","other")).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/ops/summary").cookie(alice.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data.events").value(1));
        mvc.perform(get("/api/ops/summary").cookie(bob.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data.events").value(0));
    }
    List<String> traceTypes(Client client,String sessionId)throws Exception{
        var response=mvc.perform(get("/api/trace/events").cookie(client.cookie()).param("sessionId",sessionId))
            .andExpect(status().isOk()).andReturn().getResponse();
        var events=json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        var types=new ArrayList<String>();
        for(var event:events){assertEquals(sessionId,event.path("sessionId").asText());types.add(event.path("type").asText());}
        return types;
    }
    @Test void cartAndProfileChangesTraceTheActiveClientSessionWithoutChangingAccountOwnership()throws Exception{
        Client alice=register("scope_cart_alice"),bob=register("scope_cart_bob");
        cart(alice,"p1",1);
        String lineId=core.cart(alice.id()).lines().get(0).id();
        mvc.perform(put("/api/cart/"+lineId).cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","second")
            .contentType("application/json").content("{\"quantity\":2}")).andExpect(status().isOk());
        mvc.perform(get("/api/cart").cookie(alice.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data.quantity").value(2));
        mvc.perform(put("/api/cart/"+lineId).cookie(bob.cookie()).header("X-CSRF-Token",bob.csrf()).param("sessionId","second")
            .contentType("application/json").content("{\"quantity\":3}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/user/body-profile").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","second")
            .contentType("application/json").content("{\"height\":165,\"loose\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/user/body-profile").cookie(alice.cookie()).param("sessionId","shared")).andExpect(jsonPath("$.data.height").value(165));
        mvc.perform(get("/api/user/body-profile").cookie(bob.cookie()).param("sessionId","second")).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(delete("/api/user/body-profile").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","second"))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/cart/"+lineId).cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","second"))
            .andExpect(status().isOk());
        // Repeating an already completed removal must not invent another state-change event.
        mvc.perform(delete("/api/cart/"+lineId).cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","second"))
            .andExpect(status().isOk());
        assertEquals(List.of("CART_CHANGED"),traceTypes(alice,"shared"));
        assertEquals(List.of("CART_CHANGED","BODY_UPDATED","BODY_DELETED","CART_CHANGED"),traceTypes(alice,"second"));
        assertTrue(traceTypes(alice,"unrelated").isEmpty());assertTrue(traceTypes(bob,"second").isEmpty());
        assertTrue(core.cart(alice.id()).lines().isEmpty());assertNull(core.body(alice.id()));
        assertTrue(bus.events(alice.id(),0).isEmpty(),"account state must not send trace events to an unreadable account-only channel");
        assertTrue(bus.events(null,0).stream().noneMatch(event->event.summary().contains("165")));
    }
    @Test void orderTransitionsAppearOnlyInTheSessionThatPerformedEachSuccessfulAction()throws Exception{
        Client alice=register("scope_order_alice"),bob=register("scope_order_bob");cart(alice,"p1",1);
        String paidId=json.readTree(order(alice,checkout("scope-paid-order")).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("id").asText();
        // Reusing the checkout key is a read of the existing order, not another creation.
        order(alice,checkout("scope-paid-order"));
        for(int attempt=0;attempt<2;attempt++)mvc.perform(post("/api/orders/"+paidId+"/pay").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","payment-device")
            .contentType("application/json").content("{\"paymentMethod\":\"WECHAT\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/orders/"+paidId+"/cancel").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","payment-device"))
            .andExpect(status().isConflict());
        cart(alice,"p2",1);
        String cancelledId=json.readTree(order(alice,checkout("scope-cancel-order")).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("id").asText();
        mvc.perform(post("/api/orders/"+cancelledId+"/cancel").cookie(bob.cookie()).header("X-CSRF-Token",bob.csrf()).param("sessionId","cancel-device"))
            .andExpect(status().isNotFound());
        for(int attempt=0;attempt<2;attempt++)mvc.perform(post("/api/orders/"+cancelledId+"/cancel").cookie(alice.cookie()).header("X-CSRF-Token",alice.csrf()).param("sessionId","cancel-device"))
            .andExpect(status().isOk());
        assertEquals(List.of("CART_CHANGED","ORDER_CREATED","CART_CHANGED","ORDER_CREATED"),traceTypes(alice,"shared"));
        assertEquals(List.of("ORDER_DEMO_PAID"),traceTypes(alice,"payment-device"));
        assertEquals(List.of("ORDER_CANCELLED"),traceTypes(alice,"cancel-device"));
        assertTrue(traceTypes(bob,"shared").isEmpty());assertTrue(traceTypes(bob,"cancel-device").isEmpty());
        assertTrue(bus.events(alice.id(),0).isEmpty());
        mvc.perform(get("/api/orders/"+paidId).cookie(alice.cookie()).param("sessionId","another-device"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DEMO_PAID"));
        mvc.perform(get("/api/orders/"+cancelledId).cookie(alice.cookie()).param("sessionId","another-device"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertFalse(bus.events(null,0).toString().contains("13800138000"));assertFalse(bus.events(null,0).toString().contains("测试街道"));
    }
    static final class MutableClock extends Clock{
        private Instant now=Instant.parse("2026-10-03T00:00:00Z");
        void advance(Duration d){now=now.plus(d);}
        @Override public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(java.time.ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
