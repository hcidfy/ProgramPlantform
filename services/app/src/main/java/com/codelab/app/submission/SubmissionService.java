package com.codelab.app.submission;

import com.codelab.app.assignment.AssignmentRepository;
import com.codelab.app.assignment.AssignmentService;
import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.common.ApiException;
import com.codelab.app.common.PageResult;
import com.codelab.app.config.AppProperties;
import com.codelab.app.judge.JudgeTaskQueue;
import com.codelab.app.org.OrgRepository;
import com.codelab.app.problem.ProblemRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 提交与结果（PRD §14.5）：64 KB 上限、10 s 幂等、隐藏用例不下发、
 * 队列积压 > maxQueue 时 429。
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
    private static final int MAX_SOURCE_BYTES = 64 * 1024;
    private static final int IDEMPOTENT_SECONDS = 10;

    public record SubmitResult(UUID id, String status, long queuePosition, boolean duplicate) {
    }

    public record CaseView(int seq, String status, int timeMs, String input, String expected, String actual,
            boolean hidden) {
    }

    public record SubmissionView(UUID id, UUID problemId, UUID assignmentId, String language, String status,
            String result, int passedCases, int totalCases, int score, String compileOutput, String sourceCode,
            long queuePosition, OffsetDateTime createdAt, OffsetDateTime finishedAt, List<CaseView> cases,
            CaseView firstFailedCase) {
    }

    public record SubmissionSummary(UUID id, UUID problemId, UUID assignmentId, String language, String status,
            String result, int passedCases, int totalCases, int score, OffsetDateTime createdAt,
            OffsetDateTime finishedAt) {

        static SubmissionSummary of(SubmissionRepository.SubmissionRow row) {
            return new SubmissionSummary(row.id(), row.problemId(), row.assignmentId(), row.language(), row.status(),
                    row.result(), row.passedCases(), row.totalCases(), row.score(), row.createdAt(), row.finishedAt());
        }
    }

    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final AssignmentRepository assignments;
    private final AssignmentService assignmentService;
    private final OrgRepository org;
    private final JudgeTaskQueue queue;
    private final AuditService audit;
    private final AppProperties props;

    public SubmissionService(SubmissionRepository submissions, ProblemRepository problems,
            AssignmentRepository assignments, AssignmentService assignmentService, OrgRepository org,
            JudgeTaskQueue queue, AuditService audit, AppProperties props) {
        this.submissions = submissions;
        this.problems = problems;
        this.assignments = assignments;
        this.assignmentService = assignmentService;
        this.org = org;
        this.queue = queue;
        this.audit = audit;
        this.props = props;
    }

    // ---------------- 学生提交 ----------------

    public SubmitResult submit(UUID studentId, UUID assignmentId, UUID problemId, String sourceCode,
            String idempotencyKey) {
        ProblemRepository.ProblemRow problem = problems.findById(problemId)
                .orElseThrow(() -> ApiException.notFound("题目不存在"));
        if (assignmentId != null) {
            AssignmentRepository.AssignmentRow assignment = assignmentService.requireSubmittable(studentId,
                    assignmentId);
            if (!assignmentService.assignmentContainsProblem(assignment.id(), problemId)) {
                throw ApiException.notFound("题目不在该作业里");
            }
        } else if (!problems.isVisibleToStudent(problemId, studentId)) {
            throw ApiException.notFound("题目不存在");
        }
        if (sourceCode == null || sourceCode.isBlank()) {
            throw ApiException.validation("代码不能为空");
        }
        int bytes = sourceCode.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_SOURCE_BYTES) {
            throw ApiException.payloadTooLarge("代码不能超过 64 KB（当前 " + bytes + " 字节）");
        }

        // FR-SUB-01：同一题 10 s 内重复提交命中幂等（先判时间窗口，再判客户端幂等键）
        var recent = submissions.findRecent(studentId, problemId, IDEMPOTENT_SECONDS);
        if (recent.isPresent()) {
            return new SubmitResult(recent.get().id(), recent.get().status(),
                    queue.queuePosition(recent.get().id()), true);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = submissions.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                if (!existing.get().userId().equals(studentId)) {
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "幂等键已被使用");
                }
                return new SubmitResult(existing.get().id(), existing.get().status(),
                        queue.queuePosition(existing.get().id()), true);
            }
        }

        long backlog = queue.pendingCount();
        int maxQueue = props.judge().maxQueue();
        if (backlog >= maxQueue) {
            throw ApiException.tooManyRequests("JUDGE_BUSY",
                    "判题队列积压较多（" + backlog + " 个），请稍后再试");
        }

        UUID id = submissions.insert(studentId, problemId, assignmentId, problem.language(), sourceCode,
                idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim(), null);
        queue.enqueue(id);
        audit.record(studentId, AuditActions.STUDENT_SUBMIT, "submission:" + id,
                Map.of("problemId", problemId, "assignmentId", assignmentId == null ? "" : assignmentId,
                        "bytes", bytes));
        log.info("收到提交 {} student={} problem={}", id, studentId, problemId);
        return new SubmitResult(id, "queued", queue.queuePosition(id), false);
    }

    // ---------------- 学生查看 ----------------

    public SubmissionView detail(UUID studentId, UUID submissionId) {
        SubmissionRepository.SubmissionRow row = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("提交不存在"));
        if (!row.userId().equals(studentId)) {
            throw ApiException.notFound("提交不存在");
        }
        return toView(row, true);
    }

    public PageResult<SubmissionSummary> list(UUID studentId, UUID problemId, UUID assignmentId, int page, int size) {
        long total = submissions.countForStudent(studentId, problemId, assignmentId);
        List<SubmissionSummary> items = submissions
                .listForStudent(studentId, problemId, assignmentId, size, (page - 1) * size).stream()
                .map(SubmissionSummary::of)
                .toList();
        return PageResult.of(items, total, page, size);
    }

    // ---------------- 教师查看 / 重判 ----------------

    public List<SubmissionRepository.TeacherSubmissionRow> listForTeacher(UUID teacherId, UUID assignmentId,
            UUID problemId) {
        AssignmentRepository.AssignmentRow assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound("作业不存在"));
        if (!org.teacherOwnsTeachingClass(teacherId, assignment.teachingClassId())) {
            throw ApiException.notFound("作业不存在");
        }
        List<SubmissionRepository.TeacherSubmissionRow> rows = submissions.listByAssignment(assignmentId, problemId,
                200);
        if (!rows.isEmpty()) {
            // 看他人代码必须留痕（FR-ADM-04）
            audit.record(teacherId, AuditActions.TEACHER_VIEW_CODE, "assignment:" + assignmentId,
                    Map.of("count", rows.size()));
        }
        return rows;
    }

    /** 教师看某条提交的完整结果（含代码与隐藏用例明细，PRD FR-TCH-04）。 */
    public SubmissionView teacherDetail(UUID teacherId, UUID submissionId) {
        SubmissionRepository.SubmissionRow row = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("提交不存在"));
        if (row.assignmentId() == null) {
            throw ApiException.notFound("提交不存在");
        }
        AssignmentRepository.AssignmentRow assignment = assignments.findById(row.assignmentId())
                .orElseThrow(() -> ApiException.notFound("提交不存在"));
        if (!org.teacherOwnsTeachingClass(teacherId, assignment.teachingClassId())) {
            throw ApiException.notFound("提交不存在");
        }
        audit.record(teacherId, AuditActions.TEACHER_VIEW_CODE, "submission:" + submissionId, Map.of());
        return toView(row, false);
    }

    /** 重判：复制成一条新提交重新排队，旧记录保留（FR-TCH-04）。 */
    public SubmitResult rejudge(UUID teacherId, UUID submissionId) {
        SubmissionRepository.SubmissionRow original = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("提交不存在"));
        if (original.assignmentId() == null) {
            throw ApiException.validation("这条提交不属于任何作业，无法从教师端重判");
        }
        AssignmentRepository.AssignmentRow assignment = assignments.findById(original.assignmentId())
                .orElseThrow(() -> ApiException.notFound("作业不存在"));
        if (!org.teacherOwnsTeachingClass(teacherId, assignment.teachingClassId())) {
            throw ApiException.notFound("提交不存在");
        }
        UUID newId = submissions.insert(original.userId(), original.problemId(), original.assignmentId(),
                original.language(), original.sourceCode(), null, original.id());
        queue.enqueue(newId);
        audit.record(teacherId, AuditActions.TEACHER_REJUDGE, "submission:" + newId,
                Map.of("original", submissionId));
        return new SubmitResult(newId, "queued", queue.queuePosition(newId), false);
    }

    // ---------------- 内部 ----------------

    private SubmissionView toView(SubmissionRepository.SubmissionRow row, boolean maskHidden) {
        List<CaseView> cases = new ArrayList<>();
        CaseView firstFailed = null;
        for (SubmissionRepository.CaseRow caseRow : submissions.listCaseResults(row.id())) {
            CaseView view = maskHidden ? mask(caseRow) : unmasked(caseRow);
            cases.add(view);
            if (firstFailed == null && !"ac".equals(caseRow.status())) {
                firstFailed = view;
            }
        }
        long position = "queued".equals(row.status()) ? queue.queuePosition(row.id()) : 0;
        return new SubmissionView(row.id(), row.problemId(), row.assignmentId(), row.language(), row.status(),
                row.result(), row.passedCases(), row.totalCases(), row.score(), row.compileOutput(),
                row.sourceCode(), position, row.createdAt(), row.finishedAt(), cases, firstFailed);
    }

    private CaseView unmasked(SubmissionRepository.CaseRow row) {
        return new CaseView(row.caseSeq(), row.status(), row.timeMs(), row.inputExcerpt(), row.expectedExcerpt(),
                row.actualExcerpt(), row.isHidden());
    }

    /** 隐藏用例：只给出"第 N 组未通过"，输入/期望/实际一律不下发（FR-JDG-02）。 */
    private CaseView mask(SubmissionRepository.CaseRow row) {
        if (row.isHidden() && !"ac".equals(row.status())) {
            return new CaseView(row.caseSeq(), row.status(), row.timeMs(), null, null, null, true);
        }
        if (row.isHidden()) {
            return new CaseView(row.caseSeq(), row.status(), row.timeMs(), null, null, null, true);
        }
        return new CaseView(row.caseSeq(), row.status(), row.timeMs(), row.inputExcerpt(), row.expectedExcerpt(),
                row.actualExcerpt(), false);
    }
}
