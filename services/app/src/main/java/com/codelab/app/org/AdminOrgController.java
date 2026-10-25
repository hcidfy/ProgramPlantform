package com.codelab.app.org;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.auth.AuthService;
import com.codelab.app.auth.UserImportService;
import com.codelab.app.auth.UserRepository;
import com.codelab.app.common.PageResult;
import com.codelab.app.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端：账号 / 班级 / 课程 / 教学班 / 入班（PRD §13.2）。 */
@RestController
@RequestMapping("/api/admin")
public class AdminOrgController {

    private final OrgService org;
    private final AuthService auth;
    private final UserRepository users;
    private final UserImportService importService;

    public AdminOrgController(OrgService org, AuthService auth, UserRepository users,
            UserImportService importService) {
        this.org = org;
        this.auth = auth;
        this.users = users;
        this.importService = importService;
    }

    public record IdResponse(UUID id) {
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Pattern(regexp = "student|teacher|admin", message = "只能是 student/teacher/admin") String role,
            @NotBlank String initialPassword) {
    }

    public record CreateClassRequest(@NotBlank @Size(max = 64) String name, Integer gradeYear,
            @Size(max = 64) String major) {
    }

    public record CreateCourseRequest(@NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 128) String name,
            String description) {
    }

    public record CreateTeachingClassRequest(@NotNull UUID classId, @NotNull UUID courseId, @NotNull UUID teacherId,
            @NotBlank @Size(max = 16) String term) {
    }

    public record EnrollRequest(@NotNull UUID studentId, @NotNull UUID classId) {
    }

    // ---------------- 账号 ----------------

    @GetMapping("/users")
    public PageResult<AuthService.UserView> listUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long total = users.count(role, q);
        List<AuthService.UserView> items = users.list(role, q, safeSize, (safePage - 1) * safeSize).stream()
                .map(AuthService.UserView::of)
                .toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    @PostMapping("/users")
    public ResponseEntity<IdResponse> createUser(@Valid @RequestBody CreateUserRequest req) {
        AuthService.UserView view = auth.createUser(SecurityUtils.require().id(), req.email(), req.displayName(),
                req.role(), req.initialPassword(), AuditActions.ADMIN_CREATE_USER);
        return ResponseEntity.status(201).body(new IdResponse(view.id()));
    }

    @PostMapping(value = "/users/import", consumes = {"text/csv", "text/plain"})
    public UserImportService.ImportResult importUsers(@RequestBody String csv) {
        return importService.importCsv(SecurityUtils.require().id(), csv);
    }

    // ---------------- 班级 / 课程 / 教学班 / 入班 ----------------

    @GetMapping("/school-classes")
    public List<OrgRepository.SchoolClassRow> listClasses() {
        return org.allClasses();
    }

    @PostMapping("/school-classes")
    public ResponseEntity<IdResponse> createClass(@Valid @RequestBody CreateClassRequest req) {
        UUID id = org.createClass(SecurityUtils.require().id(), req.name(), req.gradeYear(), req.major());
        return ResponseEntity.status(201).body(new IdResponse(id));
    }

    @GetMapping("/courses")
    public List<OrgRepository.CourseRow> listCourses() {
        return org.allCourses();
    }

    @PostMapping("/courses")
    public ResponseEntity<IdResponse> createCourse(@Valid @RequestBody CreateCourseRequest req) {
        UUID id = org.createCourse(SecurityUtils.require().id(), req.code(), req.name(), req.description());
        return ResponseEntity.status(201).body(new IdResponse(id));
    }

    @GetMapping("/teaching-classes")
    public List<OrgRepository.TeachingClassRow> listTeachingClasses() {
        return org.allTeachingClasses();
    }

    @PostMapping("/teaching-classes")
    public ResponseEntity<IdResponse> createTeachingClass(@Valid @RequestBody CreateTeachingClassRequest req) {
        UUID id = org.createTeachingClass(SecurityUtils.require().id(), req.classId(), req.courseId(),
                req.teacherId(), req.term());
        return ResponseEntity.status(201).body(new IdResponse(id));
    }

    @GetMapping("/enrollments")
    public List<OrgRepository.StudentRow> listEnrollments(@RequestParam UUID classId) {
        return org.studentsInClass(classId);
    }

    @PostMapping("/enrollments")
    public ResponseEntity<Void> enroll(@Valid @RequestBody EnrollRequest req) {
        org.enroll(SecurityUtils.require().id(), req.studentId(), req.classId());
        return ResponseEntity.noContent().build();
    }
}
