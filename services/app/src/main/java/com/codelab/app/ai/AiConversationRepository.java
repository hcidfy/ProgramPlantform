package com.codelab.app.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** AI 会话与消息（PRD §15.6）。 */
@Repository
public class AiConversationRepository {

    public record ConversationRow(UUID id, UUID userId, String kind, UUID courseId, UUID problemId,
            UUID submissionId, Integer level, java.time.OffsetDateTime createdAt) {
    }

    public record MessageRow(UUID id, String role, String text, Integer tokens,
            java.time.OffsetDateTime createdAt) {
    }

    public record QaRecord(UUID conversationId, UUID studentId, String studentName, String studentEmail,
            String question, String answer, java.time.OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public AiConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID userId, String kind, UUID courseId, UUID problemId, UUID submissionId, Integer level) {
        return jdbc.sql("""
                        INSERT INTO ai_conversations (user_id, kind, course_id, problem_id, submission_id, level)
                        VALUES (:userId, :kind, :courseId, :problemId, :submissionId, :level)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("kind", kind)
                .param("courseId", courseId)
                .param("problemId", problemId)
                .param("submissionId", submissionId)
                .param("level", level)
                .query(UUID.class)
                .single();
    }

    public void insertMessage(UUID conversationId, String role, String contentJson, String text, int tokens) {
        jdbc.sql("""
                        INSERT INTO ai_messages (conversation_id, role, content, text, tokens)
                        VALUES (:cid, :role, CAST(:content AS jsonb), :text, :tokens)
                        """)
                .param("cid", conversationId)
                .param("role", role)
                .param("content", contentJson)
                .param("text", text)
                .param("tokens", tokens)
                .update();
    }

    /** 思路提示"同一题同一级不重复计费"：找已存的那一条。 */
    public Optional<String> findHintAnswer(UUID userId, UUID problemId, int level) {
        return jdbc.sql("""
                        SELECT m.text
                          FROM ai_conversations c
                          JOIN ai_messages m ON m.conversation_id = c.id AND m.role = 'assistant'
                         WHERE c.user_id = :userId AND c.problem_id = :problemId
                           AND c.kind = 'hint' AND c.level = :level
                         ORDER BY m.created_at DESC
                         LIMIT 1
                        """)
                .param("userId", userId)
                .param("problemId", problemId)
                .param("level", level)
                .query(String.class)
                .optional();
    }

    public int countHintLevels(UUID userId, UUID problemId) {
        return jdbc.sql("""
                        SELECT count(DISTINCT level) FROM ai_conversations
                         WHERE user_id = :userId AND problem_id = :problemId AND kind = 'hint' AND level IS NOT NULL
                        """)
                .param("userId", userId)
                .param("problemId", problemId)
                .query(Integer.class)
                .single();
    }

    public List<ConversationRow> listChatConversations(UUID userId, UUID courseId, int limit) {
        return jdbc.sql("""
                        SELECT id, user_id, kind, course_id, problem_id, submission_id, level, created_at
                          FROM ai_conversations
                         WHERE user_id = :userId AND kind = 'chat' AND course_id = :courseId
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("userId", userId)
                .param("courseId", courseId)
                .param("limit", limit)
                .query((rs, i) -> new ConversationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("kind"),
                        rs.getObject("course_id", UUID.class),
                        rs.getObject("problem_id", UUID.class),
                        rs.getObject("submission_id", UUID.class),
                        rs.getObject("level", Integer.class),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)))
                .list();
    }

    public Optional<ConversationRow> findById(UUID conversationId) {
        return jdbc.sql("""
                        SELECT id, user_id, kind, course_id, problem_id, submission_id, level, created_at
                          FROM ai_conversations WHERE id = :id
                        """)
                .param("id", conversationId)
                .query((rs, i) -> new ConversationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("kind"),
                        rs.getObject("course_id", UUID.class),
                        rs.getObject("problem_id", UUID.class),
                        rs.getObject("submission_id", UUID.class),
                        rs.getObject("level", Integer.class),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)))
                .optional();
    }

    public List<MessageRow> listMessages(UUID conversationId, int limit) {
        return jdbc.sql("""
                        SELECT id, role, text, tokens, created_at
                          FROM ai_messages
                         WHERE conversation_id = :cid
                         ORDER BY created_at ASC
                         LIMIT :limit
                        """)
                .param("cid", conversationId)
                .param("limit", limit)
                .query((rs, i) -> new MessageRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("role"),
                        rs.getString("text"),
                        rs.getObject("tokens", Integer.class),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)))
                .list();
    }

    /** 教师看本班学生的知识问答记录（FR-QA-04）。 */
    public List<QaRecord> listQaForTeachingClass(UUID teachingClassId, UUID studentId, int limit, int offset) {
        return jdbc.sql("""
                        SELECT c.id AS conversation_id, u.id AS student_id, u.display_name, u.email,
                               q.text AS question, a.text AS answer, c.created_at
                          FROM ai_conversations c
                          JOIN users u ON u.id = c.user_id
                          JOIN teaching_classes tc ON tc.id = :tcId
                          JOIN enrollments e ON e.class_id = tc.class_id AND e.student_id = u.id
                          LEFT JOIN LATERAL (
                               SELECT text FROM ai_messages WHERE conversation_id = c.id AND role = 'user'
                               ORDER BY created_at LIMIT 1) q ON true
                          LEFT JOIN LATERAL (
                               SELECT text FROM ai_messages WHERE conversation_id = c.id AND role = 'assistant'
                               ORDER BY created_at DESC LIMIT 1) a ON true
                         WHERE c.kind = 'chat'
                           AND (:studentId::uuid IS NULL OR u.id = :studentId::uuid)
                         ORDER BY c.created_at DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("tcId", teachingClassId)
                .param("studentId", studentId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> new QaRecord(
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("student_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)))
                .list();
    }
}
