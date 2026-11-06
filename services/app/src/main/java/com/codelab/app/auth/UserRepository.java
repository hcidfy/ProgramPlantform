package com.codelab.app.auth;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** users 表访问。全部手写 SQL（不引 JPA/MyBatis，PRD §7.2）。 */
@Repository
public class UserRepository {

    public record UserRow(
            UUID id,
            String email,
            String displayName,
            String passwordHash,
            String role,
            String status,
            int failedLoginAttempts,
            OffsetDateTime lockedUntil,
            OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt) {

        public boolean isActive() {
            return "active".equals(status);
        }

        /** 锁定是否仍在生效。 */
        public boolean isLockedNow() {
            return lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        }
    }

    private static final String COLUMNS =
            "id, email, display_name, password_hash, role, status, failed_login_attempts, "
                    + "locked_until, last_login_at, created_at";

    private static final RowMapper<UserRow> MAPPER = (rs, i) -> new UserRow(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("status"),
            rs.getInt("failed_login_attempts"),
            rs.getObject("locked_until", OffsetDateTime.class),
            rs.getObject("last_login_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE email = :email")
                .param("email", email)
                .query(MAPPER)
                .optional();
    }

    public Optional<UserRow> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public boolean existsByEmail(String email) {
        return jdbc.sql("SELECT 1 FROM users WHERE email = :email")
                .param("email", email)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public UUID insert(String email, String displayName, String passwordHash, String role, String status) {
        return jdbc.sql("""
                        INSERT INTO users (email, display_name, password_hash, role, status)
                        VALUES (:email, :name, :hash, :role, :status)
                        RETURNING id
                        """)
                .param("email", email)
                .param("name", displayName)
                .param("hash", passwordHash)
                .param("role", role)
                .param("status", status)
                .query(UUID.class)
                .single();
    }

    public void markLoginSuccess(UUID id) {
        jdbc.sql("""
                        UPDATE users
                           SET failed_login_attempts = 0, locked_until = NULL,
                               last_login_at = now(), updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    /** 失败计数 +1；满 5 次锁定 10 分钟并把计数清零（PRD §13.1）。 */
    public void markLoginFailure(UUID id) {
        jdbc.sql("""
                        UPDATE users
                           SET failed_login_attempts = CASE WHEN failed_login_attempts + 1 >= 5
                                                            THEN 0 ELSE failed_login_attempts + 1 END,
                               locked_until = CASE WHEN failed_login_attempts + 1 >= 5
                                                   THEN now() + interval '10 minutes' ELSE locked_until END,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    public List<UserRow> list(String role, String q, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM users WHERE 1 = 1"
                + (role == null || role.isBlank() ? "" : " AND role = :role")
                + (q == null || q.isBlank() ? "" : " AND (email ILIKE :q OR display_name ILIKE :q)")
                + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset";
        var spec = jdbc.sql(sql).param("limit", limit).param("offset", offset);
        if (role != null && !role.isBlank()) {
            spec = spec.param("role", role);
        }
        if (q != null && !q.isBlank()) {
            spec = spec.param("q", "%" + q.trim() + "%");
        }
        return spec.query(MAPPER).list();
    }

    public long count(String role, String q) {
        String sql = "SELECT count(*) FROM users WHERE 1 = 1"
                + (role == null || role.isBlank() ? "" : " AND role = :role")
                + (q == null || q.isBlank() ? "" : " AND (email ILIKE :q OR display_name ILIKE :q)");
        var spec = jdbc.sql(sql);
        if (role != null && !role.isBlank()) {
            spec = spec.param("role", role);
        }
        if (q != null && !q.isBlank()) {
            spec = spec.param("q", "%" + q.trim() + "%");
        }
        return spec.query(Long.class).single();
    }

    public List<UserRow> listByRole(String role) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE role = :role ORDER BY display_name")
                .param("role", role)
                .query(MAPPER)
                .list();
    }

    public int setStatus(UUID id, String status) {
        return jdbc.sql("UPDATE users SET status=:status, updated_at=now() WHERE id=:id")
                .param("id", id).param("status", status).update();
    }
}
