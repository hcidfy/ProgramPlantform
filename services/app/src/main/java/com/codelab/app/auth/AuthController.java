package com.codelab.app.auth;

import com.codelab.app.config.AppProperties;
import com.codelab.app.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口（契约见 PRD §13.1）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService auth;
    private final AppProperties props;

    public AuthController(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    public record RegisterRequest(
            @NotBlank(message = "不能为空") @Email(message = "格式不正确") @Size(max = 190) String email,
            @NotBlank(message = "不能为空") @Size(max = 64) String displayName,
            @NotBlank(message = "不能为空") String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "不能为空") String email,
            @NotBlank(message = "不能为空") String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
            AuthService.UserView user) {
    }

    public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.UserView> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(201).body(auth.register(req.email(), req.displayName(), req.password()));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        AuthService.TokenPair pair = auth.login(req.email(), req.password(), AuthService.truncateDevice(userAgent));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(pair.refreshToken(), pair.refreshExpiresAt()).toString())
                .body(new TokenResponse(pair.accessToken(), "Bearer", pair.expiresInSeconds(), pair.user()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        AuthService.TokenPair pair = auth.refresh(refreshToken, AuthService.truncateDevice(userAgent));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(pair.refreshToken(), pair.refreshExpiresAt()).toString())
                .body(new AccessTokenResponse(pair.accessToken(), "Bearer", pair.expiresInSeconds()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        CurrentUser current = authentication != null && authentication.getPrincipal() instanceof CurrentUser cu
                ? cu : null;
        auth.logout(current == null ? null : current.id(), current == null ? null : current.sessionId(), refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                .build();
    }

    @GetMapping("/me")
    public AuthService.UserView me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser cu)) {
            throw com.codelab.app.common.ApiException.unauthorized("请先登录");
        }
        return auth.me(cu.id());
    }

    private ResponseCookie refreshCookie(String token, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(props.security().cookieSecure())
                .path("/api/auth")
                .maxAge(ttl.isNegative() ? Duration.ZERO : ttl)
                .sameSite("Strict")
                .build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(props.security().cookieSecure())
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .sameSite("Strict")
                .build();
    }
}
