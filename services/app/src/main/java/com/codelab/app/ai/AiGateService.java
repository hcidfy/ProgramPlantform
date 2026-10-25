package com.codelab.app.ai;

import com.codelab.app.common.ApiException;
import com.codelab.app.problem.ProblemRepository;
import com.codelab.app.submission.SubmissionRepository;
import com.codelab.app.audit.AuditActions;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 时间闸门（FR-AI-00 / PRD §8.2）：学生对该题**首次提交前**所有 S1 AI 接口 403。
 * <p>这条规则是整个产品最硬的规则——它用"时间"替代了内容审核。
 */
@Service
public class AiGateService {

    public record GateStatus(boolean hasSubmission, boolean passed, boolean canUseAi, String reason,
            int hintLevelsAvailable, int hintLevelsUnlocked) {
    }

    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final AiConversationRepository conversations;

    public AiGateService(SubmissionRepository submissions, ProblemRepository problems,
            AiConversationRepository conversations) {
        this.submissions = submissions;
        this.problems = problems;
        this.conversations = conversations;
    }

    public GateStatus status(UUID studentId, UUID problemId) {
        requireVisible(studentId, problemId);
        long submissionCount = submissions.countForStudent(studentId, problemId, null);
        boolean hasSubmission = submissionCount > 0;
        boolean passed = submissions.listForStudent(studentId, problemId, null, 20, 0).stream()
                .anyMatch(row -> "accepted".equals(row.result()));
        int unlocked = conversations.countHintLevels(studentId, problemId);
        int available = passed || !hasSubmission ? 0 : 3;
        if (!hasSubmission) {
            return new GateStatus(false, false, false, "提交后才能和 AI 讨论你的代码", 0, 0);
        }
        return new GateStatus(true, passed, available > 0, passed ? "已通过，思路提示已关闭" : null, available,
                unlocked);
    }

    public ProblemRepository.ProblemRow requireVisible(UUID studentId, UUID problemId) {
        ProblemRepository.ProblemRow problem = problems.findById(problemId)
                .orElseThrow(() -> ApiException.notFound("题目不存在"));
        if (!problems.isVisibleToStudent(problemId, studentId)) {
            throw ApiException.notFound("题目不存在");
        }
        return problem;
    }

    /** 取"这次讨论对应哪次提交"：显式传的、否则最近一次；都没有 → 403 闸门。 */
    public SubmissionRepository.SubmissionRow requireSubmission(UUID studentId, UUID problemId, UUID submissionId) {
        requireVisible(studentId, problemId);
        Optional<SubmissionRepository.SubmissionRow> row = submissionId != null
                ? submissions.findById(submissionId)
                        .filter(s -> s.userId().equals(studentId) && s.problemId().equals(problemId))
                : submissions.listForStudent(studentId, problemId, null, 1, 0).stream().findFirst();
        return row.orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "AI_GATE_CLOSED", "提交后才能获得 AI 帮助"));
    }

    public List<SubmissionRepository.CaseRow> caseRows(UUID submissionId) {
        return submissions.listCaseResults(submissionId);
    }

    public String auditAction() {
        return AuditActions.AI_CALL;
    }
}
