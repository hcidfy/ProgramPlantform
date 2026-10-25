package com.codelab.app.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 应用自有配置（前缀 app.*），见 application.yml。 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Admin admin, Migration migration, Security security, Cors cors) {

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
}
