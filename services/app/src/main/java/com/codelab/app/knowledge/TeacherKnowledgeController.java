package com.codelab.app.knowledge;

import com.codelab.app.ai.AiChatService;
import com.codelab.app.ai.AiConversationRepository;
import com.codelab.app.common.ApiException;
import com.codelab.app.org.OrgRepository;
import com.codelab.app.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** 教师：知识条目维护 + 本班问答记录（PRD §15.4）。 */
@RestController
@RequestMapping("/api/teacher")
public class TeacherKnowledgeController {

    private final KnowledgeService knowledge;
    private final AiChatService chat;
    private final OrgRepository org;

    public TeacherKnowledgeController(KnowledgeService knowledge, AiChatService chat, OrgRepository org) {
        this.knowledge = knowledge;
        this.chat = chat;
        this.org = org;
    }

    public record IdResponse(UUID id) {
    }

    public record SaveEntryRequest(
            @NotNull UUID courseId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank String contentMd,
            List<String> tags) {
    }

    public record QaPage(List<AiConversationRepository.QaRecord> items, int page, int size) {
    }

    @GetMapping("/knowledge")
    public List<KnowledgeRepository.EntryRow> list(@RequestParam UUID courseId,
            @RequestParam(required = false) String status) {
        return knowledge.list(SecurityUtils.require().id(), courseId, status);
    }

    @PostMapping("/knowledge")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody SaveEntryRequest req) {
        UUID id = knowledge.create(SecurityUtils.require().id(), req.courseId(), req.title(), req.contentMd(),
                req.tags());
        return ResponseEntity.status(201).body(new IdResponse(id));
    }

    @PutMapping("/knowledge/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody SaveEntryRequest req) {
        knowledge.update(SecurityUtils.require().id(), id, req.title(), req.contentMd(), req.tags());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/knowledge/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        knowledge.publish(SecurityUtils.require().id(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/knowledge/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        knowledge.delete(SecurityUtils.require().id(), id);
        return ResponseEntity.noContent().build();
    }

    /** 教师看本教学班学生的知识问答记录（FR-QA-04）。 */
    @GetMapping("/knowledge-qa")
    public QaPage qaRecords(@RequestParam UUID teachingClassId,
            @RequestParam(required = false) UUID studentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID teacherId = SecurityUtils.require().id();
        if (!org.teacherOwnsTeachingClass(teacherId, teachingClassId)) {
            throw ApiException.notFound("教学班不存在");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        var items = chat.qaRecords(teacherId, teachingClassId, studentId, safeSize, (safePage - 1) * safeSize);
        return new QaPage(items, safePage, safeSize);
    }
}
