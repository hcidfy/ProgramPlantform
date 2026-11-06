package com.codelab.app.ai;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.common.ApiException;
import com.codelab.app.common.JsonMini;
import com.codelab.app.knowledge.KnowledgeRepository;
import com.codelab.app.knowledge.KnowledgeService;
import com.codelab.app.org.OrgRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * S2 课程知识问答（PRD §15.4）：检索 Top 5 → 拼上下文 → chat 链 → 引用回填 → 落会话。
 * <p>没有命中就如实说"课程资料里没有"（uncertain=true），不硬编。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private static final int TOP_ENTRIES = 5;
    private static final int HISTORY_MESSAGES = 10;

    public record Citation(UUID entryId, String title, String contentMd) {
    }

    public record ChatAnswer(UUID conversationId, List<String> segments, List<Citation> citations, boolean uncertain,
            AiClient.AiResponse.Usage usage, boolean degraded, String degradedText, boolean noEntries) {
    }

    private final KnowledgeService knowledge;
    private final KnowledgeRepository knowledgeRepo;
    private final OrgRepository org;
    private final AiClient client;
    private final AiQuotaService quotas;
    private final PromptTemplateRepository prompts;
    private final AiConversationRepository conversations;
    private final AuditService audit;

    public AiChatService(KnowledgeService knowledge, KnowledgeRepository knowledgeRepo, OrgRepository org,
            AiClient client, AiQuotaService quotas, PromptTemplateRepository prompts,
            AiConversationRepository conversations, AuditService audit) {
        this.knowledge = knowledge;
        this.knowledgeRepo = knowledgeRepo;
        this.org = org;
        this.client = client;
        this.quotas = quotas;
        this.prompts = prompts;
        this.conversations = conversations;
        this.audit = audit;
    }

    public ChatAnswer ask(UUID studentId, UUID courseId, UUID conversationId, String question) {
        if (question == null || question.isBlank()) {
            throw ApiException.validation("问题不能为空");
        }
        if (question.length() > 500) {
            throw ApiException.validation("问题不能超过 500 字");
        }
        if (!org.studentHasCourse(studentId, courseId)) {
            throw ApiException.notFound("课程不存在");
        }

        UUID targetConversation = conversationId;
        List<AiConversationRepository.MessageRow> history = List.of();
        if (targetConversation != null) {
            var conversation = conversations.findById(targetConversation)
                    .filter(c -> c.userId().equals(studentId) && "chat".equals(c.kind()))
                    .orElseThrow(() -> ApiException.notFound("会话不存在"));
            if (!courseId.equals(conversation.courseId())) {
                throw ApiException.notFound("会话不存在");
            }
            history = conversations.listMessages(targetConversation, HISTORY_MESSAGES);
        }

        List<KnowledgeRepository.EntryRow> entries = knowledge.search(courseId, question, TOP_ENTRIES);
        if (entries.isEmpty()) {
            String reply = "课程资料暂未覆盖这个问题。你可以换用课程资料中的术语提问，或联系教师补充对应知识条目。";
            try {
                UUID conversation = targetConversation != null ? targetConversation
                        : conversations.create(studentId, "chat", courseId, null, null, null);
                conversations.insertMessage(conversation, "user", JsonMini.toJson(Map.of("text", question)), question, 0);
                conversations.insertMessage(conversation, "assistant", JsonMini.toJson(Map.of("text", reply)), reply, 0);
                targetConversation = conversation;
            } catch (RuntimeException ex) {
                log.warn("无知识命中时会话落库失败 course={}: {}", courseId, ex.getMessage());
            }
            return new ChatAnswer(targetConversation, List.of(reply), List.of(), true,
                    new AiClient.AiResponse.Usage(0, 0), false, null, true);
        }
        quotas.assertDailyTokens(studentId);
        int promptVersion = prompts.findActive("chat")
                .map(PromptTemplateRepository.PromptTemplate::version).orElse(1);
        PromptTemplateRepository.PromptTemplate template = prompts.findActive("chat")
                .orElse(new PromptTemplateRepository.PromptTemplate("chat", promptVersion, ""));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("promptTemplate", template.body());
        body.put("promptVersion", template.version());
        body.put("question", question);
        List<Map<String, Object>> entryPayload = new ArrayList<>();
        for (KnowledgeRepository.EntryRow entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.id().toString());
            item.put("title", entry.title());
            item.put("contentMd", entry.contentMd());
            item.put("tags", entry.tags());
            entryPayload.add(item);
        }
        body.put("entries", entryPayload);
        List<Map<String, Object>> historyPayload = new ArrayList<>();
        for (AiConversationRepository.MessageRow row : history) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", row.role());
            item.put("text", row.text() == null ? "" : row.text());
            historyPayload.add(item);
        }
        body.put("history", historyPayload);

        AiClient.AiResponse response = client.call("chat", body);
        Map<String, Object> payload = response.payload() == null ? Map.of() : response.payload();
        List<String> segments = stringList(payload.get("segments"));
        if (response.degraded() && (response.degradedText() != null && !response.degradedText().isBlank())) {
            segments = List.of(response.degradedText());
        }
        boolean uncertain = Boolean.TRUE.equals(payload.get("uncertain")) || entries.isEmpty();

        List<Citation> citations = new ArrayList<>();
        for (String id : stringList(payload.get("citations"))) {
            entries.stream()
                    .filter(entry -> entry.id().toString().equals(id))
                    .findFirst()
                    .ifPresent(entry -> citations.add(new Citation(entry.id(), entry.title(), entry.contentMd())));
        }

        AiClient.AiResponse.Usage usage = response.usage() == null
                ? new AiClient.AiResponse.Usage(0, 0) : response.usage();

        try {
            UUID conversation = targetConversation != null ? targetConversation
                    : conversations.create(studentId, "chat", courseId, null, null, null);
            conversations.insertMessage(conversation, "user", JsonMini.toJson(Map.of("text", question)), question, 0);
            conversations.insertMessage(conversation, "assistant", JsonMini.toJson(payload),
                    String.join("\n", segments), usage.promptTokens() + usage.completionTokens());
            targetConversation = conversation;
        } catch (RuntimeException ex) {
            log.warn("知识问答会话落库失败 course={}: {}", courseId, ex.getMessage());
        }

        quotas.charge(studentId, null, courseId, "chat", null, null, usage.promptTokens(), usage.completionTokens(), true,
                false);
        audit.record(studentId, AuditActions.AI_CALL, "course:" + courseId,
                Map.of("task", "chat", "entries", entries.size(), "uncertain", uncertain));

        return new ChatAnswer(targetConversation, segments, citations, uncertain, usage, response.degraded(),
                response.degradedText(), entries.isEmpty());
    }

    public List<AiConversationRepository.ConversationRow> conversations(UUID studentId, UUID courseId) {
        if (!org.studentHasCourse(studentId, courseId)) {
            throw ApiException.notFound("课程不存在");
        }
        return conversations.listChatConversations(studentId, courseId, 20);
    }

    public List<AiConversationRepository.MessageRow> messages(UUID studentId, UUID conversationId) {
        var conversation = conversations.findById(conversationId)
                .filter(c -> c.userId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        return conversations.listMessages(conversation.id(), 40);
    }

    /** 教师看本班学生的问答记录（FR-QA-04）；调用即留审计。 */
    public java.util.List<AiConversationRepository.QaRecord> qaRecords(UUID teacherId, UUID teachingClassId,
            UUID studentId, int limit, int offset) {
        var rows = conversations.listQaForTeachingClass(teachingClassId, studentId, limit, offset);
        audit.record(teacherId, AuditActions.TEACHER_VIEW_QA, "teaching_class:" + teachingClassId,
                Map.of("studentId", studentId == null ? "" : studentId, "count", rows.size()));
        return rows;
    }

    public long publishedEntryCount(UUID courseId) {
        return knowledgeRepo.countPublished(courseId);
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
