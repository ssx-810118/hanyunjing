package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetailWorkflowTests {
    @org.junit.jupiter.api.io.TempDir Path temp;
    EmbeddedDatabase data;CommerceStore store;CoreService core;RetailWorkflow flow;
    @BeforeEach void setup(){
        data=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("db/schema.sql").build();
        store=new CommerceStore(new JdbcTemplate(data),new ObjectMapper().findAndRegisterModules(),new DataSourceTransactionManager(data));
        core=new CoreService(new TraceBus(),AccountStore.inMemory(),store);flow=new RetailWorkflow(store);
        for(String id:List.of("customer","other","admin"))store.insertAccount(new AccountStore.Account(id,id,id,"salt","hash",1,Instant.now()));
    }
    @AfterEach void close(){data.shutdown();}
    RetailRules.Requirements requirements(){return new RetailRules.Requirements(new BigDecimal("1000"),"M",null,2);}
    String finish(String state){
        var r=requirements();String id=flow.start("customer","scoped-session",r);var p=core.product("p8");
        var rec=new Models.Recommendation(p,core.advice(p,null),List.of(),List.of("数据库证据"),RetailRules.eligible(p,r,null),core.references(p.id()));
        var result=new Models.AgentReply("session",state,"测试接待",new Models.Slots(null,"明",null,null,false,false),List.of(),new Models.Funnel(1,1,1,1,1),List.of(rec),new Models.KnowledgeResult(false,"",false,List.of()),r,id);
        flow.finish(id,result,1234,1,1,30L,10L,List.of(new RetailWorkflow.Step("MODEL_TOOL","submitDecision",5)));return id;
    }
    @Test void confirmIsAccountScopedIdempotentAndRechecksPriceAndStock(){
        String id=finish("DONE");
        assertThrows(NoSuchElementException.class,()->flow.confirm(id,"other",new RetailWorkflow.Confirm("p8-M")));
        assertThrows(IllegalArgumentException.class,()->flow.confirm(id,"customer",new RetailWorkflow.Confirm("p8-S")));
        store.addCart("customer",new Models.CartAdd("p8","p8-M",1));
        assertEquals(3,flow.confirm(id,"customer",new RetailWorkflow.Confirm("p8-M")).quantity());
        assertEquals(3,flow.confirm(id,"customer",new RetailWorkflow.Confirm("p8-M")).quantity());
        assertEquals("CONFIRMED",flow.list("customer").get(0).get("status"));
        String next=finish("DONE");
        store.jdbc.update("UPDATE product_sku SET price=price+1 WHERE id='p8-M'");
        assertEquals(409,assertThrows(AuthService.Failure.class,()->flow.confirm(next,"customer",new RetailWorkflow.Confirm("p8-M"))).status());
        store.jdbc.update("UPDATE product_sku SET price=price-1,stock=1 WHERE id='p8-M'");
        assertThrows(AuthService.Failure.class,()->flow.confirm(next,"customer",new RetailWorkflow.Confirm("p8-M")));
        assertEquals(3,store.cart("customer").quantity());
    }
    @Test void casesAndEvidenceSurviveReconnectAndAreVisibleOnlyToOwner(){
        String id=finish("HANDOFF");assertEquals(1,flow.metrics().get("openCases"));
        assertTrue(flow.list("other").isEmpty());flow.requestHelp(id,"customer");
        assertEquals(1,store.jdbc.queryForObject("SELECT COUNT(*) FROM agent_case",Integer.class));
        flow.resolve(id,"admin",new RetailWorkflow.Resolve("已核对规格，可按展示尺码选择"));
        var reconnected=new RetailWorkflow(store);var row=reconnected.list("customer").get(0);
        assertFalse(((List<?>)row.get("evidence")).isEmpty());assertFalse(((List<?>)row.get("steps")).isEmpty());
        assertFalse(row.containsKey("session_key"));assertFalse(row.containsKey("account_id"));
        assertTrue(row.toString().contains("已核对规格"));assertEquals(0,reconnected.metrics().get("openCases"));
        assertThrows(AuthService.Failure.class,()->flow.resolve(id,"admin",new RetailWorkflow.Resolve("重复回复")));
    }
    @Test void rulesExcludeWrongSizeColorQuantityAndOverBudget(){
        var p=core.product("p8");
        assertEquals(1,RetailRules.eligible(p,requirements(),null).size());
        assertTrue(RetailRules.eligible(p,new RetailRules.Requirements(new BigDecimal("1"),"M",null,1),null).isEmpty());
        assertTrue(RetailRules.eligible(p,new RetailRules.Requirements(null,"M","不存在颜色",1),null).isEmpty());
        assertTrue(RetailRules.eligible(p,new RetailRules.Requirements(null,"M",null,99),null).isEmpty());
        var changed=RetailRules.resolve("预算500元，M码，买2件",null,null);
        assertEquals(new BigDecimal("500"),changed.budget());assertEquals("M",changed.size());assertEquals(2,changed.quantity());
        assertEquals(changed,RetailRules.resolve("保留其他要求",changed,null));
        assertNull(RetailRules.resolve("预算不限",changed,null).budget());
        assertThrows(IllegalArgumentException.class,()->RetailRules.resolve("预算三百元",null,null));
        assertThrows(IllegalArgumentException.class,()->RetailRules.resolve("预算200-300元",null,null));
        assertThrows(IllegalArgumentException.class,()->RetailRules.resolve("200至300元以内",null,null));
        assertNull(RetailRules.resolve("我还没确定预算，先看价格",changed,null).budget());
        assertThrows(IllegalArgumentException.class,()->RetailRules.resolve("衣裳和配饰合计预算600元",null,null));
    }
    @Test void interruptedRunsAreNotCountedAsSuccessfulAndHaveNoFakeCosts(){
        flow.start("customer","session",requirements());flow.recoverInterruptedRuns();
        assertEquals("INTERRUPTED",flow.list("customer").get(0).get("status"));assertNull(flow.metrics().get("cost"));
        assertNull(flow.metrics().get("p95Ms"));assertEquals(0L,((Number)flow.metrics().get("confirmed")).longValue());
    }
    @Test void actualAgentProtocolRunPersistsModelUsageAndCandidates()throws Exception{
        // Protocol stub verifies integration; it is never deployed or reported as a live AI benchmark.
        try(var stub=new OnlineAgentTests.Stub()){
            stub.directDecision=true;
            var agent=new OnlineAgent(core,true,"protocol-test-only",stub.base(),"test-model");
            ReflectionTestUtils.setField(agent,"workflow",flow);
            var reply=agent.chat(new Models.Chat("retail-http","我第一次穿，去芙蓉园，想要唐制，预算300元"),"customer");
            assertNotNull(reply.workflowId());assertFalse(reply.recommendations().isEmpty());
            var row=flow.list("customer").get(0);
            assertEquals(1,((Number)row.get("model_calls")).intValue());
            assertEquals(1L,((Number)row.get("input_tokens")).longValue());
            assertTrue(((List<?>)row.get("steps")).size()>3);
            assertFalse(((List<?>)row.get("candidates")).isEmpty());
            assertFalse(row.toString().contains("我第一次穿"));
            stub.httpStatus=500;
            assertThrows(OnlineAgent.Failure.class,()->agent.chat(new Models.Chat("failed-live","我第一次穿，去芙蓉园，想要唐制"),"customer"));
            assertEquals(1L,((Number)flow.metrics().get("failed")).longValue());
        }
    }
    @Test void evaluationDoesNotCountAsReceptionOrAllowCheckout(){
        String id=finish("DONE");
        store.jdbc.update("UPDATE agent_run SET origin='EVALUATION' WHERE id=?",id);
        assertTrue(flow.list("customer").isEmpty());
        assertEquals(0L,((Number)flow.metrics().get("total")).longValue());assertEquals(1,flow.metrics().get("evaluations"));
        assertThrows(AuthService.Failure.class,()->flow.confirm(id,"customer",new RetailWorkflow.Confirm("p8-M")));
        assertTrue(store.cart("customer").lines().isEmpty());
    }
    @Test void concurrentConfirmationAddsOnce()throws Exception{
        String id=finish("DONE");var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(1);
        try{
            Callable<Models.Cart> action=()->{ready.await();return flow.confirm(id,"customer",new RetailWorkflow.Confirm("p8-M"));};
            var a=pool.submit(action);var b=pool.submit(action);ready.countDown();
            assertEquals(2,a.get(5,TimeUnit.SECONDS).quantity());assertEquals(2,b.get(5,TimeUnit.SECONDS).quantity());
            assertEquals(1,store.jdbc.queryForObject("SELECT COUNT(*) FROM agent_selection",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void retailHttpEndpointsEnforceOwnershipAdminAndCsrf()throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();
        var accounts=new AccountStore(json,temp.resolve("accounts.json").toString(),store);
        var auth=new AuthService(accounts);var access=new AdminAccess(store,temp.resolve("token.txt").toString());
        var filter=new AccountSecurityFilter(auth,json,"http://localhost:5173");ReflectionTestUtils.setField(filter,"adminAccess",access);
        var agent=org.mockito.Mockito.mock(OnlineAgent.class);
        var mvc=MockMvcBuilders.standaloneSetup(new RetailController(flow,access,agent),new AuthController(auth)).setControllerAdvice(new ApiErrors()).addFilters(filter).build();
        mvc.perform(get("/api/retail/runs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/retail/runs")).andExpect(status().isUnauthorized());
        var req=new MockHttpServletRequest();var res=new MockHttpServletResponse();
        var snapshot=auth.register(new Models.Register("retailbuyer","retail-test-password","评测用户"),req,res);
        var session=(AuthService.Session)req.getAttribute(AuthService.REQUEST_SESSION);var cookie=new Cookie(AuthService.COOKIE,session.token());
        String id=finish("HANDOFF");
        mvc.perform(get("/api/retail/runs").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(post("/api/retail/runs/"+id+"/help").cookie(cookie).header("X-CSRF-Token",session.csrf())).andExpect(status().isNotFound());
        store.jdbc.update("UPDATE agent_run SET account_id=? WHERE id=?",snapshot.user().id(),id);
        mvc.perform(post("/api/retail/runs/"+id+"/confirm").cookie(cookie).contentType("application/json").content("{\"skuId\":\"p8-M\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/retail/runs").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].session_key").doesNotExist());
        mvc.perform(get("/api/admin/retail/runs").cookie(cookie).header("X-HYJ-Client","admin")).andExpect(status().isUnauthorized());
        var adminReq=new MockHttpServletRequest();adminReq.addHeader("X-HYJ-Client","admin");var adminRes=new MockHttpServletResponse();
        var adminSnapshot=auth.register(new Models.Register("retailstaff","retail-admin-password","店员"),adminReq,adminRes);
        var adminSession=(AuthService.Session)adminReq.getAttribute(AuthService.REQUEST_SESSION);var adminCookie=new Cookie(AuthService.ADMIN_COOKIE,adminSession.token());
        mvc.perform(get("/api/admin/retail/runs").cookie(adminCookie).header("X-HYJ-Client","admin")).andExpect(status().isForbidden());
        store.jdbc.update("UPDATE customer_account SET role='ADMIN' WHERE id=?",adminSnapshot.user().id());
        mvc.perform(put("/api/admin/retail/cases/"+id).cookie(adminCookie).header("X-HYJ-Client","admin").contentType("application/json").content("{\"reply\":\"已核对商品库存\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/retail/cases/"+id).cookie(adminCookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",adminSession.csrf()).contentType("application/json").content("{\"reply\":\"已核对商品库存\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/retail/runs").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].cases[0].reply").value("已核对商品库存"));
    }
}
