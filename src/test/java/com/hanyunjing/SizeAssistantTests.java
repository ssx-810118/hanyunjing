package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SizeAssistantTests {
    @TempDir Path temporary;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    CoreService core;
    MockMvc mvc;
    record Client(Cookie cookie, String csrf, String id) {}
    @BeforeEach void setup() {
        var accounts = new AccountStore(json, temporary.resolve("size-accounts.json"));
        var auth = new AuthService(accounts);
        core = new CoreService(new TraceBus(), accounts);
        mvc = MockMvcBuilders.standaloneSetup(new SizeAssistantController(core), new AuthController(auth))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new AccountSecurityFilter(auth, json, "http://127.0.0.1:5173")).build();
    }
    Client client(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        var data = json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data");
        return new Client(new Cookie(AuthService.COOKIE, response.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1]),
            data.path("csrfToken").asText(), data.path("user").path("id").asText());
    }
    Client login(String name) throws Exception {
        var anonymous = client(mvc.perform(get("/api/auth/session")).andExpect(status().isOk()).andReturn().getResponse());
        return client(mvc.perform(post("/api/auth/register").cookie(anonymous.cookie()).header("X-CSRF-Token", anonymous.csrf())
            .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", name, "password", "Size_Test_2026!", "displayName", "尺码测试"))))
            .andExpect(status().isOk()).andReturn().getResponse());
    }
    org.springframework.test.web.servlet.ResultActions calculate(Client who, String product, Object request) throws Exception {
        return mvc.perform(post("/api/products/" + product + "/size-advice").cookie(who.cookie()).header("X-CSRF-Token", who.csrf())
            .param("sessionId", "size-test").contentType("application/json").content(json.writeValueAsBytes(request)));
    }
    Models.Body medium() { return new Models.Body(165.0, null, 88.0, 72.0, 94.0, false); }
    @Test void manualMeasurementsMatchTheActualProductChartWithoutSavingOrExposingNumbers() throws Exception {
        var user = login("size_manual");
        var result = calculate(user, "p14", new SizeAssistantController.SizeRequest(medium(), false))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.size").value("M")).andExpect(jsonPath("$.data.confidence").value("高"))
            .andExpect(jsonPath("$.data.inRange").value(true)).andExpect(jsonPath("$.data.missingFields").isEmpty())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(result.contains("165")); assertFalse(result.contains("88.0"));
        assertNull(core.body(user.id()), "temporary size inputs must not write saved profile");
    }
    @Test void savedMeasurementsBelongOnlyToTheSignedInAccount() throws Exception {
        var alice = login("size_alice"); var bob = login("size_bob");
        core.body(alice.id(), medium());
        var saved = new SizeAssistantController.SizeRequest(null, true);
        calculate(alice, "p14", saved).andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value("M"));
        calculate(bob, "p14", saved).andExpect(status().isOk()).andExpect(jsonPath("$.data.size").isEmpty())
            .andExpect(jsonPath("$.data.inRange").value(false)).andExpect(jsonPath("$.data.missingFields.length()").value(4));
    }
    @Test void partialMeasurementsStayLowConfidenceAndDoNotInferCircumferenceFromWeight() throws Exception {
        var user = login("size_partial");
        calculate(user, "p1", new SizeAssistantController.SizeRequest(new Models.Body(165.0, 55.0, null, null, null, false), false))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value("M"))
            .andExpect(jsonPath("$.data.confidence").value("低")).andExpect(jsonPath("$.data.missingFields.length()").value(3));
        calculate(user, "p1", new SizeAssistantController.SizeRequest(new Models.Body(null, 55.0, null, null, null, false), false))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").isEmpty()).andExpect(jsonPath("$.data.inRange").value(false));
    }
    @Test void unmatchedMeasurementsReturnNoInventedSize() throws Exception {
        var user = login("size_unmatched");
        calculate(user, "p14", new SizeAssistantController.SizeRequest(new Models.Body(220.0, null, 140.0, 130.0, 150.0, false), false))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").isEmpty()).andExpect(jsonPath("$.data.inRange").value(false));
    }
    @Test void invalidMeasurementsAndAmbiguousSourcesAreRejected() throws Exception {
        var user = login("size_invalid");
        calculate(user, "p14", new SizeAssistantController.SizeRequest(new Models.Body(999.0, null, null, null, null, false), false)).andExpect(status().isBadRequest());
        calculate(user, "p14", new SizeAssistantController.SizeRequest(medium(), true)).andExpect(status().isBadRequest());
        calculate(user, "p14", new SizeAssistantController.SizeRequest(null, false)).andExpect(status().isBadRequest());
        calculate(user, "p9999", new SizeAssistantController.SizeRequest(medium(), false)).andExpect(status().isNotFound());
    }
    @Test void sizingRequiresLoginAndCsrfLikeOtherPrivateActions() throws Exception {
        mvc.perform(post("/api/products/p14/size-advice").param("sessionId", "size-test").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        var user = login("size_csrf");
        mvc.perform(post("/api/products/p14/size-advice").cookie(user.cookie()).param("sessionId", "size-test")
            .contentType("application/json").content(json.writeValueAsBytes(new SizeAssistantController.SizeRequest(medium(), false))))
            .andExpect(status().isForbidden());
    }
}
