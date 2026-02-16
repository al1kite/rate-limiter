package com.example.ratelimiter.domain.strategy;

import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.infrastructure.redis.RedisScriptExecutor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * Fixed Window Counter 알고리즘 구현
 * 
 * 특징:
 * - 고정된 시간 윈도우 내 요청 수 제한
 * - 윈도우가 리셋되면 카운터 초기화
 * - 구현이 가장 간단함
 * 
 * 트레이드오프:
 * - 장점: 매우 간단하고 메모리 효율적, 빠른 성능
 * - 단점: 윈도우 경계에서 burst 발생 가능 (2배 트래픽 가능)
 *         예: 윈도우 끝 1초에 limit만큼, 다음 윈도우 시작 1초에 limit만큼
 * 
 * SOLID 원칙:
 * - Single Responsibility: Fixed Window 알고리즘만 담당
 * - Liskov Substitution: RateLimitStrategy를 완전히 대체 가능
 */
@Slf4j
public class FixedWindowStrategy implements RateLimitStrategy {
    
    private static final String KEY_PREFIX = "rate_limit:fixed_window:";
    
    private final RedisScriptExecutor scriptExecutor;
    private final int limit;       // 윈도우당 최대 요청 수
    private final int windowSize;  // 윈도우 크기 (초)
    
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            
            -- 현재 윈도우 키 생성
            local window_id = math.floor(now / window)
            local window_key = key .. ":" .. window_id
            
            -- 현재 카운트 가져오기
            local current = tonumber(redis.call("GET", window_key))
            
            if current == nil then
                current = 0
            end
            
            -- 요청 허용 여부 확인
            local allowed = current < limit
            
            if allowed then
                current = redis.call("INCR", window_key)
                redis.call("EXPIRE", window_key, window * 2)
            end
            
            -- 윈도우 리셋 시간 계산
            local reset_at = (window_id + 1) * window
            
            return {
                allowed and 1 or 0,
                current,
                limit,
                reset_at
            }
            """;
    
    public FixedWindowStrategy(RedisScriptExecutor scriptExecutor, int limit, int windowSize) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("Window size must be positive");
        }
        
        this.scriptExecutor = scriptExecutor;
        this.limit = limit;
        this.windowSize = windowSize;
    }
    
    @Override
    public RateLimitResult allowRequest(String identifier) {
        String key = KEY_PREFIX + identifier;
        long now = System.currentTimeMillis() / 1000;
        
        List<Long> result = scriptExecutor.executeLuaScript(
                LUA_SCRIPT,
                List.of(key),
                List.of(
                        String.valueOf(limit),
                        String.valueOf(windowSize),
                        String.valueOf(now)
                )
        );
        
        boolean allowed = result.get(0) == 1;
        long current = result.get(1);
        long limitValue = result.get(2);
        long resetAt = result.get(3);
        
        return allowed
                ? RateLimitResult.allowed(getAlgorithmName(), current, limitValue, Instant.ofEpochSecond(resetAt))
                : RateLimitResult.denied(getAlgorithmName(), current, limitValue, Instant.ofEpochSecond(resetAt));
    }
    
    @Override
    public void reset(String identifier) {
        String key = KEY_PREFIX + identifier;
        String pattern = key + ":*";
        List<String> keys = scriptExecutor.findKeys(pattern);
        
        if (!keys.isEmpty()) {
            scriptExecutor.deleteKeys(keys.toArray(new String[0]));
        }
    }
    
    @Override
    public String getAlgorithmName() {
        return "FIXED_WINDOW";
    }
    
    @Override
    public String getDescription() {
        return String.format("Fixed Window (limit=%d per %d seconds)", limit, windowSize);
    }
}
