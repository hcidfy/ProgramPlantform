package com.codelab.app.knowledge;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 课程知识条目（PRD §15.4 / §8.3：无向量库，pg_trgm + 标签）。 */
@Repository
public class KnowledgeRepository {

    public record EntryRow(UUID id, UUID courseId, String title, String contentMd, List<String> tags, String status,
            OffsetDateTime publishedAt, OffsetDateTime updatedAt, Double score) {
    }

    private final JdbcClient jdbc;

    public KnowledgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID courseId, String title, String contentMd, List<String> tags, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO knowledge_entries (course_id, title, content_md, tags, created_by)
                        VALUES (:courseId, :title, :content, CAST(:tags AS text[]), :createdBy)
                        RETURNING id
                        """)
                .param("courseId", courseId)
                .param("title", title)
                .param("content", contentMd)
                .param("tags", toPgArray(tags))
                .param("createdBy", createdBy)
                .query(UUID.class)
                .single();
    }

    public void update(UUID id, String title, String contentMd, List<String> tags) {
        jdbc.sql("""
                        UPDATE knowledge_entries
                           SET title = :title, content_md = :content, tags = CAST(:tags AS text[]), updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("title", title)
                .param("content", contentMd)
                .param("tags", toPgArray(tags))
                .update();
    }

    public void publish(UUID id) {
        jdbc.sql("""
                        UPDATE knowledge_entries
                           SET status = 'published', published_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'draft'
                        """)
                .param("id", id)
                .update();
    }

    public int delete(UUID id) {
        return jdbc.sql("DELETE FROM knowledge_entries WHERE id = :id AND status = 'draft'")
                .param("id", id)
                .update();
    }

    public Optional<EntryRow> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, course_id, title, content_md, tags, status, published_at, updated_at, NULL::float8 AS score
                          FROM knowledge_entries WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new EntryRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("course_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("content_md"),
                        readTags(rs.getArray("tags")),
                        rs.getString("status"),
                        rs.getObject("published_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        null))
                .optional();
    }

    public List<EntryRow> listByCourse(UUID courseId, String status) {
        String sql = """
                SELECT id, course_id, title, content_md, tags, status, published_at, updated_at, NULL::float8 AS score
                  FROM knowledge_entries
                 WHERE course_id = :courseId
                """ + (status == null || status.isBlank() ? "" : " AND status = :status")
                + " ORDER BY updated_at DESC";
        var spec = jdbc.sql(sql).param("courseId", courseId);
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        return spec.query((rs, i) -> new EntryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("course_id", UUID.class),
                rs.getString("title"),
                rs.getString("content_md"),
                readTags(rs.getArray("tags")),
                rs.getString("status"),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                null)).list();
    }

    /**
     * 检索（PRD §8.3）：trigram 相似度 + ILIKE + 标签命中，取 Top N。
     * 知识量 <500 条时 SQL 足够，这也是删掉 pgvector 的代价与取舍。
     */
    public List<EntryRow> search(UUID courseId, String question, int limit) {
        String like = "%" + question.trim() + "%";
        return jdbc.sql("""
                        SELECT id, course_id, title, content_md, tags, status, published_at, updated_at,
                               similarity(coalesce(title, '') || ' ' || coalesce(content_md, ''), :q) AS score
                          FROM knowledge_entries
                         WHERE course_id = :courseId AND status = 'published'
                           AND (title ILIKE :like OR content_md ILIKE :like
                                OR EXISTS (SELECT 1 FROM unnest(tags) AS t
                                            WHERE t ILIKE :like OR :q ILIKE '%' || t || '%'))
                         ORDER BY score DESC, updated_at DESC
                         LIMIT :limit
                        """)
                .param("courseId", courseId)
                .param("q", question.trim())
                .param("like", like)
                .param("limit", limit)
                .query((rs, i) -> new EntryRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("course_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("content_md"),
                        readTags(rs.getArray("tags")),
                        rs.getString("status"),
                        rs.getObject("published_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getDouble("score")))
                .list();
    }

    public long countPublished(UUID courseId) {
        return jdbc.sql("SELECT count(*) FROM knowledge_entries WHERE course_id = :courseId AND status = 'published'")
                .param("courseId", courseId)
                .query(Long.class)
                .single();
    }

    private static List<String> readTags(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object value = array.getArray();
            if (value instanceof String[] items) {
                return List.of(items);
            }
            if (value instanceof Object[] items) {
                return java.util.Arrays.stream(items).map(String::valueOf).toList();
            }
            return List.of();
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** Java List → PG 数组字面量（tags 都是短词，按需转义引号与反斜杠）。 */
    private static String toPgArray(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String tag = tags.get(i) == null ? "" : tags.get(i).trim();
            sb.append('"').append(tag.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
