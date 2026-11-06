package com.codelab.app.ai;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.common.ApiException;
import com.codelab.app.org.OrgRepository;
import com.codelab.app.problem.ProblemRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 配额（PRD §5.3 / §15.5）：学生日 token 上限 + 单题调用次数上限。
 * 解析顺序：student 覆盖 → teaching_class 覆盖 → global → 代码默认值。
 */
@Service
public class AiQuotaService {

    public static final int DEFAULT_DAILY_TOKENS = 30_000;
    public static final int DEFAULT_PER_PROBLEM_CALLS = 10;

    public record Limits(int dailyTokens, int perProblemCalls) {
    }

    public record Snapshot(long perProblemCalls, int perProblemCallLimit, long dailyTokens, int dailyTokenLimit) {
    }

    private final AiQuotaRepository quotas;
    private final OrgRepository org;
    private final ProblemRepository problems;
    private final AuditService audit;
    private final com.codelab.app.config.AppProperties props;

    public AiQuotaService(AiQuotaRepository quotas, OrgRepository org, ProblemRepository problems,
            AuditService audit, com.codelab.app.config.AppProperties props) {
        this.quotas = quotas;
        this.org = org;
        this.problems = problems;
        this.audit = audit;
        this.props = props;
    }

    private int defaultDailyTokens() {
        var ai = props.ai();
        return ai == null || ai.defaultDailyTokens() <= 0 ? DEFAULT_DAILY_TOKENS : ai.defaultDailyTokens();
    }

    private int defaultPerProblemCalls() {
        var ai = props.ai();
        return ai == null || ai.defaultPerProblemCalls() <= 0 ? DEFAULT_PER_PROBLEM_CALLS : ai.defaultPerProblemCalls();
    }

    public Limits configuredDefaults() {
        return new Limits(defaultDailyTokens(), defaultPerProblemCalls());
    }

    public Limits limits(UUID studentId, UUID problemId) {
        Optional<AiQuotaRepository.QuotaRow> studentScope = quotas.find("student", studentId);
        if (studentScope.isPresent()) {
            return new Limits(studentScope.get().dailyTokens(), studentScope.get().perProblemCalls());
        }
        if (problemId != null) {
            Optional<UUID> teachingClassId = problems.findById(problemId)
                    .flatMap(problem -> org.findTeachingClassIdByStudentAndCourse(studentId, problem.courseId()));
            if (teachingClassId.isPresent()) {
                Optional<AiQuotaRepository.QuotaRow> classScope = quotas.find("teaching_class",
                        teachingClassId.get());
                if (classScope.isPresent()) {
                    return new Limits(classScope.get().dailyTokens(), classScope.get().perProblemCalls());
                }
            }
        }
        return quotas.find("global", null)
                .map(row -> new Limits(row.dailyTokens(), row.perProblemCalls()))
                .orElse(new Limits(defaultDailyTokens(), defaultPerProblemCalls()));
    }

    public Snapshot snapshot(UUID studentId, UUID problemId) {
        Limits limits = limits(studentId, problemId);
        long usedCalls = problemId == null ? 0 : quotas.chargedCallsForProblem(studentId, problemId);
        long usedTokens = quotas.tokensToday(studentId);
        return new Snapshot(usedCalls, limits.perProblemCalls(), usedTokens, limits.dailyTokens());
    }

    /** S1（答疑/思路/分析）调用前的闸门：先看单题次数，再看当日 token。 */
    public void assertCanSpend(UUID studentId, UUID problemId, String task) {
        Limits limits = limits(studentId, problemId);
        long usedCalls = quotas.chargedCallsForProblem(studentId, problemId);
        if (usedCalls >= limits.perProblemCalls()) {
            deny(studentId, problemId, "PROBLEM", usedCalls, limits);
        }
        long usedTokens = quotas.tokensToday(studentId);
        if (usedTokens >= limits.dailyTokens()) {
            deny(studentId, problemId, "DAILY", usedTokens, limits);
        }
    }

    /** S2 知识问答只吃日 token 额度（不占单题次数）。 */
    public void assertDailyTokens(UUID studentId) {
        Limits limits = limits(studentId, null);
        long usedTokens = quotas.tokensToday(studentId);
        if (usedTokens >= limits.dailyTokens()) {
            deny(studentId, null, "DAILY", usedTokens, limits);
        }
    }

    public void charge(UUID studentId, UUID problemId, UUID courseId, String task, Integer level, UUID submissionId,
            int promptTokens, int completionTokens, boolean charged, boolean cached) {
        quotas.insertCall(studentId, problemId, courseId, task, level, submissionId, promptTokens, completionTokens,
                charged, cached);
        if (charged) {
            quotas.addUsage(studentId, task, promptTokens, completionTokens);
        }
    }

    private void deny(UUID studentId, UUID problemId, String reason, long used, Limits limits) {
        audit.record(studentId, AuditActions.AI_QUOTA_EXCEEDED, problemId == null ? null : "problem:" + problemId,
                Map.of("reason", reason, "used", used,
                        "limit", "PROBLEM".equals(reason) ? limits.perProblemCalls() : limits.dailyTokens()));
        String message = "PROBLEM".equals(reason)
                ? "本题 AI 次数已用完（上限 " + limits.perProblemCalls() + " 次）"
                : "今日 AI 额度用完（上限 " + limits.dailyTokens() + " tokens），明天再来";
        throw ApiException.tooManyRequests("AI_QUOTA_EXCEEDED", message);
    }
}
