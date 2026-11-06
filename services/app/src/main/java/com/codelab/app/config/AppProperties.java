package com.codelab.app.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 应用自有配置（前缀 app.*），见 application.yml。 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Admin admin, Migration migration, Security security, Cors cors,
        Storage storage, Judge judge, Assignment assignment, Ai ai) {

    public record Jwt(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {
    }

    public record Admin(String email, String password) {
    }

    public record Migration(String dir) {
    }

    public record Security(boolean cookieSecure) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /** 文件存储：minio（生产）/ local（仅开发，PRD §14.1 D2）。 */
    public record Storage(String type, String localDir, Minio minio) {
        public record Minio(String endpoint, String accessKey, String secretKey, String bucket) {
        }
    }

    /** 判题（PRD §14.6）。pythonCmd 为空时按平台自动选择。 */
    public record Judge(boolean enabled, String dir, int concurrency, int maxQueue, String runAsUser,
            String pythonCmd, int retentionHours, int maxCases) {
    }

    /** 作业行为开关（FR-TCH-02）。 */
    public record Assignment(boolean allowLate) {
    }

    /** AI 编排服务（PRD §15.1 D4~D6）。 */
    public record Ai(boolean enabled, String baseUrl, String internalToken, int cacheTtlMinutes,
            int timeoutSeconds, int maxTokens, boolean stub, int defaultDailyTokens,
            int defaultPerProblemCalls, long inputPriceCentsPerMillion, long outputPriceCentsPerMillion) {
    }
}
