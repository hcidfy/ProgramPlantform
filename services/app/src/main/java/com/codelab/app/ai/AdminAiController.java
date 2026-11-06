package com.codelab.app.ai;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.auth.UserRepository;
import com.codelab.app.common.ApiException;
import com.codelab.app.config.AppProperties;
import com.codelab.app.org.OrgRepository;
import com.codelab.app.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端 AI 配额和用量（PRD §11.4）。 */
@RestController
@RequestMapping("/api/admin/ai")
public class AdminAiController {

    public record QuotaRequest(@NotBlank String scope, UUID scopeId,
            @Min(1) int dailyTokens, @Min(1) int perProblemCalls) {
    }

    public record QuotaView(String scope, UUID scopeId, String targetName, int dailyTokens, int perProblemCalls) {
    }

    public record QuotasResponse(QuotaView configuredDefaults, List<QuotaView> overrides) {
    }

    public record UsageTotals(long calls, long promptTokens, long completionTokens, long totalTokens) {
        static UsageTotals from(AiQuotaRepository.UsageTotals row) {
            return new UsageTotals(row.calls(), row.promptTokens(), row.completionTokens(), row.totalTokens());
        }
    }

    public record UsageDashboard(UsageTotals today, UsageTotals month,
            List<AiQuotaRepository.TaskUsage> byTaskToday,
            List<AiQuotaRepository.CourseUsage> byCourseMonth,
            List<AiQuotaRepository.StudentUsage> topStudentsMonth,
            boolean costEstimateAvailable, Long estimatedCostCents,
            long inputPriceCentsPerMillion, long outputPriceCentsPerMillion) {
    }

    private final AiQuotaRepository quotaRepo;
    private final AiQuotaService quotaService;
    private final OrgRepository org;
    private final UserRepository users;
    private final AuditService audit;
    private final AppProperties properties;

    public AdminAiController(AiQuotaRepository quotaRepo, AiQuotaService quotaService, OrgRepository org,
            UserRepository users, AuditService audit, AppProperties properties) {
        this.quotaRepo = quotaRepo;
        this.quotaService = quotaService;
        this.org = org;
        this.users = users;
        this.audit = audit;
        this.properties = properties;
    }

    @GetMapping("/quotas")
    public QuotasResponse quotas() {
        SecurityUtils.requireRole("admin");
        var defaults = quotaService.configuredDefaults();
        var global = quotaRepo.find("global", null)
                .map(this::quotaView)
                .orElse(new QuotaView("global", null, "全局默认", defaults.dailyTokens(), defaults.perProblemCalls()));
        return new QuotasResponse(global,
                quotaRepo.listAll().stream().map(this::quotaView).toList());
    }

    @PutMapping("/quotas")
    public QuotaView updateQuota(@Valid @RequestBody QuotaRequest request) {
        var admin = SecurityUtils.requireRole("admin");
        String scope = request.scope().trim().toLowerCase();
        UUID scopeId = request.scopeId();
        if ("global".equals(scope)) {
            if (scopeId != null) throw ApiException.validation("全局配额的 scopeId 必须为空");
        } else if ("teaching_class".equals(scope)) {
            if (scopeId == null || org.findTeachingClassById(scopeId).isEmpty()) {
                throw ApiException.validation("教学班不存在");
            }
        } else if ("student".equals(scope)) {
            if (scopeId == null) throw ApiException.validation("学生配额需要 scopeId");
            var user = users.findById(scopeId).orElseThrow(() -> ApiException.validation("学生账号不存在"));
            if (!"student".equals(user.role())) throw ApiException.validation("账号不是学生");
        } else {
            throw ApiException.validation("配额范围只能是 global、teaching_class 或 student");
        }

        quotaRepo.save(scope, scopeId, request.dailyTokens(), request.perProblemCalls());
        audit.record(admin.id(), AuditActions.ADMIN_AI_QUOTA_UPDATE,
                scopeId == null ? "quota:global" : "quota:" + scope + ":" + scopeId,
                Map.of("scope", scope, "scopeId", scopeId == null ? "" : scopeId,
                        "dailyTokens", request.dailyTokens(), "perProblemCalls", request.perProblemCalls()));
        return quotaRepo.find(scope, scopeId).map(this::quotaView)
                .orElse(new QuotaView(scope, scopeId, targetName(scope, scopeId), request.dailyTokens(), request.perProblemCalls()));
    }

    private QuotaView quotaView(AiQuotaRepository.QuotaRow row) {
        return new QuotaView(row.scope(), row.scopeId(), targetName(row.scope(), row.scopeId()), row.dailyTokens(), row.perProblemCalls());
    }

    private String targetName(String scope, UUID id) {
        if (id == null) return "全局默认";
        if ("student".equals(scope)) {
            return users.findById(id).map(user -> user.displayName() + " · " + user.email()).orElse("学生账号已不存在");
        }
        return org.findTeachingClassById(id)
                .map(tc -> tc.className() + " · " + tc.courseName() + " · " + tc.term())
                .orElse("教学班已不存在");
    }

    @GetMapping("/usage")
    public UsageDashboard usage() {
        SecurityUtils.requireRole("admin");
        var today = quotaRepo.todayTotals();
        var month = quotaRepo.monthTotals();
        var ai = properties.ai();
        long inputRate = ai == null ? 0 : Math.max(0, ai.inputPriceCentsPerMillion());
        long outputRate = ai == null ? 0 : Math.max(0, ai.outputPriceCentsPerMillion());
        boolean available = inputRate > 0 && outputRate > 0;
        Long estimate = available ? estimateCost(month.promptTokens(), month.completionTokens(), inputRate, outputRate)
                : null;
        return new UsageDashboard(UsageTotals.from(today), UsageTotals.from(month), quotaRepo.tasksToday(),
                quotaRepo.coursesThisMonth(), quotaRepo.studentsThisMonth(), available, estimate, inputRate, outputRate);
    }

    private static long estimateCost(long promptTokens, long completionTokens, long inputRate, long outputRate) {
        BigDecimal cents = BigDecimal.valueOf(promptTokens).multiply(BigDecimal.valueOf(inputRate))
                .add(BigDecimal.valueOf(completionTokens).multiply(BigDecimal.valueOf(outputRate)))
                .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.HALF_UP);
        return cents.longValueExact();
    }
}
