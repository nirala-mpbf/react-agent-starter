package com.meg.agentsdk.prompt.cache;

import com.meg.agentsdk.prompt.dto.PromptResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

public class PromptCacheService {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheService.class);
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final String namespace;

    public PromptCacheService(StringRedisTemplate redis,
                              @Value("${prompt.cache.ttl:7d}") String configuredTtl,
                              @Value("${lakefs.repository}") String repository,
                              @Value("${lakefs.branch}") String branch) {

        Duration ttl = DurationStyle.detectAndParse(configuredTtl);

        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Prompt cache TTL must be positive");
        }

        this.redis = redis;
        this.ttl = ttl;
        this.namespace = "prompts:" + repository + ":" + branch + ":";
    }

    public Optional<PromptResponse> get(String id, String path) {
        try {
            String value = redis.opsForValue().get(namespace + path);

            if (value != null) {
                int separator = value.indexOf('\n');
                if (separator > 0) {
                    log.debug("Redis cache hit: path={}", path);
                    return Optional.of(new PromptResponse(id, value.substring(0, separator), value.substring(separator + 1)));
                }
            }
        }
        catch (DataAccessException e) {
            log.warn("Redis read failed; fetching prompt from lakeFS", e);
        }
        log.debug("Redis cache miss or unavailable: path={}", path);
        return Optional.empty();
    }

    public void put(String path, PromptResponse response) {
        try {
            redis.opsForValue().set(namespace + path, response.ref() + "\n" + response.content(), ttl);
            log.debug("Redis cache populated: path={}, commitId={}, ttl={}", path, response.ref(), ttl);
        }
        catch (DataAccessException e) {
            log.warn("Redis write failed", e);
        }
    }

    public void evict(String path) {
        try {
            redis.delete(namespace + path);
            log.info("Redis cache invalidated: path={}", path);
        }
        catch (DataAccessException e) {
            log.warn("Redis invalidation failed for {}; cached content may remain until expiry", path, e);
        }
    }
}

