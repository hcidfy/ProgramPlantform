package com.codelab.app.assignment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 作业（PRD §14.4）。 */
@Repository
public class AssignmentRepository {

    public record AssignmentRow(UUID id, UUID teachingClassId, String title, String description,
            OffsetDateTime deadline, String status, UUID createdBy, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        public boolean isPublished() {
            return "published".equals(status);
        }

        public boolean isDraft() {
            return "draft".equals(status);
        }
    }

    public record ProblemScore(UUID problemId, int score) {
    }

    public record ProblemStatus(UUID problemId, boolean submitted, boolean passed) {
    }

    public record Progress(long expectedCount, long submittedCount, long passedCount, long submissionCount,
            long acceptedCount) {
    }

    private static final String COLS = "id, teaching_class_id, title, description, deadline, status, created_by, "
            + "created_at, updated_at";

    /** 同一组列，但带 a. 前缀（学生视角要 join）。 */
    private static final String COLS_A = "a.id, a.teaching_class_id, a.title, a.description, a.deadline, "
            + "a.status, a.created_by, a.created_at, a.updated_at";

    private static final RowMapper<AssignmentRow> MAPPER = (rs, i) -> new AssignmentRow(
            rs.getObject("id", UUID.class),
            rs.getObject("teaching_class_id", UUID.class),
            rs.getString("title"),
            rs.getString("description"),
            rs.getObject("deadline", OffsetDateTime.class),
            rs.getString("status"),
            rs.getObject("created_by", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public AssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AssignmentRow> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM assignments WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<AssignmentRow> listByTeachingClass(UUID teachingClassId) {
        return jdbc.sql("SELECT " + COLS + " FROM assignments WHERE teaching_class_id = :tcId "
                        + "ORDER BY created_at DESC")
                .param("tcId", teachingClassId)
                .query(MAPPER)
                .list();
    }

    public UUID insert(UUID teachingClassId, String title, String description, OffsetDateTime deadline,
            UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO assignments (teaching_class_id, title, description, deadline, created_by)
                        VALUES (:tcId, :title, :description, :deadline, :createdBy)
                        RETURNING id
                        """)
                .param("tcId", teachingClassId)
                .param("title", title)
                .param("description", description)
                .param("deadline", deadline)
                .param("createdBy", createdBy)
                .query(UUID.class)
                .single();
    }

    public void update(UUID id, String title, String description, OffsetDateTime deadline) {
        jdbc.sql("""
                        UPDATE assignments SET title = :title, description = :description,
                               deadline = :deadline, updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("title", title)
                .param("description", description)
                .param("deadline", deadline)
                .update();
    }

    public void publish(UUID id) {
        jdbc.sql("UPDATE assignments SET status = 'published', updated_at = now() WHERE id = :id AND status = 'draft'")
                .param("id", id)
                .update();
    }

    public int delete(UUID id) {
        return jdbc.sql("DELETE FROM assignments WHERE id = :id AND status = 'draft'").param("id", id).update();
    }

    public void deleteProblems(UUID assignmentId) {
        jdbc.sql("DELETE FROM assignment_problems WHERE assignment_id = :id").param("id", assignmentId).update();
    }

    public void insertProblem(UUID assignmentId, UUID problemId, int seq, int score) {
        jdbc.sql("""
                        INSERT INTO assignment_problems (assignment_id, problem_id, seq, score)
                        VALUES (:assignmentId, :problemId, :seq, :score)
                        """)
                .param("assignmentId", assignmentId)
                .param("problemId", problemId)
                .param("seq", seq)
                .param("score", score)
                .update();
    }

    // ---------------- 学生视角 ----------------

    public List<AssignmentRow> listPublishedForStudent(UUID studentId) {
        return jdbc.sql("SELECT " + COLS_A + """
                          FROM assignments a
                          JOIN teaching_classes tc ON tc.id = a.teaching_class_id
                          JOIN enrollments e ON e.class_id = tc.class_id
                         WHERE e.student_id = :studentId AND a.status = 'published'
                         ORDER BY a.deadline NULLS LAST, a.created_at DESC
                        """)
                .param("studentId", studentId)
                .query(MAPPER)
                .list();
    }

    public Optional<AssignmentRow> findPublishedForStudent(UUID assignmentId, UUID studentId) {
        return jdbc.sql("SELECT " + COLS_A + """
                          FROM assignments a
                          JOIN teaching_classes tc ON tc.id = a.teaching_class_id
                          JOIN enrollments e ON e.class_id = tc.class_id
                         WHERE a.id = :assignmentId AND e.student_id = :studentId AND a.status = 'published'
                        """)
                .param("assignmentId", assignmentId)
                .param("studentId", studentId)
                .query(MAPPER)
                .optional();
    }

    /** 学生在某作业里每道题的状态（提交过 / 通过）。 */
    public List<ProblemStatus> problemStatuses(UUID assignmentId, UUID studentId) {
        return jdbc.sql("""
                        SELECT ap.problem_id,
                               count(s.id) > 0 AS submitted,
                               coalesce(bool_or(s.result = 'accepted'), false) AS passed
                          FROM assignment_problems ap
                          LEFT JOIN submissions s
                                 ON s.assignment_id = ap.assignment_id
                                AND s.problem_id = ap.problem_id
                                AND s.user_id = :studentId
                         WHERE ap.assignment_id = :assignmentId
                         GROUP BY ap.problem_id
                        """)
                .param("assignmentId", assignmentId)
                .param("studentId", studentId)
                .query((rs, i) -> new ProblemStatus(
                        rs.getObject("problem_id", UUID.class),
                        rs.getBoolean("submitted"),
                        rs.getBoolean("passed")))
                .list();
    }

    // ---------------- 教师进度（FR-TCH-03） ----------------

    public long countExpectedUsers(UUID assignmentId) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM assignments a
                          JOIN teaching_classes tc ON tc.id = a.teaching_class_id
                          JOIN enrollments e ON e.class_id = tc.class_id
                         WHERE a.id = :id
                        """)
                .param("id", assignmentId)
                .query(Long.class)
                .single();
    }

    public long countSubmittedUsers(UUID assignmentId) {
        return jdbc.sql("SELECT count(DISTINCT user_id) FROM submissions WHERE assignment_id = :id")
                .param("id", assignmentId)
                .query(Long.class)
                .single();
    }

    /** 通过人数 = 该班学生中"作业里每道题都有 accepted 提交"的人数（作业无用例题目时记 0）。 */
    public long countPassedUsers(UUID assignmentId) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM assignments a
                          JOIN teaching_classes tc ON tc.id = a.teaching_class_id
                          JOIN enrollments e ON e.class_id = tc.class_id
                         WHERE a.id = :id
                           AND EXISTS (SELECT 1 FROM assignment_problems ap WHERE ap.assignment_id = a.id)
                           AND NOT EXISTS (
                                 SELECT 1 FROM assignment_problems ap
                                  WHERE ap.assignment_id = a.id
                                    AND NOT EXISTS (
                                          SELECT 1 FROM submissions s
                                           WHERE s.assignment_id = a.id
                                             AND s.problem_id = ap.problem_id
                                             AND s.user_id = e.student_id
                                             AND s.result = 'accepted'))
                        """)
                .param("id", assignmentId)
                .query(Long.class)
                .single();
    }

    public long countSubmissions(UUID assignmentId) {
        return jdbc.sql("SELECT count(*) FROM submissions WHERE assignment_id = :id")
                .param("id", assignmentId)
                .query(Long.class)
                .single();
    }

    public long countAcceptedSubmissions(UUID assignmentId) {
        return jdbc.sql("SELECT count(*) FROM submissions WHERE assignment_id = :id AND result = 'accepted'")
                .param("id", assignmentId)
                .query(Long.class)
                .single();
    }
}
