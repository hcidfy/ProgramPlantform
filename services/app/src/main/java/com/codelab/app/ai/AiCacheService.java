package com.codelab.app.ai;

import com.codelab.app.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 结果缓存（PRD §8.4 / §15.1 D5）：进程内 TTL，键 = task + submissionId + level + promptVersion。
 * <p>刻意不进 Redis：缓存是可丢数据（PRD §7.5），丢了大不了再花一次调用。
 */
@Service
public class AiCacheService {

    private static final Logger log = LoggerFactory.getLogger(AiCacheService.class);
    private static final int MAX_ENTRIES = 2_000;

    public record CachedAnswer(Map<String, Object> payload, AiClient.AiResponse.Usage usage, int promptVersion,
            Instant storedAt) {
    }

    private final ConcurrentHashMap<String, CachedAnswer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> forceRefreshUsed = new ConcurrentHashMap<>();
    private final Duration ttl;

    public AiCacheService(AppProperties props) {
        this.ttl = Duration.ofMinutes(Math.max(props.ai() == null ? 30 : props.ai().cacheTtlMinutes(), 1));
    }

    public static String key(String task, java.util.UUID submissionId, Integer level, int promptVersion) {
        return task + ":" + submissionId + ":" + (level == null ? "-" : level) + ":v" + promptVersion;
    }

    public Optional<CachedAnswer> get(String key) {
        CachedAnswer value = cache.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(value.storedAt().plus(ttl))) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public void put(String key, CachedAnswer value) {
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
            log.warn("AI 缓存超过 {} 条，整体清空", MAX_ENTRIES);
        }
        cache.put(key, value);
    }

    /** 每个"提交 + task"在 TTL 内只允许强制刷新 1 次（PRD §8.4）。 */
    public boolean tryAcquireForceRefresh(String submissionTaskKey) {
        Instant now = Instant.now();
        Instant previous = forceRefreshUsed.get(submissionTaskKey);
        if (previous != null && now.isBefore(previous.plus(ttl))) {
            return false;
        }
        forceRefreshUsed.put(submissionTaskKey, now);
        return true;
    }

    public int size() {
        return cache.size();
    }
}
