package com.example.ratelimiter.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Redis Lua Script 실행 구현체
 * 
 * SOLID 원칙:
 * - Single Responsibility: Redis 스크립트 실행만 담당
 * - Dependency Inversion: 인터페이스를 구현하여 추상화 제공
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisScriptExecutorImpl implements RedisScriptExecutor {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    @Override
    public List<Long> executeLuaScript(String script, List<String> keys, List<String> args) {
        try {
            DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptText(script);
            redisScript.setResultType(List.class);
            
            List<Object> result = redisTemplate.execute(redisScript, keys, args.toArray());
            
            if (result == null) {
                return Collections.emptyList();
            }
            
            // Redis는 숫자를 Long으로 반환
            List<Long> longResult = new ArrayList<>();
            for (Object obj : result) {
                if (obj instanceof Number) {
                    longResult.add(((Number) obj).longValue());
                }
            }
            
            return longResult;
            
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
    
    @Override
    public List<String> findKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        return keys != null ? new ArrayList<>(keys) : Collections.emptyList();
    }
}
