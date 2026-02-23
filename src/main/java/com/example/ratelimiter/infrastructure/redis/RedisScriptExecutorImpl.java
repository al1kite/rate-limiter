package com.example.ratelimiter.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Lua Script 실행 구현체
 *
 * SOLID 원칙:
 * - Single Responsibility: Redis 스크립트 실행만 담당
 * - Dependency Inversion: 인터페이스를 구현하여 추상화 제공
 *
 * 개선 사항:
 * - Issue #2: KEYS 명령 대신 SCAN 사용 (프로덕션 안전)
 * - Issue #3: DefaultRedisScript 캐싱으로 성능 향상
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisScriptExecutorImpl implements RedisScriptExecutor {

    private final RedisTemplate<String, String> redisTemplate;

    // Issue #3: Lua 스크립트를 SHA1 해시 기반으로 캐싱하여 불필요한 객체 생성 방지
    private final Map<String, DefaultRedisScript<List>> scriptCache = new ConcurrentHashMap<>();

    @Override
    public List<Long> executeLuaScript(String script, List<String> keys, List<String> args) {
        List<Object> raw = executeRawLuaScript(script, keys, args);

        // Redis는 숫자를 Long으로 반환
        List<Long> longResult = new ArrayList<>();
        for (Object obj : raw) {
            if (obj instanceof Number) {
                longResult.add(((Number) obj).longValue());
            }
        }
        return longResult;
    }

    @Override
    @SuppressWarnings("unchecked") // DefaultRedisScript<List>의 raw List는 Spring API 제약
    public List<Object> executeRawLuaScript(String script, List<String> keys, List<String> args) {
        try {
            // Issue #3: 캐시에서 스크립트 조회 또는 생성
            DefaultRedisScript<List> redisScript = scriptCache.computeIfAbsent(script, s -> {
                DefaultRedisScript<List> newScript = new DefaultRedisScript<>();
                newScript.setScriptText(s);
                newScript.setResultType(List.class);
                return newScript;
            });

            List<Object> result = redisTemplate.execute(redisScript, keys, args.toArray());

            if (result == null) {
                return Collections.emptyList();
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to execute Lua script", e);
            throw new RuntimeException("Redis script execution failed", e);
        }
    }

    @Override
    public void deleteKeys(String... keys) {
        if (keys != null && keys.length > 0) {
            redisTemplate.delete(List.of(keys));
        }
    }

    /**
     * Issue #2: KEYS 명령 대신 SCAN 사용
     *
     * KEYS는 O(N)으로 전체 keyspace를 블로킹 스캔하여 프로덕션 위험.
     * SCAN은 커서 기반으로 점진적 탐색하여 서버 블로킹 없음.
     */
    @Override
    public List<String> findKeys(String pattern) {
        List<String> result = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();

        redisTemplate.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });

        return result;
    }
}
