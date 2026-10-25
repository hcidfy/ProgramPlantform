package com.codelab.app.ai;

import com.codelab.app.common.ApiException;
import com.codelab.app.config.AppProperties;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 调 ai 服务的内部 HTTP 客户端（PRD §15.1 D6）。
 * <p>用 RestClient 而不是手拼 JSON：序列化交给 Spring 的 HttpMessageConverter，
 * 不直接依赖 Jackson 具体版本（Boot 4 用的是 Jackson 3，包名跟 2.x 不同）。
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    /** ai 服务返回体（字段与 services/ai/app/schemas.py 的 TaskResponse 对齐）。 */
    public record AiResponse(String task, int promptVersion, Map<String, Object> payload, Usage usage,
            boolean degraded, String degradedText) {

        public record Usage(int promptTokens, int completionTokens) {
        }
    }

    private final AppProperties.Ai props;
    private final RestClient client;

    public AiClient(AppProperties appProperties) {
        this.props = appProperties.ai();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(props.timeoutSeconds(), 5)));
        // 不注入 RestClient.Builder：Boot 4 里没有默认暴露这个 bean，自己建更省事
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", props.internalToken() == null ? "" : props.internalToken())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public AiResponse call(String task, Map<String, Object> body) {
        if (!props.enabled()) {
            throw ApiException.tooManyRequests("AI_DISABLED", "AI 功能当前已关闭");
        }
        long started = System.currentTimeMillis();
        try {
            AiResponse response = client.post()
                    .uri("/internal/" + task)
                    .body(body)
                    .retrieve()
                    .body(AiResponse.class);
            if (response == null) {
                throw upstreamFailure("AI 返回空响应", task);
            }
            log.info("ai call task={} elapsed={}ms degraded={}", task, System.currentTimeMillis() - started,
                    response.degraded());
            return response;
        } catch (RestClientException ex) {
            log.warn("ai call task={} 失败: {}", task, ex.getMessage());
            throw upstreamFailure("AI 忙，先看判题结果", task);
        }
    }

    private static ApiException upstreamFailure(String message, String task) {
        log.warn("task={} -> {}", task, message);
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", message);
    }
}
