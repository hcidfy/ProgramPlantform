package com.codelab.app.submission;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 提交与判题明细（PRD §14.5 / §9）。 */
@Repository
public class SubmissionRepository {

    public record SubmissionRow(UUID id, UUID userId, UUID problemId, UUID assignmentId, String language,
            String sourceCode, String status, String result, int passedCases, int totalCases, int score,
            String compileOutput, UUID rejudgeOf, OffsetDateTime createdAt, OffsetDateTime finishedAt) {
    }

    /** 教师视角：多带学生姓名。 */
    public record TeacherSubmissionRow(UUID id, UUID userId, String studentName, String studentEmail,
            UUID problemId, String problemTitle, String language, String status, String result,
            int passedCases, int totalCases, int score, String sourceCode, OffsetDateTime createdAt) {
    }

    public record CaseRow(int caseSeq, String status, int timeMs, Integer exitCode, String inputExcerpt,
            String expectedExcerpt, String actualExcerpt, boolean isHidden) {
    }

    private static final String COLS = "id, user_id, problem_id, assignment_id, language, source_code, status, "
            + "result, passed_cases, total_cases, score, compile_output, rejudge_of, created_at, finished_at";

    private static final RowMapper<SubmissionRow> MAPPER = (rs, i) -> new SubmissionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("problem_id", UUID.class),
            rs.getObject("assignment_id", UUID.class),
            rs.getString("language"),
            rs.getString("source_code"),
            rs.getString("status"),
            rs.getString("result"),
            rs.getInt("passed_cases"),
            rs.getInt("total_cases"),
            rs.getInt("score"),
            rs.getString("compile_output"),
            rs.getObject("rejudge_of", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public SubmissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SubmissionRow> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM submissions WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public UUID insert(UUID userId, UUID problemId, UUID assignmentId, String language, String sourceCode,
            String idempotencyKey, UUID rejudgeOf) {
        return jdbc.sql("""
                        INSERT INTO submissions (user_id, problem_id, assignment_id, language, source_code,
                                                 idempotency_key, rejudge_of)
                        VALUES (:userId, :problemId, :assignmentId, :language, :source,
                                :idempotencyKey, :rejudgeOf)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("problemId", problemId)
                .param("assignmentId", assignmentId)
                .param("language", language)
                .param("source", sourceCode)
                .param("idempotencyKey", idempotencyKey)
                .param("rejudgeOf", rejudgeOf)
                .query(UUID.class)
                .single();
    }

    public void markJudging(UUID id) {
        jdbc.sql("UPDATE submissions SET status = 'judging' WHERE id = :id AND status = 'queued'")
                .param("id", id)
                .update();
    }

    public void finish(UUID id, String result, int passedCases, int totalCases, int score, String compileOutput) {
        jdbc.sql("""
                        UPDATE submissions
                           SET status = 'done', result = :result, passed_cases = :passed, total_cases = :total,
                               score = :score, compile_output = :compileOutput, finished_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("result", result)
                .param("passed", passedCases)
                .param("total", totalCases)
                .param("score", score)
                .param("compileOutput", compileOutput)
                .update();
    }

    public void markJudgeError(UUID id, String message) {
        jdbc.sql("""
                        UPDATE submissions
                           SET status = 'judge_error', result = NULL, compile_output = :msg, finished_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("msg", message)
                .update();
    }

    public void resetToQueued(UUID id) {
        jdbc.sql("""
                        UPDATE submissions
                           SET status = 'queued', result = NULL, passed_cases = 0, total_cases = 0,
                               score = 0, compile_output = NULL, finished_at = NULL
                         WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    // ---------------- 学生视角 ----------------

    public List<SubmissionRow> listForStudent(UUID studentId, UUID problemId, UUID assignmentId, int limit,
            int offset) {
        String sql = "SELECT " + COLS + " FROM submissions WHERE user_id = :userId"
                + (problemId == null ? "" : " AND problem_id = :problemId")
                + (assignmentId == null ? "" : " AND assignment_id = :assignmentId")
                + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset";
        var spec = jdbc.sql(sql).param("userId", studentId).param("limit", limit).param("offset", offset);
        if (problemId != null) {
            spec = spec.param("problemId", problemId);
        }
        if (assignmentId != null) {
            spec = spec.param("assignmentId", assignmentId);
        }
        return spec.query(MAPPER).list();
    }

    public long countForStudent(UUID studentId, UUID problemId, UUID assignmentId) {
        String sql = "SELECT count(*) FROM submissions WHERE user_id = :userId"
                + (problemId == null ? "" : " AND problem_id = :problemId")
                + (assignmentId == null ? "" : " AND assignment_id = :assignmentId");
        var spec = jdbc.sql(sql).param("userId", studentId);
        if (problemId != null) {
            spec = spec.param("problemId", problemId);
        }
        if (assignmentId != null) {
            spec = spec.param("assignmentId", assignmentId);
        }
        return spec.query(Long.class).single();
    }

    public Optional<SubmissionRow> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT " + COLS + " FROM submissions WHERE idempotency_key = :key")
                .param("key", key)
                .query(MAPPER)
                .optional();
    }

    /** 幂等（FR-SUB-01）：同一学生同一题在 N 秒内的既有提交。 */
    public Optional<SubmissionRow> findRecent(UUID userId, UUID problemId, int seconds) {
        return jdbc.sql("SELECT " + COLS + """
                         FROM submissions
                        WHERE user_id = :userId AND problem_id = :problemId
                          AND created_at > now() - make_interval(secs => :seconds)
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("userId", userId)
                .param("problemId", problemId)
                .param("seconds", seconds)
                .query(MAPPER)
                .optional();
    }

    // ---------------- 教师视角 ----------------

    public List<TeacherSubmissionRow> listByAssignment(UUID assignmentId, UUID problemId, int limit) {
        String sql = """
                SELECT s.id, s.user_id, u.display_name AS student_name, u.email AS student_email,
                       s.problem_id, p.title AS problem_title, s.language, s.status, s.result,
                       s.passed_cases, s.total_cases, s.score, s.source_code, s.created_at
                  FROM submissions s
                  JOIN users u ON u.id = s.user_id
                  JOIN problems p ON p.id = s.problem_id
                  JOIN assignments a ON a.id = s.assignment_id
                  JOIN teaching_classes tc ON tc.id = a.teaching_class_id
                  JOIN enrollments e ON e.class_id = tc.class_id AND e.student_id = s.user_id
                 WHERE s.assignment_id = :assignmentId
                """ + (problemId == null ? "" : " AND s.problem_id = :problemId")
                + " ORDER BY s.created_at DESC LIMIT :limit";
        var spec = jdbc.sql(sql).param("assignmentId", assignmentId).param("limit", limit);
        if (problemId != null) {
            spec = spec.param("problemId", problemId);
        }
        return spec.query((rs, i) -> new TeacherSubmissionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("student_name"),
                rs.getString("student_email"),
                rs.getObject("problem_id", UUID.class),
                rs.getString("problem_title"),
                rs.getString("language"),
                rs.getString("status"),
                rs.getString("result"),
                rs.getInt("passed_cases"),
                rs.getInt("total_cases"),
                rs.getInt("score"),
                rs.getString("source_code"),
                rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    // ---------------- 判题明细 ----------------

    public void deleteCaseResults(UUID submissionId) {
        jdbc.sql("DELETE FROM judge_case_results WHERE submission_id = :id").param("id", submissionId).update();
    }

    public void insertCaseResult(UUID submissionId, int caseSeq, String status, int timeMs, Integer exitCode,
            String inputExcerpt, String expectedExcerpt, String actualExcerpt, boolean isHidden) {
        jdbc.sql("""
                        INSERT INTO judge_case_results (submission_id, case_seq, status, time_ms, exit_code,
                                                        input_excerpt, expected_excerpt, actual_excerpt, is_hidden)
                        VALUES (:sid, :seq, :status, :timeMs, :exitCode, :input, :expected, :actual, :hidden)
                        """)
                .param("sid", submissionId)
                .param("seq", caseSeq)
                .param("status", status)
                .param("timeMs", timeMs)
                .param("exitCode", exitCode)
                .param("input", inputExcerpt)
                .param("expected", expectedExcerpt)
                .param("actual", actualExcerpt)
                .param("hidden", isHidden)
                .update();
    }

    public List<CaseRow> listCaseResults(UUID submissionId) {
        return jdbc.sql("""
                        SELECT case_seq, status, time_ms, exit_code, input_excerpt, expected_excerpt,
                               actual_excerpt, is_hidden
                          FROM judge_case_results WHERE submission_id = :id ORDER BY case_seq
                        """)
                .param("id", submissionId)
                .query((rs, i) -> new CaseRow(
                        rs.getInt("case_seq"),
                        rs.getString("status"),
                        rs.getInt("time_ms"),
                        rs.getObject("exit_code", Integer.class),
                        rs.getString("input_excerpt"),
                        rs.getString("expected_excerpt"),
                        rs.getString("actual_excerpt"),
                        rs.getBoolean("is_hidden")))
                .list();
    }
}
