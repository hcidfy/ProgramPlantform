package com.codelab.app.submission;

import com.codelab.app.security.SecurityUtils;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 教师重判（PRD §14.4）。 */
@RestController
@RequestMapping("/api/teacher/submissions")
public class TeacherSubmissionController {

    private final SubmissionService submissions;

    public TeacherSubmissionController(SubmissionService submissions) {
        this.submissions = submissions;
    }

    @GetMapping("/{id}")
    public SubmissionService.SubmissionView detail(@PathVariable UUID id) {
        return submissions.teacherDetail(SecurityUtils.require().id(), id);
    }

    @PostMapping("/{id}/rejudge")
    public SubmissionService.SubmitResult rejudge(@PathVariable UUID id) {
        return submissions.rejudge(SecurityUtils.require().id(), id);
    }
}
