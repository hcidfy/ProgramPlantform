package com.codelab.app.file;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** files 表（PRD §14.3 / §14.7）。 */
@Repository
public class FileRepository {

    public record FileRow(UUID id, UUID ownerId, UUID problemId, String purpose, String objectKey,
            String originalName, String mime, int sizeBytes, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public FileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID ownerId, UUID problemId, String purpose, String objectKey, String originalName,
            String mime, int sizeBytes) {
        return jdbc.sql("""
                        INSERT INTO files (owner_id, problem_id, purpose, object_key, original_name, mime, size_bytes)
                        VALUES (:owner, :problem, :purpose, :key, :name, :mime, :size)
                        RETURNING id
                        """)
                .param("owner", ownerId)
                .param("problem", problemId)
                .param("purpose", purpose)
                .param("key", objectKey)
                .param("name", originalName)
                .param("mime", mime)
                .param("size", sizeBytes)
                .query(UUID.class)
                .single();
    }

    public Optional<FileRow> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, owner_id, problem_id, purpose, object_key, original_name, mime, size_bytes, created_at
                          FROM files WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new FileRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("problem_id", UUID.class),
                        rs.getString("purpose"),
                        rs.getString("object_key"),
                        rs.getString("original_name"),
                        rs.getString("mime"),
                        rs.getInt("size_bytes"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }
}
