package com.codelab.app.assignment;

import com.codelab.app.common.ApiException;
import com.codelab.app.config.AppProperties;
import com.codelab.app.org.OrgRepository;
import com.codelab.app.problem.ProblemRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 作业业务规则（PRD §14.4 / §14.5）：教师只能操作自己教学班的作业；
 * 发布 = 一键推送给该教学班全部学生（FR-TCH-02）；截止后按开关决定能否提交。
 */
@Service
public class AssignmentService {

    public record ProgressView(long expectedCount, long submittedCount, long passedCount, double passRate,
            double passedSubmissionRate) {
    }

    public record TeacherAssignmentView(AssignmentRepository.AssignmentRow assignment,
            List<ProblemRepository.ProblemInAssignment> problems, ProgressView progress) {
    }

    public record StudentProblemStatusView(UUID problemId, String status) {
    }

    public record StudentAssignmentView(AssignmentRepository.AssignmentRow assignment,
            List<ProblemRepository.ProblemInAssignment> problems, List<StudentProblemStatusView> statuses,
            boolean expired) {
    }

    public record ProblemScoreInput(UUID problemId, Integer score) {
    }

    private final AssignmentRepository assignments;
    private final ProblemRepository problems;
    private final OrgRepository org;
    private final AppProperties props;

    public AssignmentService(AssignmentRepository assignments, ProblemRepository problems, OrgRepository org,
            AppProperties props) {
        this.assignments = assignments;
        this.problems = problems;
        this.org = org;
        this.props = props;
    }

    // ---------------- 教师 ----------------

    public UUID create(UUID teacherId, UUID teachingClassId, String title, String description, OffsetDateTime deadline,
            List<ProblemScoreInput> items) {
        if (teachingClassId == null || !org.teacherOwnsTeachingClass(teacherId, teachingClassId)) {
            throw ApiException.notFound("教学班不存在");
        }
        if (title == null || title.isBlank()) {
            throw ApiException.validation("作业标题不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw ApiException.validation("作业至少要有 1 道题");
        }
        UUID courseId = problemsOfTeachingClass(teachingClassId);
        for (ProblemScoreInput item : items) {
            ProblemRepository.ProblemRow problem = problems.findById(item.problemId())
                    .orElseThrow(() -> ApiException.validation("题目不存在: " + item.problemId()));
            if (!problem.courseId().equals(courseId)) {
                throw ApiException.validation("题目《" + problem.title() + "》不属于该教学班的课程");
            }
            if (!problem.isPublished()) {
                throw ApiException.validation("题目《" + problem.title() + "》还没发布，不能布置");
            }
        }
        UUID id = assignments.insert(teachingClassId, title.trim(), description, deadline, teacherId);
        replaceProblemsInternal(id, items);
        return id;
    }

    public void update(UUID teacherId, UUID assignmentId, String title, String description, OffsetDateTime deadline,
            List<ProblemScoreInput> items) {
        AssignmentRepository.AssignmentRow assignment = requireOwnedByTeacher(teacherId, assignmentId);
        if (!assignment.isDraft()) {
            throw ApiException.conflict("ASSIGNMENT_NOT_DRAFT", "已发布的作业不能再改，请新建作业");
        }
        assignments.update(assignmentId, title == null ? assignment.title() : title.trim(), description, deadline);
        if (items != null && !items.isEmpty()) {
            replaceProblemsInternal(assignmentId, items);
        }
    }

    public void publish(UUID teacherId, UUID assignmentId) {
        AssignmentRepository.AssignmentRow assignment = requireOwnedByTeacher(teacherId, assignmentId);
        if (!assignment.isDraft()) {
            throw ApiException.conflict("ASSIGNMENT_NOT_DRAFT", "作业已发布");
        }
        if (problems.listProblemsInAssignment(assignmentId).isEmpty()) {
            throw ApiException.validation("没有题目不能发布");
        }
        assignments.publish(assignmentId);
    }

    public void delete(UUID teacherId, UUID assignmentId) {
        AssignmentRepository.AssignmentRow assignment = requireOwnedByTeacher(teacherId, assignmentId);
        if (!assignment.isDraft()) {
            throw ApiException.conflict("ASSIGNMENT_NOT_DRAFT", "已发布的作业不能删");
        }
        assignments.delete(assignmentId);
    }

    public List<TeacherAssignmentView> listForTeacher(UUID teacherId, UUID teachingClassId) {
        if (teachingClassId == null || !org.teacherOwnsTeachingClass(teacherId, teachingClassId)) {
            throw ApiException.notFound("教学班不存在");
        }
        List<TeacherAssignmentView> result = new ArrayList<>();
        for (AssignmentRepository.AssignmentRow row : assignments.listByTeachingClass(teachingClassId)) {
            result.add(new TeacherAssignmentView(row, problems.listProblemsInAssignment(row.id()),
                    progress(row.id())));
        }
        return result;
    }

    public TeacherAssignmentView teacherDetail(UUID teacherId, UUID assignmentId) {
        AssignmentRepository.AssignmentRow assignment = requireOwnedByTeacher(teacherId, assignmentId);
        return new TeacherAssignmentView(assignment, problems.listProblemsInAssignment(assignmentId),
                progress(assignmentId));
    }

    // ---------------- 学生 ----------------

    public List<StudentAssignmentView> listForStudent(UUID studentId) {
        List<StudentAssignmentView> result = new ArrayList<>();
        for (AssignmentRepository.AssignmentRow row : assignments.listPublishedForStudent(studentId)) {
            result.add(studentView(row, studentId));
        }
        return result;
    }

    public StudentAssignmentView detailForStudent(UUID studentId, UUID assignmentId) {
        AssignmentRepository.AssignmentRow row = assignments.findPublishedForStudent(assignmentId, studentId)
                .orElseThrow(() -> ApiException.notFound("作业不存在"));
        return studentView(row, studentId);
    }

    /** 提交前闸门：作业必须已发布、学生属于该教学班、截止时间按配置判断。 */
    public AssignmentRepository.AssignmentRow requireSubmittable(UUID studentId, UUID assignmentId) {
        AssignmentRepository.AssignmentRow assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound("作业不存在"));
        if (!assignment.isPublished()) {
            throw ApiException.notFound("作业不存在");
        }
        if (!org.studentInTeachingClass(studentId, assignment.teachingClassId())) {
            throw ApiException.notFound("作业不存在");
        }
        if (isExpired(assignment) && !allowsLate()) {
            throw ApiException.forbidden("作业已截止，不能再提交");
        }
        return assignment;
    }

    public boolean allowsLate() {
        return props.assignment() != null && props.assignment().allowLate();
    }

    // ---------------- 内部 ----------------

    private StudentAssignmentView studentView(AssignmentRepository.AssignmentRow row, UUID studentId) {
        Map<UUID, AssignmentRepository.ProblemStatus> statusMap = assignments
                .problemStatuses(row.id(), studentId).stream()
                .collect(Collectors.toMap(AssignmentRepository.ProblemStatus::problemId, s -> s));
        List<StudentProblemStatusView> statuses = new ArrayList<>();
        for (ProblemRepository.ProblemInAssignment item : problems.listProblemsInAssignment(row.id())) {
            AssignmentRepository.ProblemStatus status = statusMap.get(item.problemId());
            String value = status == null ? "not_started" : status.passed() ? "passed"
                    : status.submitted() ? "submitted" : "not_started";
            statuses.add(new StudentProblemStatusView(item.problemId(), value));
        }
        return new StudentAssignmentView(row, problems.listProblemsInAssignment(row.id()), statuses,
                isExpired(row));
    }

    private ProgressView progress(UUID assignmentId) {
        long expected = assignments.countExpectedUsers(assignmentId);
        long submitted = assignments.countSubmittedUsers(assignmentId);
        long passed = assignments.countPassedUsers(assignmentId);
        long submissions = assignments.countSubmissions(assignmentId);
        long accepted = assignments.countAcceptedSubmissions(assignmentId);
        return new ProgressView(expected, submitted, passed, rate(passed, expected), rate(accepted, submissions));
    }

    private static double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private boolean isExpired(AssignmentRepository.AssignmentRow assignment) {
        return assignment.deadline() != null && assignment.deadline().isBefore(OffsetDateTime.now());
    }

    private UUID problemsOfTeachingClass(UUID teachingClassId) {
        return org.findCourseIdByTeachingClass(teachingClassId)
                .orElseThrow(() -> ApiException.notFound("教学班不存在"));
    }

    private void replaceProblemsInternal(UUID assignmentId, List<ProblemScoreInput> items) {
        assignments.deleteProblems(assignmentId);
        int seq = 0;
        for (ProblemScoreInput item : items) {
            seq++;
            assignments.insertProblem(assignmentId, item.problemId(), seq,
                    item.score() == null ? 0 : Math.max(item.score(), 0));
        }
    }

    private AssignmentRepository.AssignmentRow requireOwnedByTeacher(UUID teacherId, UUID assignmentId) {
        AssignmentRepository.AssignmentRow assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound("作业不存在"));
        if (!org.teacherOwnsTeachingClass(teacherId, assignment.teachingClassId())) {
            throw ApiException.notFound("作业不存在");
        }
        return assignment;
    }

    public List<UUID> teachingClassIdsOf(UUID teacherId) {
        return org.listTeachingClassIdsByTeacher(teacherId);
    }

    /** 供别处复用的题目视图（学生做题页用）。 */
    public List<ProblemRepository.ProblemInAssignment> problemsOf(UUID assignmentId) {
        return problems.listProblemsInAssignment(assignmentId);
    }

    public boolean assignmentContainsProblem(UUID assignmentId, UUID problemId) {
        return problems.listProblemsInAssignment(assignmentId).stream()
                .anyMatch(p -> p.problemId().equals(problemId));
    }

}
