package com.codelab.app.org;

import com.codelab.app.security.SecurityUtils;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 教师端：只看自己任课的教学班；非本人一律 404（PRD §13.3）。 */
@RestController
@RequestMapping("/api/teacher")
public class TeacherController {

    private final OrgService org;

    public TeacherController(OrgService org) {
        this.org = org;
    }

    @GetMapping("/teaching-classes")
    public List<OrgRepository.TeachingClassRow> myTeachingClasses() {
        return org.teacherClasses(SecurityUtils.require().id());
    }

    @GetMapping("/teaching-classes/{id}")
    public OrgService.TeacherClassDetail teachingClass(@PathVariable UUID id) {
        return org.teacherClassDetail(SecurityUtils.require().id(), id);
    }
}
