package com.codelab.app.knowledge;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.common.ApiException;
import com.codelab.app.org.OrgRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 教师维护课程知识条目（FR-QA-02）：草稿不进检索池，发布后 1 分钟内可被检索（无缓存，立即生效）。 */
@Service
public class KnowledgeService {

    private final KnowledgeRepository knowledge;
    private final OrgRepository org;
    private final AuditService audit;

    public KnowledgeService(KnowledgeRepository knowledge, OrgRepository org, AuditService audit) {
        this.knowledge = knowledge;
        this.org = org;
        this.audit = audit;
    }

    public List<KnowledgeRepository.EntryRow> list(UUID teacherId, UUID courseId, String status) {
        requireCourse(teacherId, courseId);
        return knowledge.listByCourse(courseId, status);
    }

    public UUID create(UUID teacherId, UUID courseId, String title, String contentMd, List<String> tags) {
        requireCourse(teacherId, courseId);
        validate(title, contentMd);
        UUID id = knowledge.insert(courseId, title.trim(), contentMd, normalizeTags(tags), teacherId);
        audit.record(teacherId, AuditActions.KNOWLEDGE_CREATE, "knowledge:" + id,
                Map.of("courseId", courseId, "title", title.trim()));
        return id;
    }

    public void update(UUID teacherId, UUID id, String title, String contentMd, List<String> tags) {
        KnowledgeRepository.EntryRow entry = requireOwned(teacherId, id);
        validate(title, contentMd);
        knowledge.update(id, title.trim(), contentMd, normalizeTags(tags));
        audit.record(teacherId, AuditActions.KNOWLEDGE_UPDATE, "knowledge:" + id, Map.of("title", title.trim()));
    }

    public void publish(UUID teacherId, UUID id) {
        requireOwned(teacherId, id);
        knowledge.publish(id);
        audit.record(teacherId, AuditActions.KNOWLEDGE_PUBLISH, "knowledge:" + id, Map.of());
    }

    public void delete(UUID teacherId, UUID id) {
        requireOwned(teacherId, id);
        if (knowledge.delete(id) == 0) {
            throw ApiException.conflict("KNOWLEDGE_LOCKED", "只有草稿可以删除");
        }
        audit.record(teacherId, AuditActions.KNOWLEDGE_DELETE, "knowledge:" + id, Map.of());
    }

    /** 学生提问前的检索（PRD §8.3）：Top N 已发布条目。 */
    public List<KnowledgeRepository.EntryRow> search(UUID courseId, String question, int limit) {
        return knowledge.search(courseId, question, limit);
    }

    public long countPublished(UUID courseId) {
        return knowledge.countPublished(courseId);
    }

    private void requireCourse(UUID teacherId, UUID courseId) {
        if (courseId == null) {
            throw ApiException.validation("courseId 不能为空");
        }
        if (!org.teacherTeachesCourse(teacherId, courseId)) {
            // 不泄露存在性（PRD §13.5）
            throw ApiException.notFound("课程不存在");
        }
    }

    private KnowledgeRepository.EntryRow requireOwned(UUID teacherId, UUID id) {
        KnowledgeRepository.EntryRow entry = knowledge.findById(id)
                .orElseThrow(() -> ApiException.notFound("知识条目不存在"));
        if (!org.teacherTeachesCourse(teacherId, entry.courseId())) {
            throw ApiException.notFound("知识条目不存在");
        }
        return entry;
    }

    private static void validate(String title, String contentMd) {
        if (title == null || title.isBlank()) {
            throw ApiException.validation("标题不能为空");
        }
        if (contentMd == null || contentMd.isBlank()) {
            throw ApiException.validation("内容不能为空");
        }
        if (title.length() > 200) {
            throw ApiException.validation("标题不能超过 200 字");
        }
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.trim().length() > 32 ? tag.trim().substring(0, 32) : tag.trim())
                .distinct()
                .limit(10)
                .toList();
    }
}
