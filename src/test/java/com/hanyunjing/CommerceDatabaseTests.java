package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CommerceDatabaseTests {
    @TempDir Path temp;
    EmbeddedDatabase dataSource;
    CommerceStore db;
    AccountStore accounts;
    CoreService core;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void setup() {
        dataSource=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("db/schema.sql").build();
        db=new CommerceStore(new JdbcTemplate(dataSource),json,new DataSourceTransactionManager(dataSource));
        accounts=new AccountStore(json,temp.resolve("legacy.json").toString(),db);
        core=new CoreService(new TraceBus(),accounts,db);
        for(String id:List.of("buyer1","buyer2","admin")) db.insertAccount(new AccountStore.Account(id,id,id,"salt","hash",1,Instant.now()));
    }
    @AfterEach void close(){dataSource.shutdown();}
    Models.Checkout checkout(String key){return new Models.Checkout("测试收货人","13800138000","测试地区","测试街道",key);}
    Models.Product change(Models.Product p,BigDecimal price,int stock){return new Models.Product(p.id(),p.name(),p.category(),p.dynasty(),p.form(),p.description(),p.scenes(),p.tags(),p.colors(),p.images(),p.accessoryIds(),p.sizeChart(),p.skus().stream().map(s->new Models.Sku(s.id(),s.color(),s.size(),stock,price)).toList());}
    void set(String id,BigDecimal price,int stock){var p=db.record(id);db.saveProduct(new CommerceStore.ProductRecord(change(p.product(),price,stock),p.status(),p.revision(),p.articleIds()),false,"admin");}
    @Test void publishedCatalogueHasFourDocumentedGarmentsPerDynastyAndPersistentSkuPrices(){
        for(String dynasty:Dynasty.labels()) assertTrue(core.products(dynasty,null,null,null,null).size()>=4,dynasty);
        for(String id:List.of("p11","p12","p15","p16","p1","p2","p7","p10","p3","p18","p19","p20","p13","p21","p22","p23","p8","p24","p25","p26"))assertTrue(db.references(id).stream().anyMatch(a->a.kind()==Models.Kind.FACT&&a.source().contains("https://")),id);
        set("p8",new BigDecimal("321.09"),7);
        var reconnected=new CommerceStore(new JdbcTemplate(dataSource),json,new DataSourceTransactionManager(dataSource));
        assertEquals(new BigDecimal("321.09"),reconnected.product("p8",false).skus().get(0).price());
        new CoreService(new TraceBus(),accounts,reconnected);
        assertEquals(7,reconnected.product("p8",false).skus().get(0).stock());
    }
    @Test void checkoutUsesLatestPriceAndCancellationIsIdempotentAcrossReconnect(){
        db.addCart("buyer1",new Models.CartAdd("p8","p8-M",2));set("p8",new BigDecimal("300.00"),5);
        var o=db.checkout("buyer1","session",checkout("checkout001"));
        assertEquals(new BigDecimal("600.00"),o.cart().total());assertEquals(3,db.product("p8",false).skus().get(1).stock());
        assertEquals(o.id(),db.checkout("buyer1","session",checkout("checkout001")).id());
        assertTrue(db.cart("buyer1").lines().isEmpty());
        assertThrows(NoSuchElementException.class,()->db.transitionOrder("buyer2",o.id(),null,true));
        db.transitionOrder("buyer1",o.id(),null,true);db.transitionOrder("buyer1",o.id(),null,true);
        assertEquals(5,db.product("p8",false).skus().get(1).stock());
        var reloaded=new CoreService(new TraceBus(),accounts,db);
        assertEquals(5,reloaded.product("p8").skus().get(1).stock());
    }
    @Test void insufficientStockRollsBackEntireOrderAndPreservesCart(){
        db.addCart("buyer1",new Models.CartAdd("p1","p1-M",2));db.addCart("buyer1",new Models.CartAdd("p2","p2-M",2));
        int original=db.product("p1",false).skus().get(1).stock();set("p2",new BigDecimal("239.00"),1);
        assertThrows(IllegalStateException.class,()->db.checkout("buyer1","session",checkout("rollback001")));
        assertEquals(original,db.product("p1",false).skus().get(1).stock());assertEquals(2,db.cart("buyer1").lines().size());assertTrue(db.orders("buyer1").isEmpty());
    }
    @Test void twoConnectionsCannotOversellTheLastSku()throws Exception {
        set("p8",new BigDecimal("259.00"),1);
        db.addCart("buyer1",new Models.CartAdd("p8","p8-M",1));db.addCart("buyer2",new Models.CartAdd("p8","p8-M",1));
        var second=new CommerceStore(new JdbcTemplate(dataSource),json,new DataSourceTransactionManager(dataSource));
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            var a=pool.submit(()->{start.await();try{db.checkout("buyer1","session",checkout("concurrent1"));return true;}catch(IllegalStateException e){return false;}});
            var b=pool.submit(()->{start.await();try{second.checkout("buyer2","session",checkout("concurrent2"));return true;}catch(IllegalStateException e){return false;}});
            start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(0,db.product("p8",false).skus().get(1).stock());assertEquals(1,db.storedOrders().size());
        }finally{pool.shutdownNow();}
    }
    @Test void staleAdminUpdateCannotUndoCheckoutStockAndArchivedProductCannotBePurchased(){
        var before=db.record("p8");db.addCart("buyer1",new Models.CartAdd("p8","p8-M",1));db.checkout("buyer1","session",checkout("stale0001"));
        assertThrows(AuthService.Failure.class,()->db.saveProduct(before,false,"admin"));assertEquals(11,db.product("p8",false).skus().get(1).stock());
        var latest=db.record("p8");db.saveProduct(new CommerceStore.ProductRecord(latest.product(),"ARCHIVED",latest.revision(),latest.articleIds()),false,"admin");
        assertThrows(NoSuchElementException.class,()->db.addCart("buyer2",new Models.CartAdd("p8","p8-M",1)));
    }
    @Test void reviewsPublishImmediatelyAndEditsStayVisible(){
        db.review("p8","buyer1",new CommerceStore.ReviewInput(5,"衣裳款式很喜欢"));
        assertEquals(1,db.reviews("p8",null).size());assertEquals(1,db.reviews("p8","buyer2").size());
        var own=db.reviews("p8","buyer1").get(0);assertTrue(own.mine());assertFalse(own.purchased());
        assertFalse(db.reviews("p8",null).get(0).mine());
        assertEquals("APPROVED",db.jdbc.queryForObject("SELECT status FROM product_review WHERE id=?",String.class,own.id()));
        db.replyToReview(own.id(),new CommerceStore.ReviewReply("  谢谢你的留言  "),"admin");
        assertEquals("谢谢你的留言",db.reviews("p8",null).get(0).reply());
        db.review("p8","buyer1",new CommerceStore.ReviewInput(4,"修改后重新提交留言"));
        var published=db.reviews("p8",null);assertEquals(1,published.size());
        assertEquals(own.id(),published.get(0).id());assertEquals(4,published.get(0).rating());
        assertEquals("修改后重新提交留言",published.get(0).content());assertEquals("",published.get(0).reply());
        assertEquals(published,db.reviews("p8","buyer2"));
        var reconnected=new CommerceStore(new JdbcTemplate(dataSource),json,new DataSourceTransactionManager(dataSource));
        assertEquals(published,reconnected.reviews("p8",null));
    }
    @Test void legacyReviewFlagsCannotHideCommentsOrGateMerchantReplies(){
        db.review("p8","buyer1",new CommerceStore.ReviewInput(5,"原本等待审核的评论"));
        db.review("p8","buyer2",new CommerceStore.ReviewInput(3,"原本未通过的历史评论"));
        db.jdbc.update("UPDATE product_review SET status='PENDING',moderation_note='旧审核说明' WHERE account_id='buyer1'");
        db.jdbc.update("UPDATE product_review SET status='REJECTED' WHERE account_id='buyer2'");
        assertEquals(2,db.reviews("p8",null).size());
        assertEquals(2,db.dashboard().get("reviewCount"));assertFalse(db.dashboard().containsKey("pendingReviews"));
        var own=db.reviews("p8","buyer1").stream().filter(CommerceStore.Review::mine).findFirst().orElseThrow();
        var fields=json.valueToTree(own);assertFalse(fields.has("status"));assertFalse(fields.has("moderationNote"));
        assertThrows(IllegalArgumentException.class,()->db.replyToReview(own.id(),new CommerceStore.ReviewReply("   "),"admin"));
        assertThrows(IllegalArgumentException.class,()->db.replyToReview(own.id(),null,"admin"));
        assertThrows(NoSuchElementException.class,()->db.replyToReview("missing",new CommerceStore.ReviewReply("感谢反馈"),"admin"));
        db.replyToReview(own.id(),new CommerceStore.ReviewReply("感谢你的反馈"),"admin");
        var replied=db.reviews("p8",null).stream().filter(r->r.id().equals(own.id())).findFirst().orElseThrow();
        assertEquals(own.content(),replied.content());assertEquals(own.rating(),replied.rating());assertEquals("感谢你的反馈",replied.reply());
        assertEquals("PENDING",db.jdbc.queryForObject("SELECT status FROM product_review WHERE id=?",String.class,own.id()));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action='REVIEW_REPLY'",Integer.class));
        assertEquals(2,db.reviews("p8",null).size());
    }
    @Test void reviewHttpEndpointsPublishToAnonymousVisitorsImmediately()throws Exception {
        var auth=new AuthService(accounts);var access=new AdminAccess(db,temp.resolve("review-setup-token.txt").toString());
        var security=new AccountSecurityFilter(auth,json,"http://localhost:5173");ReflectionTestUtils.setField(security,"adminAccess",access);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new CommerceController(db,access,temp.resolve("media").toString()),new AuthController(auth)).setControllerAdvice(new ApiErrors()).addFilters(security).build();
        mvc.perform(post("/api/products/p8/reviews").contentType("application/json").content("{\"rating\":5,\"content\":\"匿名用户不可冒名提交\"}")).andExpect(status().isUnauthorized());
        var req=new MockHttpServletRequest();var res=new MockHttpServletResponse();auth.register(new Models.Register("reviewuser","test-review-password","衣友"),req,res);
        var session=(AuthService.Session)req.getAttribute(AuthService.REQUEST_SESSION);Cookie cookie=new Cookie(AuthService.COOKIE,session.token());
        mvc.perform(post("/api/products/p8/reviews").cookie(cookie).contentType("application/json").content("{\"rating\":5,\"content\":\"提交后大家立即可见\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/products/p8/reviews").cookie(cookie).header("X-CSRF-Token",session.csrf()).contentType("application/json").content("{\"rating\":5,\"content\":\"提交后大家立即可见\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/products/p8/reviews")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].content").value("提交后大家立即可见")).andExpect(jsonPath("$.data[0].reply").value("")).andExpect(jsonPath("$.data[0].status").doesNotExist()).andExpect(jsonPath("$.data[0].moderationNote").doesNotExist());
        mvc.perform(post("/api/products/p8/reviews").cookie(cookie).header("X-CSRF-Token",session.csrf()).contentType("application/json").content("{\"rating\":3,\"content\":\"修改后同样立即公开\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/products/p8/reviews")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].rating").value(3)).andExpect(jsonPath("$.data[0].content").value("修改后同样立即公开"));
    }
    @Test void deleteHidesProductsClearsCartsAndPreservesHistoricalOrdersAndConsultations(){
        db.addCart("buyer1",new Models.CartAdd("p8","p8-M",2));
        var placed=db.checkout("buyer1","session",checkout("delete-order"));
        var orderBefore=db.orders("buyer1").get(0);
        db.addCart("buyer2",new Models.CartAdd("p8","p8-S",1));
        db.jdbc.update("INSERT INTO product_accessory VALUES (?,?)","p25","p8");
        db.jdbc.update("INSERT INTO support_ticket(id,account_id,client_id,ticket_number,category,product_id,message,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)","keep-ticket","buyer1","keep-client","KF-KEEP","商品咨询","p8","这件衣裳的咨询记录","RECORDED",Instant.now().toString());
        db.review("p8","buyer1",new CommerceStore.ReviewInput(5,"保留这条历史评论"));
        var active=db.record("p8");
        db.saveProduct(new CommerceStore.ProductRecord(active.product(),"ARCHIVED",active.revision(),active.articleIds()),false,"admin");
        var before=db.record("p8");
        int productsBefore=(Integer)db.dashboard().get("products");
        long stockBefore=(Long)db.dashboard().get("stock");
        db.deleteProduct("p8",before.revision(),"admin");
        assertFalse(db.products(true).stream().anyMatch(p->p.id().equals("p8")));
        assertFalse(db.products(false).stream().anyMatch(p->p.id().equals("p8")));
        assertThrows(NoSuchElementException.class,()->db.product("p8",true));
        assertThrows(NoSuchElementException.class,()->db.record("p8"));
        assertThrows(NoSuchElementException.class,()->db.saveProduct(before,false,"admin"));
        assertThrows(NoSuchElementException.class,()->db.addCart("buyer2",new Models.CartAdd("p8","p8-S",1)));
        assertTrue(db.cart("buyer2").lines().isEmpty());
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM product_accessory WHERE accessory_id='p8' OR product_id='p8'",Integer.class));
        assertEquals(orderBefore,db.orders("buyer1").get(0));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE product_id='p8'",Integer.class));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM product_review WHERE product_id='p8'",Integer.class));
        assertEquals(productsBefore-1,db.dashboard().get("products"));
        assertEquals(stockBefore-before.product().skus().stream().mapToLong(Models.Sku::stock).sum(),db.dashboard().get("stock"));
        db.deleteProduct("p8",before.revision(),"admin");
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action='PRODUCT_DELETE' AND target_id='p8'",Integer.class));
        var reconnected=new CommerceStore(new JdbcTemplate(dataSource),json,new DataSourceTransactionManager(dataSource));
        var restarted=new CoreService(new TraceBus(),accounts,reconnected);
        assertThrows(NoSuchElementException.class,()->restarted.product("p8"));
        assertEquals("CANCELLED",reconnected.transitionOrder("buyer1",placed.id(),null,true).status());
    }
    @Test void staleDeleteCannotRemoveAChangedProductOrItsCart(){
        db.addCart("buyer1",new Models.CartAdd("p8","p8-M",1));
        var active=db.record("p8");
        db.saveProduct(new CommerceStore.ProductRecord(active.product(),"ARCHIVED",active.revision(),active.articleIds()),false,"admin");
        var before=db.record("p8");
        set("p8",new BigDecimal("310.00"),8);
        var error=assertThrows(AuthService.Failure.class,()->db.deleteProduct("p8",before.revision(),"admin"));
        assertEquals(409,error.status());
        assertTrue(error.getMessage().contains("商品或库存已变化"));
        assertEquals("ARCHIVED",db.record("p8").status());
        assertEquals(1,db.cart("buyer1").lines().size());
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action='PRODUCT_DELETE'",Integer.class));
    }
    @Test void activeProductCannotBeDeletedEvenWithCurrentRevision(){
        db.addCart("buyer1",new Models.CartAdd("p8","p8-M",1));
        var before=db.record("p8");
        var error=assertThrows(AuthService.Failure.class,()->db.deleteProduct("p8",before.revision(),"admin"));
        assertEquals(409,error.status());
        assertTrue(error.getMessage().contains("请先下架"));
        assertEquals(before,db.record("p8"));
        assertEquals(1,db.cart("buyer1").lines().size());
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action='PRODUCT_DELETE'",Integer.class));
    }
    @Test void draftAndArchivedProductsCanBeDeletedFromAllAdminLists(){
        var p=db.record("p1");
        db.saveProduct(new CommerceStore.ProductRecord(p.product(),"ARCHIVED",p.revision(),p.articleIds()),false,"admin");
        for(String id:List.of("p1","p17")){
            db.deleteProduct(id,db.record(id).revision(),"admin");
            assertFalse(db.products(true).stream().anyMatch(item->item.id().equals(id)));
        }
        assertThrows(NoSuchElementException.class,()->db.deleteProduct("missing",0,"admin"));
    }
    @Test void oldSchemaGainsDeletionColumnWithoutChangingExistingData(){
        var before=db.record("p8");
        db.jdbc.execute("ALTER TABLE product DROP COLUMN deleted_at");
        var upgraded=new CommerceStore(new JdbcTemplate(dataSource),json,new DataSourceTransactionManager(dataSource));
        assertEquals(before,upgraded.record("p8"));
        assertEquals(26,upgraded.products(true).size());
        assertEquals(0,upgraded.jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE deleted_at IS NOT NULL",Integer.class));
        new CommerceStore(new JdbcTemplate(dataSource),json,new DataSourceTransactionManager(dataSource));
        assertEquals(26,upgraded.products(true).size());
    }
    @Test void existingAccountOrderAndStockMigrateOnlyOnce()throws Exception {
        // Separate schema represents an existing installation being migrated.
        var legacy=new AccountStore(json,temp.resolve("old.json"));
        legacy.addAccount(new AccountStore.Account("olduser","olduser","旧账号","salt","hash",1,Instant.now()));
        var oldCore=new CoreService(new TraceBus(),legacy);oldCore.addCart("olduser",new Models.CartAdd("p8","p8-M",2));oldCore.order("olduser","session",checkout("legacy001"));
        var isolated=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("db/schema.sql").build();
        try{var target=new CommerceStore(new JdbcTemplate(isolated),json,new DataSourceTransactionManager(isolated));var imported=new AccountStore(json,temp.resolve("old.json").toString(),target);var live=new CoreService(new TraceBus(),imported,target);assertEquals(1,imported.orders("olduser").size());assertEquals(10,live.product("p8").skus().get(1).stock());new CoreService(new TraceBus(),imported,target);assertEquals(10,target.product("p8",false).skus().get(1).stock());assertTrue(Files.exists(temp.resolve("old.json")));}finally{isolated.shutdown();}
    }
    @Test void adminHttpEndpointsEnforceRoleCsrfAndOneTimeSetup()throws Exception {
        var auth=new AuthService(accounts);var access=new AdminAccess(db,temp.resolve("setup-token.txt").toString());
        var security=new AccountSecurityFilter(auth,json,"http://localhost:5173");ReflectionTestUtils.setField(security,"adminAccess",access);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new CommerceController(db,access,temp.resolve("media").toString()),new AuthController(auth)).setControllerAdvice(new ApiErrors()).addFilters(security).build();
        mvc.perform(get("/api/admin/summary")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/products/p8").param("revision","0")).andExpect(status().isUnauthorized());
        var req=new MockHttpServletRequest();req.addHeader("X-HYJ-Client","admin");var res=new MockHttpServletResponse();auth.register(new Models.Register("newadmin","test-admin-password","掌柜"),req,res);
        var session=(AuthService.Session)req.getAttribute(AuthService.REQUEST_SESSION);Cookie cookie=new Cookie(AuthService.ADMIN_COOKIE,session.token());
        mvc.perform(get("/api/auth/session").cookie(cookie)).andExpect(jsonPath("$.data.authenticated").value(false));
        mvc.perform(get("/api/auth/session").cookie(new Cookie(AuthService.COOKIE,session.token()))).andExpect(jsonPath("$.data.authenticated").value(false));
        mvc.perform(get("/api/admin/summary").cookie(cookie).header("X-HYJ-Client","admin")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/products/p8").param("revision","0").cookie(cookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",session.csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/setup").cookie(cookie).header("X-HYJ-Client","admin").contentType("application/json").content("{\"token\":\"wrong\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/setup").cookie(cookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",session.csrf()).contentType("application/json").content("{\"token\":\"wrong\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/setup").cookie(cookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",session.csrf()).contentType("application/json").content(json.writeValueAsBytes(Map.of("token",Files.readString(temp.resolve("setup-token.txt")))))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/summary").cookie(cookie).header("X-HYJ-Client","admin")).andExpect(status().isOk()).andExpect(jsonPath("$.data.activeProducts").value(25));
        mvc.perform(post("/api/admin/setup").cookie(cookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",session.csrf()).contentType("application/json").content("{\"token\":\"anything\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/products").cookie(cookie).header("X-HYJ-Client","admin").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        String version=String.valueOf(db.record("p8").revision());
        mvc.perform(delete("/api/admin/products/p8").param("revision",version).cookie(cookie).header("X-HYJ-Client","admin")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/products/p8").param("revision","999999").cookie(cookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",session.csrf())).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/products/p8").param("revision",version).cookie(cookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",session.csrf())).andExpect(status().isConflict());
        var active=db.record("p8");
        db.saveProduct(new CommerceStore.ProductRecord(active.product(),"ARCHIVED",active.revision(),active.articleIds()),false,"admin");
        version=String.valueOf(db.record("p8").revision());
        mvc.perform(delete("/api/admin/products/p8").param("revision","999999").cookie(cookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",session.csrf())).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/products/p8").param("revision",version).cookie(cookie).header("X-HYJ-Client","admin").header("X-CSRF-Token",session.csrf())).andExpect(status().isOk());
        assertThrows(NoSuchElementException.class,()->db.record("p8"));
    }
    @Test void featuredDynastiesHaveFourDistinctSourcedFormsAndNormalizedStorage(){
        var groups=List.of(List.of("p11","p12","p15","p16"),List.of("p1","p2","p7","p10"),List.of("p3","p18","p19","p20"),List.of("p13","p21","p22","p23"),List.of("p8","p24","p25","p26"));
        for(var group:groups){
            assertEquals(4,group.stream().map(id->db.product(id,false).form()).distinct().count());
            for(String id:group){
                assertFalse(db.product(id,false).images().isEmpty());
                assertFalse(db.references(id).isEmpty());
                assertTrue(db.references(id).stream().allMatch(a->a.kind()==Models.Kind.FACT&&a.source().contains("https://")));
            }
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.jdbc.queryForList("SELECT details_json FROM product"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.jdbc.queryForList("SELECT snapshot_json FROM shop_order"));
        assertEquals(104,db.jdbc.queryForObject("SELECT COUNT(*) FROM product_size",Integer.class));
        assertEquals(25,db.jdbc.queryForObject("SELECT COUNT(*) FROM product_image",Integer.class));
        assertEquals("盘领衣",new CoreService(new TraceBus(),accounts,db).product("p25").form());
    }
}
