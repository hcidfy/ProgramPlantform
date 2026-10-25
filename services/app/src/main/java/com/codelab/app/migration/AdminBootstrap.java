package com.codelab.app.migration;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.auth.AuthService;
import com.codelab.app.auth.UserRepository;
import com.codelab.app.config.AppProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首个管理员（待决策 Q8 的默认做法）：按 APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD 幂等创建。
 * 密码不满足策略时只告警不阻断启动——否则一旦 env 写错，容器会起不来，现场排障更麻烦。
 */
@Component
@Order(2)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AdminBootstrap(AppProperties props, UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = props.admin() == null ? null : props.admin().email();
        String password = props.admin() == null ? null : props.admin().password();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("未配置 APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD，跳过首个管理员初始化");
            return;
        }
        String normalized = AuthService.normalizeEmail(email);
        var existing = users.findByEmail(normalized);
        if (existing.isPresent()) {
            if (!"admin".equals(existing.get().role())) {
                log.warn("账号 {} 已存在但角色是 {}，不是 admin，未做改动", normalized, existing.get().role());
            }
            return;
        }
        try {
            AuthService.checkPassword(password);
        } catch (RuntimeException ex) {
            log.warn("管理员初始密码不满足密码策略（{}），仍会创建，请尽快修改：{}", ex.getMessage(), normalized);
        }
        var id = users.insert(normalized, "系统管理员", encoder.encode(password), "admin", "active");
        audit.record(id, AuditActions.ADMIN_CREATE_USER, "user:" + id,
                Map.of("email", normalized, "bootstrap", true));
        log.info("已创建首个管理员账号: {}", normalized);
    }
}
