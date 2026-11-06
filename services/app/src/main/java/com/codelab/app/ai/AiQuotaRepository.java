package com.codelab.app.ai;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 配额 + 用量 + 调用流水（PRD §15.5）。 */
@Repository
public class AiQuotaRepository {

    public record QuotaRow(String scope, UUID scopeId, int dailyTokens, int perProblemCalls) {
    }

    public record UsageTotals(long calls, long promptTokens, long completionTokens) {
        public long totalTokens() { return promptTokens + completionTokens; }
    }

    public record TaskUsage(String task, long calls, long promptTokens, long completionTokens) {
    }

    public record CourseUsage(UUID courseId, String courseCode, String courseName, long calls,
            long promptTokens, long completionTokens) {
    }

    public record StudentUsage(UUID userId, String displayName, String email, long calls,
            long promptTokens, long completionTokens) {
    }

    private final JdbcClient jdbc;

    public AiQuotaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<QuotaRow> find(String scope, UUID scopeId) {
        String sql = scopeId == null
                ? "SELECT scope, scope_id, daily_tokens, per_problem_calls FROM ai_quotas WHERE scope = :scope AND scope_id IS NULL"
                : "SELECT scope, scope_id, daily_tokens, per_problem_calls FROM ai_quotas WHERE scope = :scope AND scope_id = :scopeId";
        var spec = jdbc.sql(sql).param("scope", scope);
        if (scopeId != null) {
            spec = spec.param("scopeId", scopeId);
        }
        return spec.query((rs, i) -> new QuotaRow(
                rs.getString("scope"),
                rs.getObject("scope_id", UUID.class),
                rs.getInt("daily_tokens"),
                rs.getInt("per_problem_calls"))).optional();
    }

    public List<QuotaRow> listAll() {
        return jdbc.sql("SELECT scope, scope_id, daily_tokens, per_problem_calls FROM ai_quotas ORDER BY scope, scope_id")
                .query((rs, i) -> new QuotaRow(rs.getString("scope"), rs.getObject("scope_id", UUID.class),
                        rs.getInt("daily_tokens"), rs.getInt("per_problem_calls")))
                .list();
    }

    public void save(String scope, UUID scopeId, int dailyTokens, int perProblemCalls) {
        jdbc.sql("""
                        INSERT INTO ai_quotas (scope, scope_id, daily_tokens, per_problem_calls)
                        VALUES (:scope, :scopeId, :dailyTokens, :perProblemCalls)
                        ON CONFLICT (scope, (coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid)))
                        DO UPDATE SET daily_tokens = EXCLUDED.daily_tokens,
                                      per_problem_calls = EXCLUDED.per_problem_calls
                        """)
                .param("scope", scope)
                .param("scopeId", scopeId)
                .param("dailyTokens", dailyTokens)
                .param("perProblemCalls", perProblemCalls)
                .update();
    }

    public UsageTotals todayTotals() {
        return jdbc.sql("""
                        SELECT coalesce(sum(calls),0) calls,
                               coalesce(sum(prompt_tokens),0) prompt_tokens,
                               coalesce(sum(completion_tokens),0) completion_tokens
                          FROM ai_usage WHERE date = current_date
                        """)
                .query((rs, i) -> new UsageTotals(rs.getLong("calls"), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens")))
                .single();
    }

    public UsageTotals monthTotals() {
        return jdbc.sql("""
                        SELECT coalesce(sum(calls),0) calls,
                               coalesce(sum(prompt_tokens),0) prompt_tokens,
                               coalesce(sum(completion_tokens),0) completion_tokens
                          FROM ai_usage
                         WHERE date >= date_trunc('month', current_date)::date AND date <= current_date
                        """)
                .query((rs, i) -> new UsageTotals(rs.getLong("calls"), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens")))
                .single();
    }

    public List<TaskUsage> tasksToday() {
        return jdbc.sql("""
                        SELECT task, coalesce(sum(calls),0) calls,
                               coalesce(sum(prompt_tokens),0) prompt_tokens,
                               coalesce(sum(completion_tokens),0) completion_tokens
                          FROM ai_usage WHERE date = current_date
                         GROUP BY task ORDER BY task
                        """)
                .query((rs, i) -> new TaskUsage(rs.getString("task"), rs.getLong("calls"),
                        rs.getLong("prompt_tokens"), rs.getLong("completion_tokens")))
                .list();
    }

    public List<CourseUsage> coursesThisMonth() {
        return jdbc.sql("""
                        SELECT c.id course_id, c.code course_code, c.name course_name,
                               count(*) calls, coalesce(sum(a.prompt_tokens),0) prompt_tokens,
                               coalesce(sum(a.completion_tokens),0) completion_tokens
                          FROM ai_calls a
                          JOIN courses c ON c.id = a.course_id
                         WHERE a.created_at >= date_trunc('month', current_date)
                           AND a.created_at < current_date + interval '1 day'
                           AND a.charged
                         GROUP BY c.id, c.code, c.name
                         ORDER BY sum(a.prompt_tokens + a.completion_tokens) DESC
                         LIMIT 100
                        """)
                .query((rs, i) -> new CourseUsage(rs.getObject("course_id", UUID.class), rs.getString("course_code"),
                        rs.getString("course_name"), rs.getLong("calls"), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens")))
                .list();
    }

    public List<StudentUsage> studentsThisMonth() {
        return jdbc.sql("""
                        SELECT u.id user_id, u.display_name, u.email,
                               coalesce(sum(x.calls),0) calls,
                               coalesce(sum(x.prompt_tokens),0) prompt_tokens,
                               coalesce(sum(x.completion_tokens),0) completion_tokens
                          FROM ai_usage x JOIN users u ON u.id = x.user_id
                         WHERE x.date >= date_trunc('month', current_date)::date AND x.date <= current_date
                         GROUP BY u.id, u.display_name, u.email
                         ORDER BY sum(x.prompt_tokens + x.completion_tokens) DESC
                         LIMIT 20
                        """)
                .query((rs, i) -> new StudentUsage(rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                        rs.getString("email"), rs.getLong("calls"), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens")))
                .list();
    }

    /** 日 token 累计（只累加，不覆盖）。 */
    public void addUsage(UUID userId, String task, int promptTokens, int completionTokens) {
        jdbc.sql("""
                        INSERT INTO ai_usage (user_id, date, task, calls, prompt_tokens, completion_tokens, cost_cents)
                        VALUES (:userId, current_date, :task, 1, :prompt, :completion, 0)
                        ON CONFLICT (user_id, date, task) DO UPDATE
                           SET calls = ai_usage.calls + 1,
                               prompt_tokens = ai_usage.prompt_tokens + EXCLUDED.prompt_tokens,
                               completion_tokens = ai_usage.completion_tokens + EXCLUDED.completion_tokens
                        """)
                .param("userId", userId)
                .param("task", task)
                .param("prompt", promptTokens)
                .param("completion", completionTokens)
                .update();
    }

    public long tokensToday(UUID userId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(prompt_tokens + completion_tokens), 0)
                          FROM ai_usage WHERE user_id = :userId AND date = current_date
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    public void insertCall(UUID userId, UUID problemId, UUID courseId, String kind, Integer level, UUID submissionId,
            int promptTokens, int completionTokens, boolean charged, boolean cached) {
        jdbc.sql("""
                        INSERT INTO ai_calls (user_id, problem_id, course_id, kind, level, submission_id,
                                              prompt_tokens, completion_tokens, charged, cached)
                        VALUES (:userId, :problemId, :courseId, :kind, :level, :submissionId, :prompt, :completion, :charged, :cached)
                        """)
                .param("userId", userId)
                .param("problemId", problemId)
                .param("courseId", courseId)
                .param("kind", kind)
                .param("level", level)
                .param("submissionId", submissionId)
                .param("prompt", promptTokens)
                .param("completion", completionTokens)
                .param("charged", charged)
                .param("cached", cached)
                .update();
    }

    public long chargedCallsForProblem(UUID userId, UUID problemId) {
        return jdbc.sql("""
                        SELECT count(*) FROM ai_calls
                         WHERE user_id = :userId AND problem_id = :problemId AND charged
                        """)
                .param("userId", userId)
                .param("problemId", problemId)
                .query(Long.class)
                .single();
    }
}
