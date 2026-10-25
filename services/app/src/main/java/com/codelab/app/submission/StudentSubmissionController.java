package com.codelab.app.submission;

import com.codelab.app.common.PageResult;
import com.codelab.app.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 学生提交端（PRD §14.5）。 */
@RestController
@RequestMapping("/api/student/submissions")
public class StudentSubmissionController {

    private final SubmissionService submissions;

    public StudentSubmissionController(SubmissionService submissions) {
        this.submissions = submissions;
    }

    public record SubmitRequest(
            @NotNull UUID problemId,
            UUID assignmentId,
            @NotBlank String sourceCode) {
    }

    @PostMapping
    public ResponseEntity<SubmissionService.SubmitResult> submit(@Valid @RequestBody SubmitRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var result = submissions.submit(SecurityUtils.require().id(), req.assignmentId(), req.problemId(),
                req.sourceCode(), idempotencyKey);
        return ResponseEntity.status(result.duplicate() ? 200 : 201).body(result);
    }

    @GetMapping("/{id}")
    public SubmissionService.SubmissionView detail(@PathVariable UUID id) {
        return submissions.detail(SecurityUtils.require().id(), id);
    }

    @GetMapping
    public PageResult<SubmissionService.SubmissionSummary> list(
            @RequestParam(required = false) UUID problemId,
            @RequestParam(required = false) UUID assignmentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return submissions.list(SecurityUtils.require().id(), problemId, assignmentId, safePage, safeSize);
    }
}
