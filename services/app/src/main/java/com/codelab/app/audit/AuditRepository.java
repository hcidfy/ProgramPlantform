package com.codelab.app.audit;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    public record AuditRow(long id, UUID actorId, String actorName, String actorEmail, String action,
            String resource, String detail, String ip, OffsetDateTime createdAt) {
    }

    public record AuditPage(java.util.List<AuditRow> items, long total, int page, int size) {
    }

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AuditPage search(String q, String action, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (q != null && !q.isBlank()) where.append(" AND (u.display_name ILIKE :q OR u.email::text ILIKE :q)");
        if (action != null && !action.isBlank()) where.append(" AND al.action ILIKE :action");
        if (from != null) where.append(" AND al.created_at >= :from");
        if (to != null) where.append(" AND al.created_at < :to");

        StatementSpec count = bind(jdbc.sql("SELECT count(*) FROM audit_logs al LEFT JOIN users u ON u.id=al.actor_id"
                + where), q, action, from, to);
        long total = count.query(Long.class).single();

        String sql = """
                SELECT al.id, al.actor_id, u.display_name actor_name, u.email::text actor_email,
                       al.action, al.resource, al.detail::text detail, al.ip::text ip, al.created_at
                  FROM audit_logs al LEFT JOIN users u ON u.id=al.actor_id
                """ + where + " ORDER BY al.created_at DESC, al.id DESC LIMIT :limit OFFSET :offset";
        var rows = bind(jdbc.sql(sql), q, action, from, to)
                .param("limit", size)
                .param("offset", (page - 1) * size)
                .query((rs, i) -> new AuditRow(rs.getLong("id"), rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_name"), rs.getString("actor_email"), rs.getString("action"),
                        rs.getString("resource"), rs.getString("detail"), rs.getString("ip"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        return new AuditPage(rows, total, page, size);
    }

    private static StatementSpec bind(StatementSpec spec, String q, String action,
            OffsetDateTime from, OffsetDateTime to) {
        if (q != null && !q.isBlank()) spec = spec.param("q", "%" + q.trim() + "%");
        if (action != null && !action.isBlank()) spec = spec.param("action", "%" + action.trim() + "%");
        if (from != null) spec = spec.param("from", from);
        if (to != null) spec = spec.param("to", to);
        return spec;
    }
}
