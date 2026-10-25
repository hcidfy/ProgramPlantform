package com.codelab.app.org;

import com.codelab.app.security.SecurityUtils;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学生端：只看本班教学班；换 id 试探别的教学班 → 404（PRD §13.4）。 */
@RestController
@RequestMapping("/api/student")
public class StudentController {

    private final OrgService org;

    public StudentController(OrgService org) {
        this.org = org;
    }

    @GetMapping("/courses")
    public OrgService.StudentCourses myCourses() {
        return org.studentCourses(SecurityUtils.require().id());
    }

    @GetMapping("/courses/{id}")
    public OrgRepository.TeachingClassRow course(@PathVariable UUID id) {
        return org.studentCourse(SecurityUtils.require().id(), id);
    }
}
