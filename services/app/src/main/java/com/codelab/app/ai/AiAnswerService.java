package com.codelab.app.ai;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.common.ApiException;
import com.codelab.app.common.JsonMini;
import com.codelab.app.problem.ProblemRepository;
import com.codelab.app.submission.SubmissionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * S1 三条链的业务编排（PRD §15.3）：闸门 → 缓存 → 配额 → 调 ai → 护栏 → 落库/计量。
 * <p>"AI 只讲不替学生写"由两层保证：ai 侧 key_snippet ≤5 行，这一层再校验一次。
 */
@Service
public class AiAnswerService {

    private static final Logger log = LoggerFactory.getLogger(AiAnswerService.class);
    private static final int MAX_SNIPPET_LINES = 5;
    private static final int MAX_SOURCE_CHARS = 6000;

    public record Remaining(long perProblemCalls, int perProblemCallLimit, long dailyTokens, int dailyTokenLimit) {
    }

    public record AiAnswer(String task, int promptVersion, Map<String, Object> payload, boolean cached,
            AiClient.AiResponse.Usage usage, Remaining remaining, boolean degraded, String degradedText) {
    }

    private final AiGateService gate;
    private final AiQuotaService quotas;
    private final AiCacheService cache;
    private final AiClient client;
    private final PromptTemplateRepository prompts;
    private final AiConversationRepository conversations;
    private final SubmissionRepository submissions;
    private final AuditService audit;

    public AiAnswerService(AiGateService gate, AiQuotaService quotas, AiCacheService cache, AiClient client,
            PromptTemplateRepository prompts, AiConversationRepository conversations, SubmissionRepository submissions,
            AuditService audit) {
        this.gate = gate;
        this.quotas = quotas;
        this.cache = cache;
        this.client = client;
        this.prompts = prompts;
        this.conversations = conversations;
        this.submissions = submissions;
        this.audit = audit;
    }

    public AiAnswer explain(UUID studentId, UUID problemId, UUID submissionId, String question, boolean forceRefresh) {
        return runTask(studentId, problemId, submissionId, "explain", null, question, forceRefresh);
    }

    public AiAnswer hint(UUID studentId, UUID problemId, UUID submissionId, int level, boolean forceRefresh) {
        if (level < 1 || level > 3) {
            throw ApiException.validation("思路等级只能是 1/2/3");
        }
        return runTask(studentId, problemId, submissionId, "hint", level, null, forceRefresh);
    }

    public AiAnswer analyze(UUID studentId, UUID problemId, UUID submissionId, boolean forceRefresh) {
        return runTask(studentId, problemId, submissionId, "analyze", null, null, forceRefresh);
    }

    // ---------------- 内部 ----------------

    private AiAnswer runTask(UUID studentId, UUID problemId, UUID submissionId, String task, Integer level,
            String question, boolean forceRefresh) {
        ProblemRepository.ProblemRow problem = gate.requireVisible(studentId, problemId);
        SubmissionRepository.SubmissionRow submission = gate.requireSubmission(studentId, problemId, submissionId);

        if ("hint".equals(task)) {
            if ("accepted".equals(submission.result())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "AI_HINT_CLOSED", "这道题已经通过，思路提示已关闭");
            }
            int unlocked = conversations.countHintLevels(studentId, problemId);
            if (level > unlocked + 1) {
                throw ApiException.conflict("AI_HINT_LOCKED", "请先解锁第 " + (unlocked + 1) + " 级思路");
            }
        }

        int promptVersion = prompts.findActive(task).map(PromptTemplateRepository.PromptTemplate::version).orElse(1);
        String cacheKey = AiCacheService.key(task, submission.id(), level, promptVersion);
        String forceKey = task + ":" + submission.id();

        if (!forceRefresh) {
            Optional<AiCacheService.CachedAnswer> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                return toAnswer(task, cached.get().promptVersion(), cached.get().payload(), true,
                        cached.get().usage(), studentId, problemId, null);
            }
            if ("hint".equals(task)) {
                // 同一题同一级不重复计费：库里已有就直接复用
                Optional<String> stored = conversations.findHintAnswer(studentId, problemId, level);
                if (stored.isPresent()) {
                    Map<String, Object> payload = JsonMini.parseObject(stored.get());
                    if (payload != null) {
                        return toAnswer(task, promptVersion, payload, true,
                                new AiClient.AiResponse.Usage(0, 0), studentId, problemId, null);
                    }
                }
            }
        } else if (!cache.tryAcquireForceRefresh(forceKey)) {
            Optional<AiCacheService.CachedAnswer> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                return toAnswer(task, cached.get().promptVersion(), cached.get().payload(), true,
                        cached.get().usage(), studentId, problemId, null);
            }
        }

        quotas.assertCanSpend(studentId, problemId, task);

        PromptTemplateRepository.PromptTemplate template = prompts.findActive(task)
                .orElse(new PromptTemplateRepository.PromptTemplate(task, promptVersion, ""));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("promptTemplate", template.body());
        body.put("promptVersion", template.version());
        body.put("problem", problemMap(problem));
        body.put("submission", submissionMap(submission));
        if ("explain".equals(task)) {
            body.put("question", question == null ? "" : question);
        }
        if ("hint".equals(task)) {
            body.put("level", level);
        }

        AiClient.AiResponse response = client.call(task, body);
        Map<String, Object> payload = response.payload() == null ? Map.of() : response.payload();
        if (!response.degraded()) {
            payload = enforceSnippetLimit(payload);
        }
        AiClient.AiResponse.Usage usage = response.usage() == null
                ? new AiClient.AiResponse.Usage(0, 0)
                : response.usage();

        persist(studentId, problem.id(), submission.id(), task, level, question, payload, response, usage);
        quotas.charge(studentId, problemId, problem.courseId(), task, level, submission.id(), usage.promptTokens(),
                usage.completionTokens(), true, false);
        cache.put(cacheKey, new AiCacheService.CachedAnswer(payload, usage, template.version(),
                java.time.Instant.now()));
        audit.record(studentId, AuditActions.AI_CALL, "problem:" + problemId,
                Map.of("task", task, "level", level == null ? 0 : level, "submissionId", submission.id(),
                        "promptTokens", usage.promptTokens(), "completionTokens", usage.completionTokens()));

        return toAnswer(task, template.version(), payload, false, usage, studentId, problemId, response);
    }

    private AiAnswer toAnswer(String task, int promptVersion, Map<String, Object> payload, boolean cached,
            AiClient.AiResponse.Usage usage, UUID studentId, UUID problemId, AiClient.AiResponse response) {
        AiQuotaService.Snapshot snapshot = quotas.snapshot(studentId, problemId);
        Remaining remaining = new Remaining(snapshot.perProblemCalls(), snapshot.perProblemCallLimit(),
                snapshot.dailyTokens(), snapshot.dailyTokenLimit());
        boolean degraded = response != null && response.degraded();
        String degradedText = response == null ? null : response.degradedText();
        return new AiAnswer(task, promptVersion, payload, cached, usage, remaining, degraded, degradedText);
    }

    private void persist(UUID studentId, UUID problemId, UUID submissionId, String task, Integer level,
            String question, Map<String, Object> payload, AiClient.AiResponse response,
            AiClient.AiResponse.Usage usage) {
        try {
            UUID conversationId = conversations.create(studentId, task, null, problemId, submissionId, level);
            String questionText = question == null || question.isBlank() ? "（点了「" + taskLabel(task) + "」）" : question;
            conversations.insertMessage(conversationId, "user", JsonMini.toJson(Map.of("text", questionText)),
                    questionText, 0);
            String answerText = response.degraded()
                    ? String.valueOf(response.degradedText())
                    : humanize(task, payload);
            conversations.insertMessage(conversationId, "assistant", JsonMini.toJson(payload), answerText,
                    usage.promptTokens() + usage.completionTokens());
        } catch (RuntimeException ex) {
            // 落库失败不能把已经花掉的回答吞掉，只告警
            log.warn("AI 会话落库失败 submission={} task={}: {}", submissionId, task, ex.getMessage());
        }
    }

    private static String taskLabel(String task) {
        return switch (task) {
            case "explain" -> "问 AI 为什么错";
            case "hint" -> "给我思路";
            case "analyze" -> "分析正确结果";
            default -> task;
        };
    }

    private static String humanize(String task, Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        switch (task) {
            case "explain" -> {
                sb.append(payload.getOrDefault("cause", "")).append('\n');
                sb.append(payload.getOrDefault("why", "")).append('\n');
                sb.append("下一步：").append(payload.getOrDefault("next_step", "")).append('\n');
                Object snippet = payload.get("key_snippet");
                if (snippet != null && !String.valueOf(snippet).isBlank()) {
                    sb.append("片段：\n").append(snippet);
                }
            }
            case "hint" -> {
                sb.append("L").append(payload.getOrDefault("level", 1)).append(" 概念：")
                        .append(payload.getOrDefault("concept", "")).append('\n');
                sb.append("方向：").append(payload.getOrDefault("direction", "")).append('\n');
                sb.append("骨架：\n").append(payload.getOrDefault("outline", ""));
            }
            case "analyze" -> {
                sb.append(payload.getOrDefault("diagnosis", "")).append('\n');
                sb.append("差距：").append(payload.getOrDefault("gap", "")).append('\n');
                sb.append("改法：").append(payload.getOrDefault("fix_direction", ""));
            }
            default -> sb.append(payload);
        }
        return sb.toString().trim();
    }

    /** 二次护栏：key_snippet 硬截到 5 行（PDF §8.5 服务端约束）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> enforceSnippetLimit(Map<String, Object> payload) {
        Object snippet = payload.get("key_snippet");
        if (snippet instanceof String text) {
            String[] lines = text.replace("\r\n", "\n").split("\n", -1);
            if (lines.length > MAX_SNIPPET_LINES) {
                Map<String, Object> copy = new LinkedHashMap<>(payload);
                copy.put("key_snippet", String.join("\n",
                        java.util.Arrays.copyOf(lines, MAX_SNIPPET_LINES)));
                return copy;
            }
        }
        return payload;
    }

    private static Map<String, Object> problemMap(ProblemRepository.ProblemRow problem) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", problem.title());
        map.put("statement", summarize(problem.statementMd()));
        map.put("language", problem.language());
        map.put("timeLimitMs", problem.timeLimitMs());
        map.put("memLimitKb", problem.memLimitKb());
        return map;
    }

    /** 题面只传摘要：前 800 字 + 限制条件（PRD §8.4）。 */
    private static String summarize(String statementMd) {
        if (statementMd == null) {
            return "";
        }
        String text = statementMd.strip();
        return text.length() <= 800 ? text : text.substring(0, 800) + "…";
    }

    private Map<String, Object> submissionMap(SubmissionRepository.SubmissionRow submission) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", submission.id().toString());
        map.put("status", submission.status());
        map.put("result", submission.result());
        map.put("passedCases", submission.passedCases());
        map.put("totalCases", submission.totalCases());
        map.put("sourceCode", sliceSource(submission));
        map.put("compileOutput", submission.compileOutput());
        List<Map<String, Object>> cases = new ArrayList<>();
        SubmissionRepository.CaseRow firstFailed = null;
        for (SubmissionRepository.CaseRow row : gate.caseRows(submission.id())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", row.caseSeq());
            item.put("status", row.status());
            item.put("timeMs", row.timeMs());
            item.put("hidden", row.isHidden());
            if (!row.isHidden() && !"ac".equals(row.status()) && firstFailed == null) {
                firstFailed = row;
            }
            cases.add(item);
        }
        map.put("caseSummaries", cases);
        if (firstFailed != null) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("seq", firstFailed.caseSeq());
            fail.put("expected", firstFailed.expectedExcerpt());
            fail.put("actual", firstFailed.actualExcerpt());
            map.put("firstFailCase", fail);
        }
        return map;
    }

    /** 只传必要片段（PRD §8.4）：编译错误附近的 40 行，否则前 120 行。 */
    private static String sliceSource(SubmissionRepository.SubmissionRow submission) {
        String source = submission.sourceCode() == null ? "" : submission.sourceCode();
        String[] lines = source.replace("\r\n", "\n").split("\n", -1);
        Integer errorLine = extractLine(submission.compileOutput());
        List<String> picked;
        if (errorLine != null) {
            int start = Math.max(0, errorLine - 1 - 20);
            int end = Math.min(lines.length, errorLine + 20);
            picked = List.of(lines).subList(start, end);
        } else {
            int end = Math.min(lines.length, 120);
            picked = List.of(lines).subList(0, end);
        }
        String text = String.join("\n", picked);
        return text.length() <= MAX_SOURCE_CHARS ? text : text.substring(0, MAX_SOURCE_CHARS);
    }

    private static Integer extractLine(String compileOutput) {
        if (compileOutput == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("(?:Main\\.java|main\\.py)[:\\s]+(\\d+)")
                .matcher(compileOutput);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public Optional<SubmissionRepository.SubmissionRow> latestSubmission(UUID studentId, UUID problemId) {
        return submissions.listForStudent(studentId, problemId, null, 1, 0).stream().findFirst();
    }
}
