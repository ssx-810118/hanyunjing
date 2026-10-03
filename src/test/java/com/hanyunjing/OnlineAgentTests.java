package com.hanyunjing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Local OpenAI protocol stub only: these tests do NOT prove external provider connectivity. */
class OnlineAgentTests {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PROMPT = "我第一次穿，去芙蓉园，想要唐制，预算300元";
    CoreService core;
    Stub stub;
    @BeforeEach void setup() throws Exception { core = spy(new CoreService(new TraceBus())); stub = new Stub(); }
    @AfterEach void close() { stub.close(); }
    OnlineAgent agent() { return new OnlineAgent(core, true, "protocol-test-only", stub.base(), "test-model", Duration.ofSeconds(3), Duration.ofSeconds(10)); }
    MockMvc mvc(OnlineAgent agent) {
        return MockMvcBuilders.standaloneSetup(new ApiController(core, mock(TryOnService.class), agent, new TraceBus()))
            .setControllerAdvice(new ApiErrors()).build();
    }
    @Test void missingCredentials503AndSafeStatusNeverFallback() throws Exception {
        var agent = new OnlineAgent(core, true, "", stub.base(), "test-model");
        var mvc = mvc(agent);
        mvc.perform(post("/api/agent/chat").contentType("application/json").content(JSON.writeValueAsString(new Models.Chat("missing-key", PROMPT))))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
        String status = mvc.perform(get("/api/agent/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.configured").value(false)).andExpect(jsonPath("$.data.ready").value(false))
            .andExpect(jsonPath("$.data.mode").value("ONLINE_ONLY")).andReturn().getResponse().getContentAsString();
        assertFalse(status.contains(stub.base())); assertFalse(status.contains("api-key"));
        assertEquals(0, stub.requests.size()); verify(core, never()).chat(any());
        assertFalse(new OnlineAgent(core, true, "${OPENAI_API_KEY:}", stub.base(), "test").available());
        assertFalse(new OnlineAgent(core, false, "test", stub.base(), "test").available());
    }
    @Test void productionRouteUsesModelToolsAndServerEvidence() throws Exception {
        var agent = agent();
        mvc(agent).perform(post("/api/agent/chat").contentType("application/json").content(JSON.writeValueAsString(new Models.Chat("online", PROMPT))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"))
            .andExpect(jsonPath("$.data.recommendations[0].product.id").value("p1"))
            .andExpect(jsonPath("$.data.recommendations[0].product.skus[0].price").value(199))
            .andExpect(jsonPath("$.data.recommendations[0].accessories[0].id").value("p10"));
        verify(core, never()).chat(any());
        assertEquals(3, stub.requests.size());
        assertTrue(stub.requests.get(0).path("tools").size() >= 6);
        assertTrue(core.traces("online").stream().anyMatch(t -> t.type().equals("LLM_CALL_START")));
        assertTrue(core.traces("online").stream().anyMatch(t -> t.type().equals("TOOL_RESULT") && t.summary().contains("products")));
        assertFalse(core.traces("online").toString().contains(PROMPT));
        assertEquals("RESPONDED", agent.status().connection());
    }
    @ParameterizedTest
    @CsvSource({"第一次穿,true", "首次穿,true", "初次穿,true", "初穿,true", "没穿过,true", "从未穿过,true",
        "不是第一次穿,false", "不是首次穿,false", "非首次,false", "不是初次穿,false", "非初次,false", "并非初次穿,false", "以前穿过,false"})
    void firstWearEvidenceUsesSameMeaningAsUserInput(String evidence, boolean firstWear) throws Exception {
        stub.directDecision = true;
        stub.queryDynasty = "汉"; stub.decisionDynasty = "汉"; stub.queryScene = "";
        stub.selected = List.of("p11"); stub.firstWearEvidence = evidence; stub.firstWear = firstWear;
        mvc(agent()).perform(post("/api/agent/chat").contentType("application/json")
            .content(JSON.writeValueAsString(new Models.Chat("first-wear-synonym", "去芙蓉园，汉制，" + evidence))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"))
            .andExpect(jsonPath("$.data.slots.firstWear").value(firstWear))
            .andExpect(jsonPath("$.data.slots.dynasty").value("汉"))
            .andExpect(jsonPath("$.data.recommendations[0].product.id").value("p11"));
        assertEquals(1, stub.requests.size());
        verify(core, never()).chat(any());
    }
    @Test void inventedOrTruncatedWearEvidenceCannotReverseUserPreference() {
        stub.directDecision = true;
        stub.firstWearEvidence = "初次穿";
        assertEquals(502, assertThrows(OnlineAgent.Failure.class,
            () -> agent().chat(new Models.Chat("invented-wear-evidence", PROMPT))).status());
        assertEquals(502, assertThrows(OnlineAgent.Failure.class,
            () -> agent().chat(new Models.Chat("truncated-wear-evidence", "去芙蓉园，唐制，不是初次穿"))).status());
        stub.firstWear = false; stub.firstWearEvidence = "穿过";
        assertEquals(502, assertThrows(OnlineAgent.Failure.class,
            () -> agent().chat(new Models.Chat("truncated-never-worn", "去芙蓉园，唐制，没穿过"))).status());
    }
    @Test void updatedWearPreferenceOverridesEarlierEvidenceAndOtherwiseIsRetained() {
        stub.directDecision = true;
        stub.firstWearEvidence = "初次穿";
        var agent = agent();
        assertTrue(agent.chat(new Models.Chat("wear-memory", "去芙蓉园，唐制，初次穿")).slots().firstWear());
        stub.firstWear = false; stub.firstWearEvidence = "不是初次穿";
        assertFalse(agent.chat(new Models.Chat("wear-memory", "纠正一下，不是初次穿")).slots().firstWear());
        assertFalse(agent.chat(new Models.Chat("wear-memory", "保留其他需求，喜欢素雅")).slots().firstWear());
        stub.firstWear = true; stub.firstWearEvidence = "初次穿";
        assertEquals(502, assertThrows(OnlineAgent.Failure.class,
            () -> agent.chat(new Models.Chat("wear-memory", "保留其他需求"))).status());
    }
    @Test void profilesNeverLeaveServerAndSensitiveMessagesNeverReachModel() {
        var agent = agent();
        core.body("privacy", new Models.Body(163.123, 55.789, 86.321, 72.456, 93.654, false));
        var reply = agent.chat(new Models.Chat("privacy", PROMPT));
        assertEquals("M", reply.recommendations().get(0).sizeAdvice().size());
        String payload = stub.requests.toString();
        for (String secret : List.of("163.123", "55.789", "86.321", "72.456", "93.654")) {
            assertFalse(payload.contains(secret)); assertFalse(core.traces("privacy").toString().contains(secret));
        }
        int count = stub.requests.size();
        for (String sensitive : List.of("身高168cm", "体重60公斤", "168/60", "照片在https://example.test/a.png", "１６８ｃｍ", "腰围七十二"))
            assertThrows(IllegalArgumentException.class, () -> agent.chat(new Models.Chat("privacy", sensitive)));
        assertEquals(count, stub.requests.size());
        assertDoesNotThrow(() -> OnlineAgent.requireNonSensitive("预算300元，公元618年唐制风格"));
    }
    @Test void inventedIdsDuplicateIdsAndExcessSelectionsAreRejected() {
        for (List<String> ids : List.of(List.of("fake-product"), List.of("p1", "p1"), List.of("p1", "p2", "p3", "p4"))) {
            stub.selected = ids;
            var e = assertThrows(OnlineAgent.Failure.class, () -> agent().chat(new Models.Chat("bad-decision", PROMPT)));
            assertEquals(502, e.status());
        }
        verify(core, never()).chat(any());
    }
    @Test void fabricatedSlotEvidenceIsRejectedAndPreparedSizesAreVerified() {
        stub.fakeEvidence = true;
        assertEquals(502, assertThrows(OnlineAgent.Failure.class, () -> agent().chat(new Models.Chat("fake-evidence", PROMPT))).status());
        stub.fakeEvidence = false; stub.skipSize = true;
        // The server now runs the local size tool for every catalogue item
        // before calling the model, so the model need not repeat this query.
        var reply = agent().chat(new Models.Chat("no-size", PROMPT));
        assertEquals("DONE", reply.status());
        assertTrue(core.traces("no-size").stream().anyMatch(t -> t.type().equals("LOCAL_CHECKS_READY")));
    }
    @Test void preparedToolsAllowOneModelRoundWithoutLeakingBodyData() {
        stub.directDecision = true;
        core.body("prepared", new Models.Body(163.123,55.789,86.321,72.456,93.654,false));
        var reply = agent().chat(new Models.Chat("prepared", PROMPT));
        assertEquals("DONE", reply.status());
        assertEquals("p1", reply.recommendations().get(0).product().id());
        assertEquals("M", reply.recommendations().get(0).sizeAdvice().size());
        assertEquals(1, stub.requests.size());
        assertEquals(13, reply.funnel().total()); assertEquals(5, reply.funnel().sceneMatched());
        assertEquals(5, reply.funnel().styleMatched()); assertEquals(5, reply.funnel().available());
        String system = stub.requests.get(0).path("messages").get(0).path("content").asText();
        assertTrue(system.contains("preparedContext")); assertTrue(system.contains("localChecks"));
        for (String secret : List.of("163.123","55.789","86.321","72.456","93.654")) assertFalse(system.contains(secret));
        assertTrue(core.traces("prepared").stream().anyMatch(t -> t.type().equals("AGENT_CONTEXT_READY")));
        assertEquals(1, core.traces("prepared").stream().filter(t -> t.type().equals("AGENT_PROGRESS")).count());
        verify(core, never()).chat(any());
    }
    @Test void preparedContextAcceptsFullLengthValidUserInput() {
        stub.directDecision = true;
        assertEquals("DONE", agent().chat(new Models.Chat("long-valid", PROMPT + "，喜欢素雅".repeat(130))).status());
        assertEquals(1, stub.requests.size());
    }
    @Test void repeatedModelToolLoopsStopAfterFiveRoundsWithoutFallback() {
        stub.repeatScenes = true;
        var failure = assertThrows(OnlineAgent.Failure.class, () -> agent().chat(new Models.Chat("loop-limit", PROMPT)));
        assertEquals(502, failure.status()); assertEquals(5, stub.requests.size());
        assertTrue(core.traces("loop-limit").stream().anyMatch(t -> t.type().equals("LLM_FAILURE")));
        verify(core, never()).chat(any());
    }
    @Test void everySupportedDynastyCanBeQueriedAndSelectedFromModelTools() {
        Map<String, String> selections = Map.of("汉", "p11", "唐", "p1", "宋", "p6", "元", "p13", "明", "p8");
        for (String dynasty : Dynasty.labels()) {
            stub.queryDynasty = dynasty; stub.decisionDynasty = dynasty; stub.queryScene = "";
            stub.selected = List.of(selections.get(dynasty)); stub.knowledgeQuery = dynasty;
            var reply = agent().chat(new Models.Chat("dynasty-" + Dynasty.labels().indexOf(dynasty),
                "我第一次穿，去芙蓉园，想要" + dynasty + "制"));
            assertEquals(dynasty, reply.slots().dynasty());
            assertEquals(selections.get(dynasty), reply.recommendations().get(0).product().id());
            assertFalse(reply.knowledge().abstained());
        }
        assertTrue(stub.requests.get(0).path("messages").get(0).path("content").asText().contains(String.join("、", Dynasty.labels())));
        verify(core, never()).chat(any());
    }
    @Test void unsupportedToolAndDecisionDynastiesAndGenericHanfuEvidenceAreRejected() {
        stub.queryDynasty = "清";
        assertEquals(502, assertThrows(OnlineAgent.Failure.class, () -> agent().chat(new Models.Chat("unknown-query", PROMPT))).status());
        stub.queryDynasty = "唐"; stub.decisionDynasty = "清";
        assertEquals(502, assertThrows(OnlineAgent.Failure.class, () -> agent().chat(new Models.Chat("unknown-decision", "我第一次穿去芙蓉园，想要清制"))).status());
        stub.queryDynasty = "汉"; stub.decisionDynasty = "汉"; stub.queryScene = "";
        stub.selected = List.of("p11"); stub.dynastyEvidence = "汉服";
        assertEquals(502, assertThrows(OnlineAgent.Failure.class, () -> agent().chat(new Models.Chat("generic-hanfu", "我第一次穿去芙蓉园，想买汉服"))).status());
    }
    @Test void optionalPreferencesDoNotForceUnrelatedQuestionsAndKnowledgeRulesRemain() {
        stub.missing = true;
        var reply = agent().chat(new Models.Chat("slots", "想看唐制"));
        assertEquals("NO_MATCH", reply.status()); assertNull(reply.slots().scene()); assertNull(reply.slots().firstWear());
        assertTrue(reply.recommendations().isEmpty()); assertTrue(reply.missingFields().isEmpty());
        stub.missing = false; stub.knowledgeQuery = "没有对应资料的纹样";
        reply = agent().chat(new Models.Chat("gap", PROMPT));
        assertTrue(reply.knowledge().abstained());
        stub.knowledgeQuery = "唐";
        reply = agent().chat(new Models.Chat("formal", PROMPT + "，用于正式婚礼"));
        assertEquals("HANDOFF", reply.status()); assertTrue(reply.knowledge().humanRequired());
    }
    @Test void explicitBudgetAndSizeCannotBeOverriddenByModelSelections() {
        stub.directDecision=true;
        var agent=agent();
        var low=agent.chat(new Models.Chat("retail-budget",PROMPT+"，预算100元"));
        assertEquals("NO_MATCH",low.status());assertTrue(low.recommendations().isEmpty());
        assertEquals(new java.math.BigDecimal("100"),low.requirements().budget());
        var valid=agent.chat(new Models.Chat("retail-budget","保留原需求，预算400元，M码"));
        assertEquals("DONE",valid.status());
        assertTrue(valid.recommendations().get(0).eligibleSkus().stream().allMatch(s->s.size().equals("M")&&s.price().compareTo(new java.math.BigDecimal("400"))<=0));
        var retained=agent.chat(new Models.Chat("retail-budget","保留其他需求，颜色素雅"));
        assertEquals(valid.requirements(),retained.requirements());
        var unknownSize=agent.chat(new Models.Chat("retail-budget","保留需求",new RetailRules.Requirements(null,"XXXXL",null,1)));
        assertEquals("NO_MATCH",unknownSize.status());
    }
    @Test void modelErrorIsSanitizedAndNeverRetriedOrFallback() throws Exception {
        stub.httpStatus = 401;
        var agent = agent();
        String body = mvc(agent).perform(post("/api/agent/chat").contentType("application/json").content(JSON.writeValueAsString(new Models.Chat("bad-key", PROMPT))))
            .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertEquals(1, stub.requests.size()); assertFalse(body.contains("DO_NOT_LEAK"));
        assertFalse(core.traces("bad-key").toString().contains("DO_NOT_LEAK")); verify(core, never()).chat(any());
    }
    @Test void timeoutIsBoundedAndNoOfflineFallback() {
        stub.delayMillis = 900;
        var agent = new OnlineAgent(core, true, "protocol-test-only", stub.base(), "test", Duration.ofMillis(150), Duration.ofMillis(500));
        long start = System.nanoTime();
        var e = assertThrows(OnlineAgent.Failure.class, () -> agent.chat(new Models.Chat("timeout", PROMPT)));
        assertEquals(504, e.status()); assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        verify(core, never()).chat(any());
    }
    @Test void laterModelRoundsOnlyGetRemainingTurnBudget() {
        stub.delayMillis = 300;
        var agent = new OnlineAgent(core, true, "protocol-test-only", stub.base(), "test", Duration.ofSeconds(2), Duration.ofMillis(500));
        long start = System.nanoTime();
        var failure = assertThrows(OnlineAgent.Failure.class,
            () -> agent.chat(new Models.Chat("turn-time-budget", PROMPT)));
        assertEquals(504, failure.status());
        assertEquals(2, stub.requests.size());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500);
        verify(core, never()).chat(any());
    }
    @Test void sessionMemoryAndLocalProfilesAreIsolated() {
        var agent = agent();
        core.body("session-a", new Models.Body(163., 55., 86., 72., 93., false));
        var a = agent.chat(new Models.Chat("session-a", PROMPT + " alpha-private-session"));
        int cut = stub.requests.size();
        var b = agent.chat(new Models.Chat("session-b", PROMPT + " beta-private-session"));
        assertEquals("M", a.recommendations().get(0).sizeAdvice().size());
        assertNull(b.recommendations().get(0).sizeAdvice().size());
        assertFalse(stub.requests.subList(cut, stub.requests.size()).toString().contains("alpha-private-session"));
        cut = stub.requests.size();
        agent.chat(new Models.Chat("session-a", "保留原需求，预算300元"));
        assertTrue(stub.requests.get(cut).toString().contains("alpha-private-session"));
        assertFalse(stub.requests.get(cut).toString().contains("beta-private-session"));
    }
    @Test void sameSessionSerializesButOtherSessionsCanCallConcurrently() throws Exception {
        var agent = agent(); var pool = Executors.newFixedThreadPool(3);
        stub.delayMillis = 180;
        try {
            Future<Models.AgentReply> first = pool.submit(() -> agent.chat(new Models.Chat("serial", PROMPT + " turn-one")));
            assertTrue(stub.entered.await(2, TimeUnit.SECONDS));
            Future<Models.AgentReply> second = pool.submit(() -> agent.chat(new Models.Chat("serial", PROMPT + " turn-two")));
            Future<Models.AgentReply> other = pool.submit(() -> agent.chat(new Models.Chat("parallel", PROMPT + " turn-other")));
            assertEquals("DONE", first.get(8, TimeUnit.SECONDS).status());
            assertEquals("DONE", second.get(8, TimeUnit.SECONDS).status());
            assertEquals("DONE", other.get(8, TimeUnit.SECONDS).status());
            assertTrue(stub.maxActive.get() >= 2, "Different sessions must not share a global model lock");
            JsonNode secondStart = stub.requests.stream().filter(n -> lastUser(n).contains("turn-two")).findFirst().orElseThrow();
            assertTrue(secondStart.path("messages").toString().contains("turn-one"));
            assertTrue(secondStart.path("messages").toString().contains("selectedIds"));
        } finally { pool.shutdownNow(); }
    }
    static String lastUser(JsonNode root) {
        String text = ""; for (JsonNode m : root.path("messages")) if (m.path("role").asText().equals("user")) text = m.path("content").asText(); return text;
    }
    static final class Stub implements AutoCloseable {
        final HttpServer server; final ExecutorService workers = Executors.newCachedThreadPool();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
        volatile int httpStatus = 200, delayMillis;
        volatile boolean missing, fakeEvidence, skipSize, directDecision, repeatScenes;
        volatile List<String> selected = List.of("p1");
        volatile String knowledgeQuery = "唐";
        volatile String queryDynasty = "唐", decisionDynasty = "唐", queryScene = "tang-furong", dynastyEvidence;
        volatile String firstWearEvidence = "第一次";
        volatile boolean firstWear = true;
        Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext("/v1/chat/completions", exchange -> {
                int inFlight = active.incrementAndGet(); maxActive.accumulateAndGet(inFlight, Math::max);
                try {
                    JsonNode request = JSON.readTree(exchange.getRequestBody()); requests.add(request); entered.countDown();
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                    byte[] body = JSON.writeValueAsBytes(httpStatus == 200 ? response(request) : Map.of("error", Map.of("message", "DO_NOT_LEAK provider internal data", "type", "authentication_error")));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(httpStatus, body.length); exchange.getResponseBody().write(body);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { active.decrementAndGet(); exchange.close(); }
            }); server.start();
        }
        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        Object response(JsonNode request) throws java.io.IOException {
            int results = 0;
            for (JsonNode m : request.path("messages")) {
                if (m.path("role").asText().equals("user")) results = 0;
                if (m.path("role").asText().equals("tool")) results++;
            }
            List<Object> calls = new ArrayList<>();
            if (repeatScenes) {
                calls.add(call("scenes", Map.of()));
            } else if (results == 0 && !directDecision) {
                calls.add(call("scenes", Map.of()));
                calls.add(call("products", Map.of("dynasty", queryDynasty, "scene", queryScene)));
                calls.add(call("knowledge", Map.of("query", knowledgeQuery)));
            } else if (results == 3 && !missing && !directDecision) {
                for (String productId : selected) {
                    if (!skipSize) calls.add(call("localSize", Map.of("productId", productId)));
                    calls.add(call("outfits", Map.of("productId", productId)));
                }
            } else {
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("scene", missing ? null : "tang-furong"); decision.put("dynasty", decisionDynasty);
                decision.put("style", null); decision.put("firstWear", missing ? null : firstWear);
                decision.put("muted", false); decision.put("slim", false);
                decision.put("sceneEvidence", missing ? null : fakeEvidence ? "编造的芙蓉园" : "芙蓉园");
                decision.put("dynastyEvidence", dynastyEvidence == null ? decisionDynasty + "制" : dynastyEvidence); decision.put("firstWearEvidence", missing ? null : firstWearEvidence);
                decision.put("productIds", missing ? List.of() : selected);
                calls.add(call("submitDecision", Map.of("decision", decision)));
            }
            return Map.of("id", "stub-completion", "object", "chat.completion", "created", 1, "model", "test-model",
                "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "tool_calls", calls), "finish_reason", "tool_calls")),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2));
        }
        Object call(String name, Object args) throws java.io.IOException { return Map.of("id", UUID.randomUUID().toString(), "type", "function", "function", Map.of("name", name, "arguments", JSON.writeValueAsString(args))); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
