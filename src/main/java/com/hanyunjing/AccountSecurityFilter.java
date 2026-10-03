package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
@Order(1)
public class AccountSecurityFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper json;
    private final Set<String> origins;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AdminAccess adminAccess;
    public AccountSecurityFilter(AuthService auth, ObjectMapper json, @Value("${app.cors-origins}") String origins) {
        this.auth = auth; this.json = json; this.origins = Set.of(origins.split(","));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith(request.getContextPath() + "/api/") || request.getMethod().equals("OPTIONS"); }
    private boolean publicRead(String path) {
        return path.startsWith("/api/media/") || path.equals("/api/auth/session") || path.equals("/api/agent/status") || path.equals("/api/tryon/status")
            || path.equals("/api/products") || path.startsWith("/api/products/") || path.equals("/api/categories")
            || path.equals("/api/scenes") || path.equals("/api/suggestions") || path.equals("/api/knowledge/search")
            || path.equals("/api/knowledge/forms") || path.equals("/api/knowledge/dynasties") || path.startsWith("/api/knowledge/articles/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean read = request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
        boolean authAction = path.equals("/api/auth/login") || path.equals("/api/auth/register") || path.equals("/api/auth/logout");
        AuthService.Session session = auth.find(request);
        request.setAttribute(AuthService.REQUEST_SESSION, session);
        if (!(read && publicRead(path)) && !authAction && (session == null || session.user() == null)) {
            fail(response, 401, "请先登录后再使用此功能"); return;
        }
        if ((path.startsWith("/api/admin/") || path.equals("/api/knowledge/add")) && !"admin".equals(request.getHeader("X-HYJ-Client"))) {
            fail(response, 403, "请从独立管理工作台使用此功能"); return;
        }
        if (!read) {
            String origin = request.getHeader("Origin");
            String referer = request.getHeader("Referer");
            if (origin == null && referer != null) try {
                URI uri = URI.create(referer); origin = uri.getScheme() + "://" + uri.getRawAuthority();
            } catch (IllegalArgumentException e) { origin = "invalid"; }
            String ownOrigin = request.getScheme() + "://" + request.getServerName() +
                ((request.getServerPort() == 80 && request.getScheme().equals("http") || request.getServerPort() == 443 && request.getScheme().equals("https")) ? "" : ":" + request.getServerPort());
            if ((origin != null && !origins.contains(origin) && !ownOrigin.equals(origin)) || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                fail(response, 403, "请求来源校验失败，请从本站页面重试"); return;
            }
            String csrf = request.getHeader("X-CSRF-Token");
            if (session == null || csrf == null || !MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.US_ASCII), csrf.getBytes(StandardCharsets.US_ASCII))) {
                fail(response, 403, "页面安全令牌已失效，请刷新页面后重试"); return;
            }
        }
        if(adminAccess!=null && (path.startsWith("/api/admin/")&&!path.equals("/api/admin/access")&&!path.equals("/api/admin/setup") || path.equals("/api/knowledge/add"))) {
            if(session==null||!adminAccess.allowed(session.user())) {fail(response,403,"此功能仅限管理员");return;}
        }
        if (!(read && publicRead(path)) || path.endsWith("/reviews")) response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
    private void fail(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store"); json.writeValue(response.getWriter(), new Models.Api<Void>(status, message, null));
    }
}
