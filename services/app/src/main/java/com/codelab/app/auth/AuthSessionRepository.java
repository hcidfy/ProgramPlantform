package com.codelab.app.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** auth_sessions：refresh token 只存 SHA-256 摘要；撤销即置 revoked_at。 */
@Repository
public class AuthSessionRepository {

    public record SessionRow(UUID id, UUID userId, String refreshHash, OffsetDateTime expiresAt,
            OffsetDateTime revokedAt) {
    }

    private final JdbcClient jdbc;

    public AuthSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID userId, String refreshHash, String device, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO auth_sessions (user_id, refresh_hash, device, expires_at)
                        VALUES (:userId, :hash, :device, :expiresAt)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("hash", refreshHash)
                .param("device", device)
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC))
                .query(UUID.class)
                .single();
    }

    public Optional<SessionRow> findActiveByRefreshHash(String refreshHash) {
        return jdbc.sql("""
                        SELECT id, user_id, refresh_hash, expires_at, revoked_at
                          FROM auth_sessions
                         WHERE refresh_hash = :hash AND revoked_at IS NULL AND expires_at > now()
                        """)
                .param("hash", refreshHash)
                .query((rs, i) -> new SessionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("refresh_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class)))
                .optional();
    }

    /** 会话是否仍然有效：登出（撤销）后 access token 立刻失效。 */
    public boolean isActive(UUID sessionId) {
        return jdbc.sql("SELECT 1 FROM auth_sessions WHERE id = :id AND revoked_at IS NULL AND expires_at > now()")
                .param("id", sessionId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public void revoke(UUID sessionId) {
        jdbc.sql("UPDATE auth_sessions SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", sessionId)
                .update();
    }

    public void revokeByRefreshHash(String refreshHash) {
        jdbc.sql("UPDATE auth_sessions SET revoked_at = now() WHERE refresh_hash = :hash AND revoked_at IS NULL")
                .param("hash", refreshHash)
                .update();
    }
}
