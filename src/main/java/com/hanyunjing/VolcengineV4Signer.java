package com.hanyunjing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;

/** Volcengine Signature V4 for the fixed visual-service actions. */
final class VolcengineV4Signer {
    static final String VERSION = "2022-08-31";
    private static final String REGION = "cn-north-1";
    private static final String SERVICE = "cv";
    private static final String SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    private VolcengineV4Signer() {}

    static String query(String action) {
        if (!"CVSubmitTask".equals(action) && !"CVGetResult".equals(action))
            throw new IllegalArgumentException("Unsupported visual-service action");
        return "Action=" + action + "&Version=" + VERSION;
    }

    static Map<String, String> sign(URI endpoint, String action, byte[] payload,
                                    String accessKey, String secretKey, Instant now) {
        try {
            String date = DATE.format(now);
            String day = date.substring(0, 8);
            String hash = sha256(payload);
            String path = endpoint.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String host = endpoint.getHost();
            if (endpoint.getPort() != -1) host += ":" + endpoint.getPort();
            String canonicalHeaders = "content-type:application/json\nhost:" + host
                + "\nx-content-sha256:" + hash + "\nx-date:" + date + "\n";
            String canonical = "POST\n" + path + "\n" + query(action) + "\n" + canonicalHeaders
                + "\n" + SIGNED_HEADERS + "\n" + hash;
            String scope = day + "/" + REGION + "/" + SERVICE + "/request";
            String stringToSign = "HMAC-SHA256\n" + date + "\n" + scope + "\n"
                + sha256(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] key = hmac(secretKey.getBytes(StandardCharsets.UTF_8), day);
            key = hmac(key, REGION);
            key = hmac(key, SERVICE);
            key = hmac(key, "request");
            String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
            return Map.of("Content-Type", "application/json", "X-Date", date,
                "X-Content-Sha256", hash, "Authorization", "HMAC-SHA256 Credential=" + accessKey
                    + "/" + scope + ", SignedHeaders=" + SIGNED_HEADERS + ", Signature=" + signature);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Unable to sign image-service request");
        }
    }

    private static String sha256(byte[] data) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] hmac(byte[] key, String text) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
}
