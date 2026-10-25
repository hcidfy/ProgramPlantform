package com.codelab.app.security;

import com.codelab.app.common.JsonMini;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 手写 HS256 JWT（PRD §13.1）。
 * <p>为什么不用 jjwt / nimbus：本项目只需 HS256 签发与校验，一个 HMAC + 一次 base64 足够；
 * 少一个第三方依赖就少一个升级面。密钥来自 APP_JWT_SECRET（≥32 字符）。
 */
public class JwtService {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    public record AccessToken(UUID userId, String role, UUID sessionId, long expiresAtEpochSecond) {
    }

    private final byte[] key;
    private final String issuer;
    private final Duration accessTtl;

    public JwtService(String secret, String issuer, Duration accessTtl) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET 长度必须 >= 32 字节");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.accessTtl = accessTtl;
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public String issue(UUID userId, String role, UUID sessionId) {
        long now = Instant.now().getEpochSecond();
        String payload = "{" + "\"iss\":" + JsonMini.quote(issuer)
                + ",\"sub\":\"" + userId + "\""
                + ",\"role\":\"" + role + "\""
                + ",\"sid\":\"" + sessionId + "\""
                + ",\"typ\":\"access\""
                + ",\"iat\":" + now
                + ",\"exp\":" + (now + accessTtl.toSeconds())
                + "}";
        String signingInput = ENC.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8))
                + "." + ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + ENC.encodeToString(sign(signingInput));
    }

    /** 校验签名与声明；任何一项不满足都返回 empty（调用方按 401 处理）。 */
    public Optional<AccessToken> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, DEC.decode(parts[2]))) {
                return Optional.empty();
            }
            String payload = new String(DEC.decode(parts[1]), StandardCharsets.UTF_8);
            if (!"access".equals(JsonMini.str(payload, "typ").orElse(""))) {
                return Optional.empty();
            }
            if (!issuer.equals(JsonMini.str(payload, "iss").orElse(""))) {
                return Optional.empty();
            }
            long exp = JsonMini.num(payload, "exp").orElse(0L);
            if (exp <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            UUID userId = UUID.fromString(JsonMini.str(payload, "sub").orElseThrow());
            UUID sessionId = UUID.fromString(JsonMini.str(payload, "sid").orElseThrow());
            String role = JsonMini.str(payload, "role").orElseThrow();
            return Optional.of(new AccessToken(userId, role, sessionId, exp));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 签名失败", ex);
        }
    }
}
