package com.hanyunjing;

import com.fasterxml.jackson.databind.*;
import java.nio.*;
import java.util.*;

/** GLB must be self-contained: the browser must not fetch model-controlled external URLs. */
final class GlbValidator {
    static final int MAX_BYTES=60*1024*1024;
    static void validate(byte[] bytes) {
        try {
            if(bytes==null||bytes.length<28||bytes.length>MAX_BYTES)throw new IllegalArgumentException();
            var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(b.getInt()!=0x46546c67||b.getInt()!=2||b.getInt()!=bytes.length)throw new IllegalArgumentException();
            int length=b.getInt();if(b.getInt()!=0x4e4f534a||length<2||length%4!=0||length>b.remaining())throw new IllegalArgumentException();
            byte[] json=new byte[length];b.get(json);JsonNode root=new ObjectMapper().readTree(json);
            if(!root.path("asset").path("version").asText().equals("2.0")||root.path("meshes").isEmpty())throw new IllegalArgumentException();
            for(var item:root.path("buffers"))if(item.has("uri"))throw new IllegalArgumentException();
            for(var item:root.path("images"))if(item.has("uri")||!item.has("bufferView"))throw new IllegalArgumentException();
            if(root.path("extensionsUsed").toString().contains("KHR_draco_mesh_compression")||root.path("extensionsUsed").toString().contains("KHR_texture_basisu"))throw new IllegalArgumentException();
            rejectExternal(root);
            if(b.remaining()<8)throw new IllegalArgumentException();int bin=b.getInt();
            if(b.getInt()!=0x004e4942||bin!=b.remaining()||bin<1)throw new IllegalArgumentException();
        }catch(Exception e){throw new IllegalStateException("3D模型格式无效或包含暂不支持的外部资源，未展示模型");}
    }
    private static void rejectExternal(JsonNode node) {
        if(node.isObject())node.fields().forEachRemaining(e->{if(e.getKey().equalsIgnoreCase("uri")||e.getKey().equalsIgnoreCase("url"))throw new IllegalArgumentException();rejectExternal(e.getValue());});
        else if(node.isArray())node.forEach(GlbValidator::rejectExternal);
    }
}
