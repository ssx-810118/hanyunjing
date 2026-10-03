package com.hanyunjing;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    private ResponseEntity<Models.Api<Models.AuthSession>> result(Models.AuthSession value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Models.Api.ok(value));
    }
    @GetMapping("/session") public ResponseEntity<Models.Api<Models.AuthSession>> session(HttpServletRequest req, HttpServletResponse res) { return result(auth.session(req, res)); }
    @PostMapping("/register") public ResponseEntity<Models.Api<Models.AuthSession>> register(@Valid @RequestBody Models.Register in, HttpServletRequest req, HttpServletResponse res) { return result(auth.register(in, req, res)); }
    @PostMapping("/login") public ResponseEntity<Models.Api<Models.AuthSession>> login(@Valid @RequestBody Models.Login in, HttpServletRequest req, HttpServletResponse res) { return result(auth.login(in, req, res)); }
    @PostMapping("/logout") public ResponseEntity<Models.Api<Models.AuthSession>> logout(HttpServletRequest req, HttpServletResponse res) { return result(auth.logout(req, res)); }
}
