package com.hanyunjing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Checks the actual catalogue resources; try-on demonstration rendering remains a separate contract. */
class ProductImageTests {
    CoreService core;
    MockMvc mvc;

    @BeforeEach void setup() {
        var bus = new TraceBus();
        core = spy(new CoreService(bus));
        mvc = MockMvcBuilders.standaloneSetup(new ApiController(core, mock(TryOnService.class),
            new OnlineAgent(core, false, "", "", ""), bus)).setControllerAdvice(new ApiErrors()).build();
    }

    @Test void allFourteenPublishedProductsServeDistinctCompleteWebpImages() throws Exception {
        var digests = new HashSet<String>();
        for (var product : core.products(null, null, null, null, null)) {
            byte[] bytes = mvc.perform(get("/api/products/" + product.id() + "/image"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/webp"))
                .andReturn().getResponse().getContentAsByteArray();
            assertWebp(bytes, product.id());
            assertTrue(digests.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))),
                "Each product must have its own image: " + product.id());
        }
        assertEquals(14, digests.size());
    }

    @Test void everyTryOnProductHasItsOwnDecodableGarmentReference() throws Exception {
        var digests = new HashSet<String>();
        for (var product : core.products(null, null, null, null, null)) {
            var resource = new org.springframework.core.io.ClassPathResource("static/images/tryon-garments/" + product.id() + ".jpg");
            assertTrue(resource.isReadable(), product.id());
            byte[] bytes;
            try (var input = resource.getInputStream()) { bytes = input.readAllBytes(); }
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertNotNull(image, "Garment reference must decode: " + product.id());
            assertTrue(image.getWidth() >= 512 && image.getHeight() >= 512, product.id());
            assertTrue(digests.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))),
                "Every garment must have a separate reference: " + product.id());
        }
        assertEquals(14, digests.size());
    }

    @Test void absentResourceAndUnknownProductReturn404WithoutIllustrationFallback() throws Exception {
        doReturn(core.product("p1")).when(core).product("missing-image");
        mvc.perform(get("/api/products/missing-image/image")).andExpect(status().isNotFound()).andExpect(content().bytes(new byte[0]));
        mvc.perform(get("/api/products/unknown/image")).andExpect(status().isNotFound());
        mvc.perform(get("/api/products/p15/image")).andExpect(status().isNotFound());
    }

    private static void assertWebp(byte[] bytes, String id) {
        assertTrue(bytes.length > 1024, "Image data must be present: " + id);
        assertEquals("RIFF", ascii(bytes, 0));
        assertEquals("WEBP", ascii(bytes, 8));
        assertEquals(bytes.length - 8L, Integer.toUnsignedLong(littleInt(bytes, 4)), "Complete RIFF payload: " + id);
        int width = 0, height = 0;
        boolean imageChunk = false;
        for (int offset = 12; offset + 8 <= bytes.length;) {
            String type = ascii(bytes, offset);
            long size = Integer.toUnsignedLong(littleInt(bytes, offset + 4));
            int payload = offset + 8;
            assertTrue(size <= bytes.length - payload, "Truncated WebP chunk: " + id);
            if (type.equals("VP8X")) {
                assertTrue(size >= 10);
                width = little24(bytes, payload + 4) + 1;
                height = little24(bytes, payload + 7) + 1;
            } else if (type.equals("VP8 ")) {
                assertTrue(size >= 10);
                assertArrayEquals(new byte[]{(byte) 0x9d, 0x01, 0x2a}, java.util.Arrays.copyOfRange(bytes, payload + 3, payload + 6));
                width = little16(bytes, payload + 6) & 0x3fff;
                height = little16(bytes, payload + 8) & 0x3fff;
                imageChunk = true;
            } else if (type.equals("VP8L")) {
                assertTrue(size >= 5);
                assertEquals(0x2f, bytes[payload] & 0xff);
                int bits = littleInt(bytes, payload + 1);
                width = (bits & 0x3fff) + 1;
                height = ((bits >>> 14) & 0x3fff) + 1;
                imageChunk = true;
            }
            offset = Math.toIntExact(payload + size + (size & 1));
        }
        assertTrue(imageChunk, "A compressed image chunk is required: " + id);
        assertTrue(width >= 512 && height >= 512, "Catalogue image dimensions must not be the 480px try-on placeholder: " + id);
    }

    private static String ascii(byte[] bytes, int offset) { return new String(bytes, offset, 4, StandardCharsets.US_ASCII); }
    private static int littleInt(byte[] bytes, int offset) { return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
    private static int little16(byte[] bytes, int offset) { return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8); }
    private static int little24(byte[] bytes, int offset) { return little16(bytes, offset) | ((bytes[offset + 2] & 0xff) << 16); }
}
