package com.codelab.app.audit;

import com.codelab.app.common.PageResult;
import com.codelab.app.security.SecurityUtils;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员只读审计检索（PRD §11.5）。 */
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditController {

    private final AuditRepository audit;

    public AdminAuditController(AuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    public PageResult<AuditRepository.AuditRow> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        SecurityUtils.requireRole("admin");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        var result = audit.search(q, action, from, to, safePage, safeSize);
        return PageResult.of(result.items(), result.total(), result.page(), result.size());
    }
}
