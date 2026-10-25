package com.codelab.app.ai;

import com.codelab.app.config.AppProperties;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 启动自检（M3）：问一下 ai 服务"你现在是哪个 provider"。
 * <p>为什么要有这一步：stub（假 AI）绝不能悄悄上线——它会给出模板化回答且不报错。
 * 这里在启动日志里显式打 WARN，配合 deploy.sh 的硬检查形成双保险。
 */
@Component
@Order(4)
public class AiSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiSelfCheck.class);

    private final AppProperties props;

    public AiSelfCheck(AppProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Ai ai = props.ai();
        if (ai == null || !ai.enabled()) {
            log.warn("AI 功能已关闭（APP_AI_ENABLED=false）：所有 AI 接口会返回 429 AI_DISABLED");
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(ai.baseUrl())
                    .requestFactory(factory)
                    .build();
            @SuppressWarnings("unchecked")
            Map<String, Object> health = client.get().uri("/healthz").retrieve().body(Map.class);
            Object provider = health == null ? null : health.get("provider");
            Object model = health == null ? null : health.get("model");
            if ("stub".equals(provider)) {
                log.warn("================================================================");
                log.warn("AI 服务当前是 stub（假 AI）：回答是确定性模板，不会调用真实模型。");
                log.warn("上线前必须把 .env 的 AI_PROVIDER 改成 openai 并配置 DASHSCOPE_API_KEY。");
                log.warn("================================================================");
            } else {
                log.info("AI 服务就绪：provider={} model={} baseUrl={}", provider, model, ai.baseUrl());
            }
        } catch (RuntimeException ex) {
            log.warn("AI 服务不可达（{}）：AI 答疑与知识问答会返回 502，判题与其它功能不受影响。原因：{}",
                    ai.baseUrl(), ex.getMessage());
        }
    }
}
