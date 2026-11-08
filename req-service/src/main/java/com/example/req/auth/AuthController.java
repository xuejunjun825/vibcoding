package com.example.req.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentication endpoints; login is anonymous, /me needs any valid token. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public AuthViews.LoginResponse login(@RequestBody AuthViews.LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public AuthViews.MeResponse me() {
        return authService.me();
    }
}
