package com.codelab.app.knowledge;

import com.codelab.app.ai.AiChatService;
import com.codelab.app.ai.AiConversationRepository;
import com.codelab.app.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学生：课程知识问答（PRD §15.4）。 */
@RestController
@RequestMapping("/api/student")
public class StudentKnowledgeController {

    private final AiChatService chat;

    public StudentKnowledgeController(AiChatService chat) {
        this.chat = chat;
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question, UUID conversationId) {
    }

    @PostMapping("/courses/{courseId}/ai/ask")
    public AiChatService.ChatAnswer ask(@PathVariable UUID courseId, @Valid @RequestBody AskRequest req) {
        return chat.ask(SecurityUtils.require().id(), courseId, req.conversationId(), req.question());
    }

    @GetMapping("/courses/{courseId}/ai/conversations")
    public List<AiConversationRepository.ConversationRow> conversations(@PathVariable UUID courseId) {
        return chat.conversations(SecurityUtils.require().id(), courseId);
    }

    @GetMapping("/ai/conversations/{id}")
    public List<AiConversationRepository.MessageRow> messages(@PathVariable UUID id) {
        return chat.messages(SecurityUtils.require().id(), id);
    }
}
