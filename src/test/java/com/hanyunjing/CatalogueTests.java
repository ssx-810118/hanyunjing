package com.hanyunjing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogueTests {
    CoreService core;
    MockMvc mvc;

    @BeforeEach void setup() {
        var bus = new TraceBus();
        // These catalogue-data tests supply asset readiness; ProductImageTests checks published files.
        core = new CoreService(bus, AccountStore.inMemory(), true, id -> true);
        mvc = MockMvcBuilders.standaloneSetup(new ApiController(core, mock(TryOnService.class),
            new OnlineAgent(core, false, "", "", ""), bus)).setControllerAdvice(new ApiErrors()).build();
    }

    @Test void dynastyEndpointsShareTheOrderedEnumAndRejectUnknownFilters() throws Exception {
        assertEquals(List.of("汉", "唐", "宋", "元", "明"), Dynasty.labels());
        mvc.perform(get("/api/categories").param("sessionId", "catalogue"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.dynasties", contains(Dynasty.labels().toArray(String[]::new))));
        mvc.perform(get("/api/knowledge/dynasties").param("sessionId", "catalogue"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data", contains(Dynasty.labels().toArray(String[]::new))));
        for (String dynasty : Dynasty.labels()) {
            assertFalse(core.products(dynasty, null, null, null, null).isEmpty());
            mvc.perform(get("/api/products").param("sessionId", "catalogue").param("dynasty", dynasty))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].dynasty").value(dynasty));
        }
        mvc.perform(get("/api/products").param("sessionId", "catalogue").param("dynasty", "清"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertThrows(IllegalArgumentException.class, () -> core.products("unknown", null, null, null, null));
    }

    @Test void defaultCatalogueKeepsUnillustratedExpansionOutOfAllPublicProductAccess() {
        var published = new CoreService(new TraceBus());
        assertEquals(14, published.products(null, null, null, null, null).size());
        assertEquals(13, published.products(null, null, null, null, null).stream().filter(p -> !p.category().equals("配饰")).count());
        for (int i = 15; i <= 26; i++) {
            String id = "p" + i;
            assertThrows(java.util.NoSuchElementException.class, () -> published.product(id));
        }
        assertTrue(published.articles().stream().anyMatch(a -> a.id().equals("a14")));
        assertTrue(published.articles().stream().anyMatch(a -> a.id().equals("a15")));
        assertTrue(published.articles().stream().anyMatch(a -> a.id().equals("a16")));
    }

    @Test void enablingExpansionRejectsAProductWhoseAssetsAreNotReady() {
        var error = assertThrows(IllegalStateException.class, () -> new CoreService(new TraceBus(),
            AccountStore.inMemory(), true, id -> !id.equals("p15")));
        assertTrue(error.getMessage().contains("p15"));
        assertTrue(error.getMessage().contains("app.catalogue.expansion-enabled"));
    }

    @Test void allTwentySixProductsHaveTheExpectedDynastyPriceAndStableSkus() {
        var catalogue = core.products(null, null, null, null, null);
        assertEquals(26, catalogue.size());
        String[] dynasties = {"唐","唐","宋","唐","唐","宋","唐","明","宋","唐","汉","汉","元","明","汉","汉","汉","宋","宋","宋","元","元","元","明","明","明"};
        int[] prices = {199,239,169,219,189,179,209,259,149,89,249,239,269,259,269,259,279,219,189,229,299,329,289,239,289,229};
        for (int i = 0; i < dynasties.length; i++) {
            String id = "p" + (i + 1);
            var product = core.product(id);
            assertEquals(dynasties[i], product.dynasty(), id);
            assertEquals(List.of("S", "M", "L", "XL"), product.sizeChart().stream().map(Models.SizeRow::size).toList());
            assertEquals(List.of(id + "-S", id + "-M", id + "-L", id + "-XL"), product.skus().stream().map(Models.Sku::id).toList());
            for (var sku : product.skus()) {
                assertEquals(0, BigDecimal.valueOf(prices[i]).compareTo(sku.price()), sku.id());
                assertEquals(sku.size().equals("L") ? 8 : 12, sku.stock());
            }
            assertEquals(List.of("/api/products/" + id + "/image"), product.images());
            assertTrue(product.description().contains("不是馆藏实物或文物复原"));
            assertTrue(product.description().contains("不作为断代依据"));
        }
        assertEquals("明·苍青曳撒", core.product("p8").name());
        assertFalse(core.products("唐", null, null, null, null).stream().anyMatch(p -> p.id().equals("p8")));
        assertTrue(core.products("明", null, null, null, null).stream().anyMatch(p -> p.id().equals("p8")));
        assertEquals("宋风·青灰对襟长衫", core.product("p6").name());
        assertEquals("对襟长衫", core.product("p6").form());
        assertEquals("宋风·茶白长衫", core.product("p9").name());
        assertTrue(core.product("p13").description().contains("蒙古族服饰设计参考"));
        Map<String, Long> counts = catalogue.stream().collect(Collectors.groupingBy(Models.Product::dynasty, Collectors.counting()));
        assertEquals(Map.of("汉",5L,"唐",6L,"宋",6L,"元",4L,"明",5L), counts);
    }

    @Test void newDesignsHaveExplicitColorsSourcesAndDoNotMisclassifyDynasties() {
        String[] colors = {"浅杏配绛紫","素纱白","茶金","素白","藕荷","皂黑","绀紫","黛蓝","深绿","豆绿","绛紫","杏金"};
        for (int i = 0; i < colors.length; i++) {
            var product = core.product("p" + (15 + i));
            assertEquals(List.of(colors[i]), product.colors(), product.id());
            assertTrue(product.skus().stream().allMatch(sku -> sku.color().equals(product.colors().get(0))));
            assertTrue(product.description().contains("形制参考："));
            assertTrue(product.description().contains("标价、库存与尺码表"));
            assertTrue(product.description().contains("演示数据"));
        }
        assertEquals("围裹两片裙", core.product("p19").form());
        assertTrue(core.product("p19").description().contains("不将两片裙等同于马面裙"));
        assertEquals("元", core.product("p22").dynasty());
        assertTrue(core.product("p22").description().contains("不据此推定穿着者民族"));
        for (String id : List.of("p13", "p21")) assertTrue(core.product(id).description().contains("蒙古族服饰设计参考"));
        assertEquals("盘领公服袍",core.product("p23").form());
        assertEquals("曳撒",core.product("p8").form());
        assertEquals("盘领衣",core.product("p25").form());
        assertEquals("明",core.product("p25").dynasty());
        assertTrue(core.articles().stream().anyMatch(a -> a.id().equals("a14") && a.source().contains("itemid=31106")));
        assertTrue(core.articles().stream().anyMatch(a -> a.id().equals("a15") && a.source().contains("itemid=4312")));
        assertTrue(core.articles().stream().anyMatch(a -> a.id().equals("a16") && a.source().contains("itemid=1870")));
    }

    @Test void eachDynastyHasSearchableSourcedFactsWithoutDesignAdvice() throws Exception {
        for (String dynasty : Dynasty.labels()) {
            var result = core.searchKnowledge(dynasty);
            assertFalse(result.abstained(), dynasty);
            assertTrue(result.hits().stream().anyMatch(h -> h.article().kind() == Models.Kind.FACT), dynasty);
            assertTrue(result.hits().stream().allMatch(h -> h.article().kind() == Models.Kind.FACT), dynasty);
            assertTrue(result.hits().stream().allMatch(h -> h.article().keywords().contains(dynasty)), dynasty);
            mvc.perform(get("/api/knowledge/search").param("sessionId", "knowledge").param("q", dynasty))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.abstained").value(false));
        }
        for (var article : core.articles()) {
            assertFalse(article.source().contains("西安礼仪馆"));
            assertFalse(article.content().contains("唐制正式场合行拱手礼"));
            if (article.kind() == Models.Kind.FACT) {
                assertTrue(List.of("https://www.chinasilkmuseum.com/","https://www.dpm.org.cn/","https://zh.wikisource.org/","https://www.gjrwls.com/").stream().anyMatch(article.source()::contains));
            }
        }
        assertTrue(core.articles().stream().allMatch(a -> a.kind() == Models.Kind.FACT));
        assertTrue(core.searchKnowledge("曳撒").hits().stream().anyMatch(h -> h.article().id().equals("a7")));
        assertTrue(core.searchKnowledge("曲裾").hits().stream().anyMatch(h -> h.article().id().equals("a4")));
        assertTrue(core.searchKnowledge("正式婚礼").humanRequired());
        core.add(new Models.KnowledgeAdd("明代测试冲突", "测试", "测试相反结论", Models.Kind.COMMON,
            "本地测试", List.of("明"), "yesa-catalogue-dynasty", "conflicting"));
        assertTrue(core.searchKnowledge("明").abstained());
    }

    @Test void offlineExplicitDynastyChangesSupportAllFiveWithoutInferringHanFromHanfu() {
        assertTrue(Dynasty.explicitIn("我想买汉服").isEmpty());
        assertEquals("宋", Dynasty.explicitIn("先看唐制，再换宋制").orElseThrow());
        for (String dynasty : Dynasty.labels()) {
            var reply = core.chat(new Models.Chat("offline-" + Dynasty.labels().indexOf(dynasty),
                "我第一次穿去芙蓉园，身高168cm，体重60公斤，想看" + dynasty + "制"));
            assertEquals(dynasty, reply.slots().dynasty());
            assertFalse(reply.recommendations().isEmpty(), dynasty);
            assertTrue(reply.recommendations().stream().allMatch(r -> r.product().dynasty().equals(dynasty)));
        }
    }
}
