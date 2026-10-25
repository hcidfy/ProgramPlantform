package com.codelab.app.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：给 docker compose healthcheck 与部署脚本用。
 * 不引 actuator（PRD §7.2 的依赖白名单里没有它），一个 query 足够证明"进程活着 + 数据库连得上"。
 */
@RestController
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "codelab-app");
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            body.put("db", "UP");
            return ResponseEntity.ok(body);
        } catch (RuntimeException ex) {
            body.put("status", "DOWN");
            body.put("db", "DOWN");
            body.put("error", ex.getMessage());
            return ResponseEntity.status(503).body(body);
        }
    }
}
