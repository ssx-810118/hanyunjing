package com.hanyunjing;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
public class AdminAccess {
    private final CommerceStore store;
    private final Path tokenPath;
    public AdminAccess(CommerceStore store,@Value("${app.admin.setup-token-file:.local/admin-setup-token.txt}") String path) {
        this.store=store;this.tokenPath=Path.of(path).toAbsolutePath().normalize();
        status();
    }
    String require() {
        var user=AuthService.currentUser();
        if(user==null)throw new AuthService.Failure(401,"请先登录");
        if(!store.admin(user.id()))throw new AuthService.Failure(403,"此功能仅限管理员");
        return user.id();
    }
    boolean allowed(Models.User user) { return user!=null&&store.admin(user.id()); }
    synchronized Map<String,Object> status() {
        var user=AuthService.currentUser();boolean configured=store.hasAdmin();
        if(!configured&&!Files.exists(tokenPath))try {
            Files.createDirectories(tokenPath.getParent());byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            Files.writeString(tokenPath,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),StandardOpenOption.CREATE_NEW);
        }catch(Exception e){throw new IllegalStateException("无法创建管理员初始化口令，请检查本地目录权限");}
        return Map.of("admin",user!=null&&store.admin(user.id()),"setupRequired",!configured);
    }
    synchronized void setup(String token) {
        var user=AuthService.currentUser();if(user==null)throw new AuthService.Failure(401,"请先登录需要授权的账号");
        if(store.hasAdmin())throw new AuthService.Failure(409,"管理员已初始化");
        try {
            if(token==null||token.length()>100||!Files.exists(tokenPath)||!MessageDigest.isEqual(Files.readString(tokenPath).trim().getBytes(StandardCharsets.UTF_8),token.trim().getBytes(StandardCharsets.UTF_8)))throw new AuthService.Failure(403,"初始化口令不正确");
            store.bootstrapAdmin(user.id());Files.deleteIfExists(tokenPath);
        }catch(java.io.IOException e){throw new IllegalStateException("无法读取管理员初始化口令");}
    }
}
