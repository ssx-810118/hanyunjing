package com.hanyunjing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Tencent Cloud TC3-HMAC-SHA256, JSON POST to the ai3d service. */
final class TencentV3Signer {
    private TencentV3Signer() {}
    static Map<String,String> sign(URI endpoint,String action,byte[] body,String id,String key,String region,Instant now) {
        if(!Set.of("SubmitHunyuanTo3DProJob","QueryHunyuanTo3DProJob").contains(action))throw new IllegalArgumentException("Unsupported 3D action");
        try {
            String day=now.atZone(ZoneOffset.UTC).toLocalDate().toString(), stamp=Long.toString(now.getEpochSecond());
            String host=endpoint.getHost()+(endpoint.getPort()==-1?"":":"+endpoint.getPort());
            String signed="content-type;host;x-tc-action";
            String headers="content-type:application/json; charset=utf-8\nhost:"+host+"\nx-tc-action:"+action.toLowerCase(Locale.ROOT)+"\n";
            String canonical="POST\n/\n\n"+headers+"\n"+signed+"\n"+sha(body);
            String scope=day+"/ai3d/tc3_request";
            String toSign="TC3-HMAC-SHA256\n"+stamp+"\n"+scope+"\n"+sha(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] secret=hmac(("TC3"+key).getBytes(StandardCharsets.UTF_8),day);
            secret=hmac(secret,"ai3d");secret=hmac(secret,"tc3_request");
            return Map.of("Content-Type","application/json; charset=utf-8","X-TC-Action",action,"X-TC-Version","2025-05-13",
                "X-TC-Timestamp",stamp,"X-TC-Region",region,"Authorization","TC3-HMAC-SHA256 Credential="+id+"/"+scope+", SignedHeaders="+signed+", Signature="+HexFormat.of().formatHex(hmac(secret,toSign)));
        }catch(Exception e){throw new IllegalStateException("无法签名3D请求");}
    }
    private static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static byte[] hmac(byte[] key,String value)throws Exception{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}
}
