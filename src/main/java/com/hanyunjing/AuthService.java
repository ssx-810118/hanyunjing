package com.hanyunjing;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    static final String COOKIE = "HYJ_SESSION", REQUEST_SESSION = AuthService.class.getName() + ".session";
    static final String ADMIN_COOKIE = "HYJ_ADMIN_SESSION";
    static String cookieName(HttpServletRequest request) { return "admin".equals(request.getHeader("X-HYJ-Client")) ? ADMIN_COOKIE : COOKIE; }
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 600_000;
    private static final Duration AUTH_TTL = Duration.ofHours(8), ANON_TTL = Duration.ofMinutes(30);
    public static final class Failure extends RuntimeException {
        private final int status;
        Failure(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
    record Session(String token, String csrf, Models.User user, Instant expiresAt, String clientCookie) {}
    private record Attempts(int count, Instant since) {}
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final AccountStore store;
    private final Clock clock;
    @Autowired public AuthService(AccountStore store) { this(store, Clock.systemUTC()); }
    AuthService(AccountStore store, Clock clock) { this.store = store; this.clock = clock; }

    Session find(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (!cookieName(request).equals(cookie.getName())) continue;
            Session value = sessions.get(cookie.getValue());
            if (value != null && value.expiresAt().isAfter(clock.instant()) && value.clientCookie().equals(cookieName(request))) return value;
            if (value != null && !value.expiresAt().isAfter(clock.instant())) sessions.remove(value.token());
        }
        return null;
    }
    Models.AuthSession session(HttpServletRequest request, HttpServletResponse response) {
        Session session = find(request);
        if (session == null) session = create(null, request, response);
        return view(session);
    }
    Models.AuthSession register(Models.Register in, HttpServletRequest request, HttpServletResponse response) {
        limit(request);
        String username = in.username().trim();
        if (!username.matches("[A-Za-z0-9_-]{3,32}")) throw new IllegalArgumentException("用户名须为3至32位字母、数字、下划线或短横线");
        validatePassword(in.password());
        String displayName = in.displayName() == null || in.displayName().isBlank() ? username : in.displayName().trim();
        if (displayName.length() > 40 || displayName.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("昵称须为1至40个可显示字符");
        byte[] saltBytes = new byte[16]; RANDOM.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        var account = new AccountStore.Account("u_" + UUID.randomUUID().toString().replace("-", ""), username,
            displayName, salt, hash(in.password(), salt, ITERATIONS), ITERATIONS, clock.instant());
        store.addAccount(account);
        forget(request);
        return view(create(account.user(), request, response));
    }
    Models.AuthSession login(Models.Login in, HttpServletRequest request, HttpServletResponse response) {
        limit(request);
        AccountStore.Account account = store.account(in.username().trim());
        // Do comparable password work for nonexistent accounts, without revealing which field failed.
        String candidate = hash(in.password(), account == null ? "bWlzc2luZy1hY2NvdW50LQ==" : account.salt(),
            account == null ? ITERATIONS : account.iterations());
        if (account == null || !MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII), account.passwordHash().getBytes(StandardCharsets.US_ASCII)))
            throw new Failure(401, "用户名或密码不正确");
        forget(request);
        return view(create(account.user(), request, response));
    }
    Models.AuthSession logout(HttpServletRequest request, HttpServletResponse response) {
        forget(request);
        return view(create(null, request, response));
    }
    private void forget(HttpServletRequest request) { Session current = find(request); if (current != null) sessions.remove(current.token()); }
    private Session create(Models.User user, HttpServletRequest request, HttpServletResponse response) {
        cleanup();
        if (sessions.size() >= 10_000) throw new Failure(503, "当前登录请求较多，请稍后重试");
        Duration ttl = user == null ? ANON_TTL : AUTH_TTL;
        var session = new Session(random(32), random(32), user, clock.instant().plus(ttl), cookieName(request));
        sessions.put(session.token(), session);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName(request), session.token()).httpOnly(true)
            .secure(request.isSecure()).sameSite("Lax").path("/").maxAge(ttl).build().toString());
        request.setAttribute(REQUEST_SESSION, session);
        return session;
    }
    private Models.AuthSession view(Session session) { return new Models.AuthSession(session.user() != null, session.user(), session.csrf()); }
    static Models.User currentUser() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        Object value = attributes.getRequest().getAttribute(REQUEST_SESSION);
        return value instanceof Session session ? session.user() : null;
    }
    static String owner(String originalSession) {
        WebSupport.session(originalSession);
        Models.User user = currentUser();
        return user == null ? originalSession : user.id();
    }
    static String scope(String originalSession) {
        WebSupport.session(originalSession);
        Models.User user = currentUser();
        if (user == null) return originalSession; // Core/controller unit tests do not install the HTTP filter.
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((user.id() + ":" + originalSession).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("账号会话初始化失败"); }
    }
    private synchronized void limit(HttpServletRequest request) {
        String key = request.getRemoteAddr();
        Instant now = clock.instant();
        Attempts previous = attempts.get(key);
        if (previous == null || previous.since().plusSeconds(900).isBefore(now)) previous = new Attempts(0, now);
        if (previous.count() >= 30) throw new Failure(429, "登录或注册尝试过于频繁，请15分钟后再试");
        attempts.put(key, new Attempts(previous.count() + 1, previous.since()));
    }
    static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72 || password.isBlank())
            throw new IllegalArgumentException("密码长度须为8至72位");
    }
    static String hash(String password, String salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), iterations, 256);
        try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("密码校验服务不可用"); }
        finally { spec.clearPassword(); }
    }
    private static String random(int length) { byte[] bytes = new byte[length]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    @Scheduled(fixedDelay = 60_000) public void cleanup() {
        Instant now = clock.instant(); sessions.values().removeIf(s -> !s.expiresAt().isAfter(now));
        attempts.values().removeIf(a -> !a.since().plusSeconds(900).isAfter(now));
    }
}
