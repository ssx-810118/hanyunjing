package com.hanyunjing;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TraceBusTests {
    @RestController static class StreamController {
        final TraceBus bus;
        StreamController(TraceBus bus) { this.bus = bus; }
        @GetMapping("/stream") SseEmitter stream() { return bus.subscribe("account-session", 0, "client-session"); }
    }
    @Test void emptyStreamFlushesImmediatelyAndDoesNotInventTraceEvents() throws Exception {
        var bus = new TraceBus();
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(bus)).build();
        var result = mvc.perform(get("/stream")).andExpect(status().isOk())
            .andExpect(request().asyncStarted()).andReturn();
        assertTrue(result.getResponse().getContentAsString().contains(":connected"));
        assertTrue(bus.events("account-session", 0).isEmpty());
        bus.publish("other-account", "SHOULD_NOT_APPEAR", "private");
        bus.publish("account-session", "AGENT_STARTED", "safe summary");
        String data = result.getResponse().getContentAsString();
        assertFalse(data.contains("SHOULD_NOT_APPEAR"));
        assertTrue(data.contains("AGENT_STARTED"));
        assertTrue(data.contains("client-session"));
        assertFalse(data.contains("account-session"));
    }
}
