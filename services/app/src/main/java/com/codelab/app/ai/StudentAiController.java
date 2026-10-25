package com.codelab.app.ai;

import com.codelab.app.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S1 答题解疑（PRD §15.2 / §15.3）：答疑 / 三级思路 / 正确结果分析。
 * <p>提交前一律 403 —— 这条规则让 AI 只能用于"复盘"，不能用于"代写"。
 */
@RestController
@RequestMapping("/api/student/problems/{problemId}/ai")
public class StudentAiController {

    private final AiGateService gate;
    private final AiQuotaService quotas;
    private final AiAnswerService answers;

    public StudentAiController(AiGateService gate, AiQuotaService quotas, AiAnswerService answers) {
        this.gate = gate;
        this.quotas = quotas;
        this.answers = answers;
    }

    public record StatusView(boolean hasSubmission, boolean passed, boolean canUseAi, String reason,
            int hintLevelsAvailable, int hintLevelsUnlocked, long perProblemCalls, int perProblemCallLimit,
            long dailyTokens, int dailyTokenLimit) {
    }

    public record ExplainRequest(UUID submissionId, @Size(max = 500) String question, Boolean forceRefresh) {
    }

    public record HintRequest(UUID submissionId, @Min(1) @Max(3) Integer level, Boolean forceRefresh) {
    }

    public record AnalyzeRequest(UUID submissionId, Boolean forceRefresh) {
    }

    @GetMapping("/status")
    public StatusView status(@PathVariable UUID problemId) {
        var current = SecurityUtils.require();
        var gateStatus = gate.status(current.id(), problemId);
        var snapshot = quotas.snapshot(current.id(), problemId);
        return new StatusView(gateStatus.hasSubmission(), gateStatus.passed(), gateStatus.canUseAi(),
                gateStatus.reason(), gateStatus.hintLevelsAvailable(), gateStatus.hintLevelsUnlocked(),
                snapshot.perProblemCalls(), snapshot.perProblemCallLimit(), snapshot.dailyTokens(),
                snapshot.dailyTokenLimit());
    }

    @PostMapping("/explain")
    public AiAnswerService.AiAnswer explain(@PathVariable UUID problemId,
            @Valid @RequestBody(required = false) ExplainRequest body) {
        UUID studentId = SecurityUtils.require().id();
        ExplainRequest request = body == null ? new ExplainRequest(null, null, null) : body;
        return answers.explain(studentId, problemId, request.submissionId(), request.question(),
                Boolean.TRUE.equals(request.forceRefresh()));
    }

    @PostMapping("/hint")
    public AiAnswerService.AiAnswer hint(@PathVariable UUID problemId, @Valid @RequestBody HintRequest body) {
        UUID studentId = SecurityUtils.require().id();
        int level = body.level() == null ? 1 : body.level();
        return answers.hint(studentId, problemId, body.submissionId(), level,
                Boolean.TRUE.equals(body.forceRefresh()));
    }

    @PostMapping("/analyze")
    public AiAnswerService.AiAnswer analyze(@PathVariable UUID problemId,
            @Valid @RequestBody(required = false) AnalyzeRequest body) {
        UUID studentId = SecurityUtils.require().id();
        AnalyzeRequest request = body == null ? new AnalyzeRequest(null, null) : body;
        return answers.analyze(studentId, problemId, request.submissionId(),
                Boolean.TRUE.equals(request.forceRefresh()));
    }
}
