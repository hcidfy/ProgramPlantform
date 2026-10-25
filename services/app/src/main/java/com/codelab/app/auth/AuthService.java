package com.codelab.app.auth;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.common.ApiException;
import com.codelab.app.config.AppProperties;
import com.codelab.app.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 账号与会话（FR-ACC-01/02）。业务规则：注册恒为 student、5 次错锁 10 分钟、refresh 轮换。 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    public record UserView(UUID id, String email, String displayName, String role, String status) {
        public static UserView of(UserRepository.UserRow row) {
            return new UserView(row.id(), row.email(), row.displayName(), row.role(), row.status());
        }
    }

    public record TokenPair(String accessToken, long expiresInSeconds, String refreshToken,
            Instant refreshExpiresAt, UserView user) {
    }

    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AppProperties props;
    private final AuditService audit;

    public AuthService(UserRepository users, AuthSessionRepository sessions, PasswordEncoder encoder,
            JwtService jwt, AppProperties props, AuditService audit) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
        this.audit = audit;
    }

    // ---------------- 注册 / 建号 ----------------

    public UserView register(String email, String displayName, String password) {
        return createUser(null, email, displayName, "student", password, AuditActions.REGISTER);
    }

    /** 管理员建号与注册共用；role 由调用方决定（注册恒 student）。 */
    public UserView createUser(UUID actorId, String email, String displayName, String role, String password,
            String auditAction) {
        String normalized = normalizeEmail(email);
        checkPassword(password);
        if (users.existsByEmail(normalized)) {
            throw ApiException.conflict("EMAIL_EXISTS", "邮箱已存在");
        }
        UUID id = users.insert(normalized, displayName.trim(), encoder.encode(password), role, "active");
        audit.record(actorId, auditAction, "user:" + id,
                Map.of("email", normalized, "role", role, "displayName", displayName.trim()));
        return users.findById(id).map(UserView::of).orElseThrow();
    }

    // ---------------- 登录 ----------------

    public TokenPair login(String email, String password, String device) {
        String normalized = normalizeEmail(email);
        UserRepository.UserRow user = users.findByEmail(normalized).orElse(null);
        if (user == null) {
            audit.record(null, AuditActions.LOGIN_FAILED, null, Map.of("email", normalized, "reason", "no_such_user"));
            throw ApiException.unauthorized("邮箱或密码错误");
        }
        if (user.isLockedNow()) {
            audit.record(user.id(), AuditActions.LOGIN_FAILED, "user:" + user.id(), Map.of("reason", "locked"));
            throw ApiException.locked("密码错误次数过多，账号已锁定 10 分钟");
        }
        if (!user.isActive()) {
            audit.record(user.id(), AuditActions.LOGIN_FAILED, "user:" + user.id(),
                    Map.of("reason", "status_" + user.status()));
            throw ApiException.forbidden("账号状态不可用：" + user.status());
        }
        if (!encoder.matches(password, user.passwordHash())) {
            users.markLoginFailure(user.id());
            audit.record(user.id(), AuditActions.LOGIN_FAILED, "user:" + user.id(), Map.of("reason", "bad_password"));
            throw ApiException.unauthorized("邮箱或密码错误");
        }
        users.markLoginSuccess(user.id());
        TokenPair pair = issue(user.id(), user.role(), device);
        audit.record(user.id(), AuditActions.LOGIN_SUCCESS, "user:" + user.id(),
                Map.of("role", user.role(), "device", device == null ? "" : device));
        return pair;
    }

    // ---------------- 刷新 / 登出 ----------------

    public TokenPair refresh(String refreshToken, String device) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("缺少 refresh token，请重新登录");
        }
        AuthSessionRepository.SessionRow session = sessions.findActiveByRefreshHash(sha256(refreshToken))
                .orElseThrow(() -> ApiException.unauthorized("登录状态已失效，请重新登录"));
        UserRepository.UserRow user = users.findById(session.userId())
                .orElseThrow(() -> ApiException.unauthorized("账号不存在"));
        if (!user.isActive()) {
            throw ApiException.forbidden("账号状态不可用：" + user.status());
        }
        // 轮换：旧 refresh 立即作废，降低被重放的价值
        sessions.revoke(session.id());
        return issue(user.id(), user.role(), device);
    }

    public void logout(UUID userId, UUID sessionId, String refreshToken) {
        if (sessionId != null) {
            sessions.revoke(sessionId);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessions.revokeByRefreshHash(sha256(refreshToken));
        }
        if (userId != null) {
            audit.record(userId, AuditActions.LOGOUT, "user:" + userId, Map.of());
        }
    }

    public UserView me(UUID userId) {
        return users.findById(userId)
                .map(UserView::of)
                .orElseThrow(() -> ApiException.unauthorized("账号不存在"));
    }

    // ---------------- 内部 ----------------

    private TokenPair issue(UUID userId, String role, String device) {
        String refreshPlain = randomToken();
        Instant refreshExpiresAt = Instant.now().plus(props.jwt().refreshTtl());
        UUID sessionId = sessions.create(userId, sha256(refreshPlain), device, refreshExpiresAt);
        String accessToken = jwt.issue(userId, role, sessionId);
        UserRepository.UserRow row = users.findById(userId).orElseThrow();
        return new TokenPair(accessToken, jwt.accessTtl().toSeconds(), refreshPlain, refreshExpiresAt, UserView.of(row));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** 密码策略（PRD §13.1）：8~64 字符，须同时含字母与数字。 */
    public static void checkPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw ApiException.validation("密码长度须为 8~64 个字符");
        }
        if (!HAS_LETTER.matcher(password).find() || !HAS_DIGIT.matcher(password).find()) {
            throw ApiException.validation("密码须同时包含字母与数字");
        }
    }

    public static String truncateDevice(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        String trimmed = userAgent.trim();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180);
    }

    public Duration refreshTtl() {
        return props.jwt().refreshTtl();
    }
}
