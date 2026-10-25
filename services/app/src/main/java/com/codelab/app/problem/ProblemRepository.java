package com.codelab.app.problem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 题库 + 用例（PRD §14.2）。 */
@Repository
public class ProblemRepository {

    public record ProblemRow(
            UUID id, UUID courseId, String title, String statementMd, String language, String difficulty,
            int timeLimitMs, int memLimitKb, String initialCode, String status, int version,
            UUID previousVersionId, UUID createdBy, OffsetDateTime publishedAt, OffsetDateTime createdAt) {

        public boolean isDraft() {
            return "draft".equals(status);
        }

        public boolean isPublished() {
            return "published".equals(status);
        }
    }

    public record TestCaseRow(UUID id, UUID problemId, int seq, String input, String expected, boolean isHidden,
            int score) {
    }

    public record ProblemInAssignment(UUID problemId, int seq, int score, String title, String language,
            String difficulty, int timeLimitMs, int memLimitKb, String status) {
    }

    private static final String COLS = "id, course_id, title, statement_md, language, difficulty, time_limit_ms, "
            + "mem_limit_kb, initial_code, status, version, previous_version_id, created_by, published_at, created_at";

    private static final RowMapper<ProblemRow> MAPPER = (rs, i) -> new ProblemRow(
            rs.getObject("id", UUID.class),
            rs.getObject("course_id", UUID.class),
            rs.getString("title"),
            rs.getString("statement_md"),
            rs.getString("language"),
            rs.getString("difficulty"),
            rs.getInt("time_limit_ms"),
            rs.getInt("mem_limit_kb"),
            rs.getString("initial_code"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getObject("previous_version_id", UUID.class),
            rs.getObject("created_by", UUID.class),
            rs.getObject("published_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<TestCaseRow> CASE_MAPPER = (rs, i) -> new TestCaseRow(
            rs.getObject("id", UUID.class),
            rs.getObject("problem_id", UUID.class),
            rs.getInt("seq"),
            rs.getString("input"),
            rs.getString("expected"),
            rs.getBoolean("is_hidden"),
            rs.getInt("score"));

    private final JdbcClient jdbc;

    public ProblemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProblemRow> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM problems WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<ProblemRow> listByCourses(List<UUID> courseIds, String status, String q, int limit, int offset) {
        if (courseIds == null || courseIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT " + COLS + " FROM problems WHERE course_id IN (:courseIds)"
                + (status == null || status.isBlank() ? "" : " AND status = :status")
                + (q == null || q.isBlank() ? "" : " AND (title ILIKE :q OR statement_md ILIKE :q)")
                + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset";
        var spec = jdbc.sql(sql).param("courseIds", courseIds).param("limit", limit).param("offset", offset);
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        if (q != null && !q.isBlank()) {
            spec = spec.param("q", "%" + q.trim() + "%");
        }
        return spec.query(MAPPER).list();
    }

    public long countByCourses(List<UUID> courseIds, String status, String q) {
        if (courseIds == null || courseIds.isEmpty()) {
            return 0;
        }
        String sql = "SELECT count(*) FROM problems WHERE course_id IN (:courseIds)"
                + (status == null || status.isBlank() ? "" : " AND status = :status")
                + (q == null || q.isBlank() ? "" : " AND (title ILIKE :q OR statement_md ILIKE :q)");
        var spec = jdbc.sql(sql).param("courseIds", courseIds);
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        if (q != null && !q.isBlank()) {
            spec = spec.param("q", "%" + q.trim() + "%");
        }
        return spec.query(Long.class).single();
    }

    public UUID insert(UUID courseId, String title, String statementMd, String language, String difficulty,
            int timeLimitMs, int memLimitKb, String initialCode, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO problems (course_id, title, statement_md, language, difficulty,
                                              time_limit_ms, mem_limit_kb, initial_code, created_by)
                        VALUES (:courseId, :title, :statement, :language, :difficulty,
                                :timeLimit, :memLimit, :initialCode, :createdBy)
                        RETURNING id
                        """)
                .param("courseId", courseId)
                .param("title", title)
                .param("statement", statementMd)
                .param("language", language)
                .param("difficulty", difficulty)
                .param("timeLimit", timeLimitMs)
                .param("memLimit", memLimitKb)
                .param("initialCode", initialCode)
                .param("createdBy", createdBy)
                .query(UUID.class)
                .single();
    }

    public void update(UUID id, String title, String statementMd, String language, String difficulty,
            int timeLimitMs, int memLimitKb, String initialCode) {
        jdbc.sql("""
                        UPDATE problems
                           SET title = :title, statement_md = :statement, language = :language,
                               difficulty = :difficulty, time_limit_ms = :timeLimit,
                               mem_limit_kb = :memLimit, initial_code = :initialCode, updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("title", title)
                .param("statement", statementMd)
                .param("language", language)
                .param("difficulty", difficulty)
                .param("timeLimit", timeLimitMs)
                .param("memLimit", memLimitKb)
                .param("initialCode", initialCode)
                .update();
    }

    public void publish(UUID id) {
        jdbc.sql("""
                        UPDATE problems SET status = 'published', published_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'draft'
                        """)
                .param("id", id)
                .update();
    }

    public int delete(UUID id) {
        return jdbc.sql("DELETE FROM problems WHERE id = :id AND status = 'draft'").param("id", id).update();
    }

    /** 另存为新版本（FR-PRB-04）：复制题面与用例，version+1，回到 draft。 */
    public UUID insertNewVersion(UUID sourceId, UUID createdBy) {
        UUID newId = jdbc.sql("""
                        INSERT INTO problems (course_id, title, statement_md, language, difficulty, time_limit_ms,
                                              mem_limit_kb, initial_code, status, version, previous_version_id, created_by)
                        SELECT course_id, title, statement_md, language, difficulty, time_limit_ms,
                               mem_limit_kb, initial_code, 'draft', version + 1, id, :createdBy
                          FROM problems WHERE id = :id
                        RETURNING id
                        """)
                .param("id", sourceId)
                .param("createdBy", createdBy)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO test_cases (problem_id, seq, input, expected, is_hidden, score)
                        SELECT :newId, seq, input, expected, is_hidden, score FROM test_cases WHERE problem_id = :oldId
                        """)
                .param("newId", newId)
                .param("oldId", sourceId)
                .update();
        return newId;
    }

    // ---------------- 用例 ----------------

    public List<TestCaseRow> listTestCases(UUID problemId) {
        return jdbc.sql("SELECT id, problem_id, seq, input, expected, is_hidden, score "
                        + "FROM test_cases WHERE problem_id = :problemId ORDER BY seq")
                .param("problemId", problemId)
                .query(CASE_MAPPER)
                .list();
    }

    public void deleteTestCases(UUID problemId) {
        jdbc.sql("DELETE FROM test_cases WHERE problem_id = :problemId").param("problemId", problemId).update();
    }

    public void insertTestCase(UUID problemId, int seq, String input, String expected, boolean isHidden, int score) {
        jdbc.sql("""
                        INSERT INTO test_cases (problem_id, seq, input, expected, is_hidden, score)
                        VALUES (:problemId, :seq, :input, :expected, :isHidden, :score)
                        """)
                .param("problemId", problemId)
                .param("seq", seq)
                .param("input", input)
                .param("expected", expected)
                .param("isHidden", isHidden)
                .param("score", score)
                .update();
    }

    public long countTestCases(UUID problemId) {
        return jdbc.sql("SELECT count(*) FROM test_cases WHERE problem_id = :problemId")
                .param("problemId", problemId)
                .query(Long.class)
                .single();
    }

    // ---------------- 可见性 ----------------

    /**
     * 学生能否看这道题：题目已发布，且该题被"本班某个已发布作业"包含（PRD §13.4 同一思路，越权按不存在处理）。
     */
    public boolean isVisibleToStudent(UUID problemId, UUID studentId) {
        return jdbc.sql("""
                        SELECT 1
                          FROM problems p
                          JOIN assignment_problems ap ON ap.problem_id = p.id
                          JOIN assignments a ON a.id = ap.assignment_id AND a.status = 'published'
                          JOIN teaching_classes tc ON tc.id = a.teaching_class_id
                          JOIN enrollments e ON e.class_id = tc.class_id AND e.student_id = :studentId
                         WHERE p.id = :problemId AND p.status = 'published'
                         LIMIT 1
                        """)
                .param("problemId", problemId)
                .param("studentId", studentId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public List<ProblemInAssignment> listProblemsInAssignment(UUID assignmentId) {
        return jdbc.sql("""
                        SELECT ap.problem_id, ap.seq, ap.score, p.title, p.language, p.difficulty,
                               p.time_limit_ms, p.mem_limit_kb, p.status
                          FROM assignment_problems ap
                          JOIN problems p ON p.id = ap.problem_id
                         WHERE ap.assignment_id = :assignmentId
                         ORDER BY ap.seq, p.title
                        """)
                .param("assignmentId", assignmentId)
                .query((rs, i) -> new ProblemInAssignment(
                        rs.getObject("problem_id", UUID.class),
                        rs.getInt("seq"),
                        rs.getInt("score"),
                        rs.getString("title"),
                        rs.getString("language"),
                        rs.getString("difficulty"),
                        rs.getInt("time_limit_ms"),
                        rs.getInt("mem_limit_kb"),
                        rs.getString("status")))
                .list();
    }
}
