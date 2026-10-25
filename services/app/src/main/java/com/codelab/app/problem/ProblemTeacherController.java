package com.codelab.app.problem;

import com.codelab.app.common.PageResult;
import com.codelab.app.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 教师题库端（PRD §14.2）。 */
@RestController
@RequestMapping("/api/teacher/problems")
public class ProblemTeacherController {

    private final ProblemService problems;

    public ProblemTeacherController(ProblemService problems) {
        this.problems = problems;
    }

    public record IdResponse(UUID id) {
    }

    public record SaveProblemRequest(
            @NotNull UUID courseId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank String statementMd,
            @NotBlank @Pattern(regexp = "java|python3", message = "只能是 java 或 python3") String language,
            @NotBlank @Pattern(regexp = "easy|medium|hard", message = "只能是 easy/medium/hard") String difficulty,
            @NotNull Integer timeLimitMs,
            @NotNull Integer memLimitKb,
            String initialCode) {
    }

    public record CaseItem(String input, @NotBlank String expected, Boolean hidden, Integer score) {
    }

    public record CasesRequest(@NotNull List<CaseItem> cases) {
    }

    @GetMapping
    public PageResult<ProblemRepository.ProblemRow> list(
            @RequestParam(required = false) UUID courseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return problems.list(SecurityUtils.require().id(), courseId, status, q, safePage, safeSize);
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody SaveProblemRequest req) {
        UUID id = problems.create(SecurityUtils.require().id(), req.courseId(), req.title(), req.statementMd(),
                req.language(), req.difficulty(), req.timeLimitMs(), req.memLimitKb(), req.initialCode());
        return ResponseEntity.status(201).body(new IdResponse(id));
    }

    @GetMapping("/{id}")
    public ProblemService.ProblemDetail detail(@PathVariable UUID id) {
        return problems.detail(SecurityUtils.require().id(), id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody SaveProblemRequest req) {
        problems.update(SecurityUtils.require().id(), id, req.title(), req.statementMd(), req.language(),
                req.difficulty(), req.timeLimitMs(), req.memLimitKb(), req.initialCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        problems.publish(SecurityUtils.require().id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/new-version")
    public ResponseEntity<IdResponse> newVersion(@PathVariable UUID id) {
        UUID newId = problems.newVersion(SecurityUtils.require().id(), id);
        return ResponseEntity.status(201).body(new IdResponse(newId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        problems.delete(SecurityUtils.require().id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/test-cases")
    public List<ProblemRepository.TestCaseRow> testCases(@PathVariable UUID id) {
        return problems.detail(SecurityUtils.require().id(), id).testCases();
    }

    @PutMapping("/{id}/test-cases")
    public ResponseEntity<Void> replaceTestCases(@PathVariable UUID id, @Valid @RequestBody CasesRequest req) {
        List<ProblemService.CaseInput> items = req.cases().stream()
                .map(c -> new ProblemService.CaseInput(c.input(), c.expected(),
                        Boolean.TRUE.equals(c.hidden()), c.score() == null ? 0 : c.score()))
                .toList();
        problems.replaceCases(SecurityUtils.require().id(), id, items);
        return ResponseEntity.noContent().build();
    }
}
