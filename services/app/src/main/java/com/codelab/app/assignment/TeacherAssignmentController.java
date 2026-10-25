package com.codelab.app.assignment;

import com.codelab.app.security.SecurityUtils;
import com.codelab.app.submission.SubmissionRepository;
import com.codelab.app.submission.SubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** 教师作业端（PRD §14.4）。 */
@RestController
@RequestMapping("/api/teacher/assignments")
public class TeacherAssignmentController {

    private final AssignmentService assignments;
    private final SubmissionService submissions;

    public TeacherAssignmentController(AssignmentService assignments, SubmissionService submissions) {
        this.assignments = assignments;
        this.submissions = submissions;
    }

    public record IdResponse(UUID id) {
    }

    public record ProblemItem(@NotNull UUID problemId, Integer score) {
    }

    public record SaveAssignmentRequest(
            @NotNull UUID teachingClassId,
            @NotBlank @Size(max = 200) String title,
            String description,
            OffsetDateTime deadline,
            @NotNull @Size(min = 1) List<ProblemItem> problems) {
    }

    public record UpdateAssignmentRequest(
            @NotBlank @Size(max = 200) String title,
            String description,
            OffsetDateTime deadline,
            List<ProblemItem> problems) {
    }

    @GetMapping
    public List<AssignmentService.TeacherAssignmentView> list(@RequestParam(required = false) UUID teachingClassId) {
        UUID teacherId = SecurityUtils.require().id();
        if (teachingClassId != null) {
            return assignments.listForTeacher(teacherId, teachingClassId);
        }
        Map<UUID, AssignmentService.TeacherAssignmentView> merged = new LinkedHashMap<>();
        for (UUID tcId : teacherClassIds()) {
            for (AssignmentService.TeacherAssignmentView view : assignments.listForTeacher(teacherId, tcId)) {
                merged.putIfAbsent(view.assignment().id(), view);
            }
        }
        return List.copyOf(merged.values());
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody SaveAssignmentRequest req) {
        UUID id = assignments.create(SecurityUtils.require().id(), req.teachingClassId(), req.title(),
                req.description(), req.deadline(), toInputs(req.problems()));
        return ResponseEntity.status(201).body(new IdResponse(id));
    }

    @GetMapping("/{id}")
    public AssignmentService.TeacherAssignmentView detail(@PathVariable UUID id) {
        return assignments.teacherDetail(SecurityUtils.require().id(), id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody UpdateAssignmentRequest req) {
        assignments.update(SecurityUtils.require().id(), id, req.title(), req.description(), req.deadline(),
                req.problems() == null ? null : toInputs(req.problems()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        assignments.publish(SecurityUtils.require().id(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        assignments.delete(SecurityUtils.require().id(), id);
        return ResponseEntity.noContent().build();
    }

    /** 教师看本教学班学生提交（含代码），会写审计 teacher.view_code。 */
    @GetMapping("/{id}/submissions")
    public List<SubmissionRepository.TeacherSubmissionRow> submissions(@PathVariable UUID id,
            @RequestParam(required = false) UUID problemId) {
        return submissions.listForTeacher(SecurityUtils.require().id(), id, problemId);
    }

    private static List<AssignmentService.ProblemScoreInput> toInputs(List<ProblemItem> items) {
        return items.stream().map(i -> new AssignmentService.ProblemScoreInput(i.problemId(), i.score())).toList();
    }

    private List<UUID> teacherClassIds() {
        return assignments.teachingClassIdsOf(SecurityUtils.require().id());
    }
}
