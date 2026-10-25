package com.codelab.app.ai;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 配额 + 用量 + 调用流水（PRD §15.5）。 */
@Repository
public class AiQuotaRepository {

    public record QuotaRow(String scope, UUID scopeId, int dailyTokens, int perProblemCalls) {
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

    public void insertCall(UUID userId, UUID problemId, String kind, Integer level, UUID submissionId,
            int promptTokens, int completionTokens, boolean charged, boolean cached) {
        jdbc.sql("""
                        INSERT INTO ai_calls (user_id, problem_id, kind, level, submission_id,
                                              prompt_tokens, completion_tokens, charged, cached)
                        VALUES (:userId, :problemId, :kind, :level, :submissionId, :prompt, :completion, :charged, :cached)
                        """)
                .param("userId", userId)
                .param("problemId", problemId)
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
