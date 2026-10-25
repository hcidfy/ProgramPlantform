package com.codelab.app.problem;

import com.codelab.app.common.ApiException;
import com.codelab.app.common.PageResult;
import com.codelab.app.org.OrgRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 题库业务规则（PRD §14.2）：教师的可操作范围 = 自己任课教学班所属课程；
 * 题目 draft 才能改题面/用例，已发布题改题面必须"另存为新版本"（FR-PRB-04）。
 */
@Service
public class ProblemService {

    public record ProblemDetail(ProblemRepository.ProblemRow problem,
            List<ProblemRepository.TestCaseRow> testCases) {
    }

    /** 学生可见的题面：不含任何用例内容（FR-PRB-02）。 */
    public record StudentProblemView(UUID id, UUID courseId, String title, String statementMd, String language,
            String difficulty, int timeLimitMs, int memLimitKb, String initialCode) {
    }

    public record CaseInput(String input, String expected, boolean hidden, int score) {
    }

    private static final List<String> LANGUAGES = List.of("java", "python3");
    private static final List<String> DIFFICULTIES = List.of("easy", "medium", "hard");
    private static final int MAX_CASES = 20;

    private final ProblemRepository problems;
    private final OrgRepository org;

    public ProblemService(ProblemRepository problems, OrgRepository org) {
        this.problems = problems;
        this.org = org;
    }

    // ---------------- 教师 ----------------

    public PageResult<ProblemRepository.ProblemRow> list(UUID teacherId, UUID courseId, String status, String q,
            int page, int size) {
        List<UUID> courseIds = courseId != null ? List.of(courseId) : org.listCourseIdsByTeacher(teacherId);
        if (courseId != null && !org.teacherTeachesCourse(teacherId, courseId)) {
            throw ApiException.notFound("课程不存在");
        }
        long total = problems.countByCourses(courseIds, status, q);
        List<ProblemRepository.ProblemRow> items = problems.listByCourses(courseIds, status, q, size,
                (page - 1) * size);
        return PageResult.of(items, total, page, size);
    }

    public UUID create(UUID teacherId, UUID courseId, String title, String statementMd, String language,
            String difficulty, int timeLimitMs, int memLimitKb, String initialCode) {
        if (courseId == null) {
            throw ApiException.validation("课程不能为空");
        }
        if (!org.teacherTeachesCourse(teacherId, courseId)) {
            throw ApiException.notFound("你没有该课程的教学班，不能建题");
        }
        validate(title, statementMd, language, difficulty, timeLimitMs, memLimitKb);
        return problems.insert(courseId, title.trim(), statementMd, language, difficulty, timeLimitMs, memLimitKb,
                initialCode, teacherId);
    }

    public ProblemDetail detail(UUID teacherId, UUID problemId) {
        ProblemRepository.ProblemRow problem = requireOwned(teacherId, problemId);
        return new ProblemDetail(problem, problems.listTestCases(problemId));
    }

    public void update(UUID teacherId, UUID problemId, String title, String statementMd, String language,
            String difficulty, int timeLimitMs, int memLimitKb, String initialCode) {
        ProblemRepository.ProblemRow problem = requireOwned(teacherId, problemId);
        requireDraft(problem, "已发布的题目不能直接改题面，请用「另存为新版本」");
        validate(title, statementMd, language, difficulty, timeLimitMs, memLimitKb);
        problems.update(problemId, title.trim(), statementMd, language, difficulty, timeLimitMs, memLimitKb,
                initialCode);
    }

    public void publish(UUID teacherId, UUID problemId) {
        ProblemRepository.ProblemRow problem = requireOwned(teacherId, problemId);
        if (!problem.isDraft()) {
            throw ApiException.conflict("PROBLEM_NOT_DRAFT", "只有草稿才能发布");
        }
        if (problems.countTestCases(problemId) == 0) {
            throw ApiException.validation("发布前至少要有一个测试用例");
        }
        problems.publish(problemId);
    }

    /** FR-PRB-04：已发布题改题面的唯一路径。 */
    public UUID newVersion(UUID teacherId, UUID problemId) {
        ProblemRepository.ProblemRow problem = requireOwned(teacherId, problemId);
        if (!problem.isPublished()) {
            throw ApiException.conflict("PROBLEM_NOT_PUBLISHED", "只有已发布的题目才需要另存新版本");
        }
        return problems.insertNewVersion(problemId, teacherId);
    }

    public void delete(UUID teacherId, UUID problemId) {
        ProblemRepository.ProblemRow problem = requireOwned(teacherId, problemId);
        requireDraft(problem, "已发布的题目不能删（会被作业引用），请改用归档/new-version");
        problems.delete(problemId);
    }

    /** 整体替换用例（PRD §14.2）；只在草稿态允许，避免改动已用于判题的用例。 */
    public void replaceCases(UUID teacherId, UUID problemId, List<CaseInput> cases) {
        ProblemRepository.ProblemRow problem = requireOwned(teacherId, problemId);
        requireDraft(problem, "已发布题目的用例不能改，请另存新版本后再改");
        if (cases == null || cases.isEmpty()) {
            throw ApiException.validation("至少要有一个测试用例");
        }
        if (cases.size() > MAX_CASES) {
            throw ApiException.validation("用例数不能超过 " + MAX_CASES + " 个");
        }
        for (int i = 0; i < cases.size(); i++) {
            CaseInput c = cases.get(i);
            if (c.expected() == null || c.expected().isBlank()) {
                throw ApiException.validation("第 " + (i + 1) + " 个用例的期望输出为空");
            }
        }
        problems.deleteTestCases(problemId);
        for (int i = 0; i < cases.size(); i++) {
            CaseInput c = cases.get(i);
            problems.insertTestCase(problemId, i + 1, c.input() == null ? "" : c.input(), c.expected(), c.hidden(),
                    Math.max(c.score(), 0));
        }
    }

    // ---------------- 学生 ----------------

    public StudentProblemView studentView(UUID studentId, UUID problemId) {
        if (!problems.isVisibleToStudent(problemId, studentId)) {
            throw ApiException.notFound("题目不存在");
        }
        ProblemRepository.ProblemRow p = problems.findById(problemId)
                .orElseThrow(() -> ApiException.notFound("题目不存在"));
        return new StudentProblemView(p.id(), p.courseId(), p.title(), p.statementMd(), p.language(),
                p.difficulty(), p.timeLimitMs(), p.memLimitKb(), p.initialCode());
    }

    // ---------------- 内部 ----------------

    private ProblemRepository.ProblemRow requireOwned(UUID teacherId, UUID problemId) {
        ProblemRepository.ProblemRow problem = problems.findById(problemId)
                .orElseThrow(() -> ApiException.notFound("题目不存在"));
        if (!org.teacherTeachesCourse(teacherId, problem.courseId())) {
            // 不泄露存在性：别人的题目一律 404（PRD §13.5）
            throw ApiException.notFound("题目不存在");
        }
        return problem;
    }

    private void requireDraft(ProblemRepository.ProblemRow problem, String message) {
        if (!problem.isDraft()) {
            throw ApiException.conflict("PROBLEM_LOCKED", message);
        }
    }

    private void validate(String title, String statementMd, String language, String difficulty, int timeLimitMs,
            int memLimitKb) {
        if (title == null || title.isBlank()) {
            throw ApiException.validation("题目标题不能为空");
        }
        if (statementMd == null || statementMd.isBlank()) {
            throw ApiException.validation("题面不能为空");
        }
        if (!LANGUAGES.contains(language)) {
            throw ApiException.validation("语言只能是 java 或 python3");
        }
        if (!DIFFICULTIES.contains(difficulty)) {
            throw ApiException.validation("难度只能是 easy/medium/hard");
        }
        if (timeLimitMs < 100 || timeLimitMs > 10000) {
            throw ApiException.validation("时间限制需在 100~10000 毫秒之间");
        }
        if (memLimitKb < 16384 || memLimitKb > 1048576) {
            throw ApiException.validation("内存限制需在 16MB~1GB 之间");
        }
    }
}
