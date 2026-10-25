package com.codelab.app.assignment;

import com.codelab.app.security.SecurityUtils;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学生作业端：只返回本班已发布作业（PRD §14.5）。 */
@RestController
@RequestMapping("/api/student/assignments")
public class StudentAssignmentController {

    private final AssignmentService assignments;

    public StudentAssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping
    public List<AssignmentService.StudentAssignmentView> list() {
        return assignments.listForStudent(SecurityUtils.require().id());
    }

    @GetMapping("/{id}")
    public AssignmentService.StudentAssignmentView detail(@PathVariable UUID id) {
        return assignments.detailForStudent(SecurityUtils.require().id(), id);
    }
}
