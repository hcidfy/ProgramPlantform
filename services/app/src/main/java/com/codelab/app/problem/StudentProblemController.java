package com.codelab.app.problem;

import com.codelab.app.security.SecurityUtils;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学生看题：只有"本班已发布作业里的题"可见，隐藏用例永不下发（PRD §14.5）。 */
@RestController
@RequestMapping("/api/student/problems")
public class StudentProblemController {

    private final ProblemService problems;

    public StudentProblemController(ProblemService problems) {
        this.problems = problems;
    }

    @GetMapping("/{id}")
    public ProblemService.StudentProblemView detail(@PathVariable UUID id) {
        return problems.studentView(SecurityUtils.require().id(), id);
    }
}
