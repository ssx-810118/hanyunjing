package com.hanyunjing;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class VolcengineV4SignerTests {
    private static final URI ENDPOINT = URI.create("https://visual.volcengineapi.com/");
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    @Test void matchesIndependentOfficialAlgorithmGoldenSignature() {
        // Independently computed with the official HTTP example's Python HMAC algorithm.
        // These are dummy credentials; no request is sent.
        byte[] payload = ("{\"req_key\":\"dressing_diffusionV2\",\"task_id\":\"example-task\","
                + "\"req_json\":\"{\\\"return_url\\\":false}\"}").getBytes(StandardCharsets.UTF_8);
        var headers = VolcengineV4Signer.sign(ENDPOINT, "CVGetResult", payload,
                "test-ak", "test-sk", NOW);

        assertEquals("20261003T000000Z", headers.get("X-Date"));
        assertEquals("application/json", headers.get("Content-Type"));
        assertEquals("b47312b9a1d833afa17825ecb5f1cc443658460849ac42c9c3769fc416b45dde",
                headers.get("X-Content-Sha256"));
        assertEquals("HMAC-SHA256 Credential=test-ak/20261003/cn-north-1/cv/request, "
                + "SignedHeaders=content-type;host;x-content-sha256;x-date, "
                + "Signature=b1e00e413f743cc70cd64a829585e22f97b779a1a1075610d908a06219d7e96a",
                headers.get("Authorization"));
        assertFalse(headers.containsKey("Host"), "Java HttpClient supplies the Host header");
        assertTrue(headers.values().stream().noneMatch(value -> value.contains("test-sk")));
    }

    @Test void bindsSignatureToActionBodyDateAndHost() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        var original = VolcengineV4Signer.sign(ENDPOINT, "CVGetResult", payload,
                "test-ak", "test-sk", NOW);
        assertNotEquals(original.get("Authorization"), VolcengineV4Signer.sign(ENDPOINT,
                "CVSubmitTask", payload, "test-ak", "test-sk", NOW).get("Authorization"));
        assertNotEquals(original.get("Authorization"), VolcengineV4Signer.sign(ENDPOINT,
                "CVGetResult", "{\"x\":1}".getBytes(StandardCharsets.UTF_8),
                "test-ak", "test-sk", NOW).get("Authorization"));
        assertNotEquals(original.get("Authorization"), VolcengineV4Signer.sign(ENDPOINT,
                "CVGetResult", payload, "test-ak", "test-sk", NOW.plusSeconds(1)).get("Authorization"));
        assertNotEquals(original.get("Authorization"), VolcengineV4Signer.sign(
                URI.create("http://127.0.0.1:12345/"), "CVGetResult", payload,
                "test-ak", "test-sk", NOW).get("Authorization"));
    }
}
