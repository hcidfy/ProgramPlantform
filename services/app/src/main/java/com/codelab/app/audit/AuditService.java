package com.codelab.app.audit;

import com.codelab.app.common.JsonMini;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 审计日志：只追加（PRD §6.8 FR-ADM-04 / §13.5）。写失败不影响主流程。 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID actorId, String action, String resource, Map<String, ?> detail) {
        String detailJson = detail == null || detail.isEmpty() ? null : JsonMini.object(detail);
        String ip = currentIp();
        try {
            jdbc.sql("""
                            INSERT INTO audit_logs (actor_id, action, resource, detail, ip)
                            VALUES (:actor, :action, :resource, CAST(:detail AS jsonb), CAST(:ip AS inet))
                            """)
                    .param("actor", actorId)
                    .param("action", action)
                    .param("resource", resource)
                    .param("detail", detailJson)
                    .param("ip", ip)
                    .update();
        } catch (RuntimeException ex) {
            log.warn("审计写入失败 action={} resource={}: {}", action, resource, ex.getMessage());
        }
    }

    private String currentIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        // inet 不接受 "0:0:0:0:0:0:0:1" 之外的花样；不可解析就丢弃
        try {
            java.net.InetAddress.getByName(ip);
            return ip;
        } catch (Exception ex) {
            return null;
        }
    }
}
