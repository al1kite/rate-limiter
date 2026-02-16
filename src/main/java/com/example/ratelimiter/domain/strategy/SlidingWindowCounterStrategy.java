package com.example.ratelimiter.domain.strategy;

import com.example.ratelimiter.domain.model.RateLimitMetadata;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.infrastructure.redis.RedisScriptExecutor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * Sliding Window Counter 알고리즘 구현 (Hybrid)
 * 
 * 특징:
 * - Fixed Window와 Sliding Window Log의 하이브리드
 * - 이전 윈도우와 현재 윈도우 카운터를 가중 평균으로 계산
 * - 메모리 효율적이면서도 정확도 높음
 * 
 * 공식: weighted_count = prev_count * (1 - elapsed_time_percent) + curr_count
 * 
 * 트레이드오프:
 * - 장점: 메모리 효율적, 정확도 높음, 버스트 완화 (대부분의 경우 최선의 선택)
 * - 단점: Fixed Window보다 복잡, Sliding Window Log보다 부정확
 *         (실제로는 거의 차이 없음)
 * 
 * SOLID 원칙:
 * - Single Responsibility: Sliding Window Counter 알고리즘만 담당
 * - Liskov Substitution: RateLimitStrategy를 완전히 대체 가능
 */
@Slf4j
public class SlidingWindowCounterStrategy implements RateLimitStrategy {
    
    private static final String KEY_PREFIX = "rate_limit:sliding_window_counter:";
    
    private final RedisScriptExecutor scriptExecutor;
    private final int limit;       // 윈도우당 최대 요청 수
    private final int windowSize;  // 윈도우 크기 (초)
    
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            
            -- 현재 윈도우와 이전 윈도우 ID 계산
            local current_window = math.floor(now / window)
            local prev_window = current_window - 1
            
            local curr_key = key .. ":" .. current_window
            local prev_key = key .. ":" .. prev_window
            
            -- 이전 윈도우와 현재 윈도우 카운트
            local prev_count = tonumber(redis.call("GET", prev_key)) or 0
            local curr_count = tonumber(redis.call("GET", curr_key)) or 0
            
            -- 현재 윈도우 내 경과 시간 비율 계산
            local window_start = current_window * window
            local elapsed_time = now - window_start
            local elapsed_percent = elapsed_time / window
            
            -- 가중 평균 계산
            local weighted_count = prev_count * (1 - elapsed_percent) + curr_count
            
            -- 요청 허용 여부 확인
            local allowed = weighted_count < limit
            
            if allowed then
                curr_count = redis.call("INCR", curr_key)
                redis.call("EXPIRE", curr_key, window * 2)
                -- 재계산
                weighted_count = prev_count * (1 - elapsed_percent) + curr_count
            end
            
            -- 다음 윈도우 시작 시간
            local next_window_start = (current_window + 1) * window
            
            return {
                allowed and 1 or 0,
                math.floor(weighted_count),
                limit,
                next_window_start,
                prev_count,
                curr_count
            }
            """;
    
    public SlidingWindowCounterStrategy(RedisScriptExecutor scriptExecutor, int limit, int windowSize) {
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
        long weightedCount = result.get(1);
        long limitValue = result.get(2);
        long nextWindowStart = result.get(3);
        long prevCount = result.get(4);
        long currCount = result.get(5);
        
        RateLimitMetadata metadata = RateLimitMetadata.forSlidingWindowCounter(
                prevCount, currCount, nextWindowStart - windowSize
        );
        
        RateLimitResult rateLimitResult = allowed
                ? RateLimitResult.allowed(getAlgorithmName(), weightedCount, limitValue, Instant.ofEpochSecond(nextWindowStart))
                : RateLimitResult.denied(getAlgorithmName(), weightedCount, limitValue, Instant.ofEpochSecond(nextWindowStart));
        
        return rateLimitResult.withMetadata(metadata);
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
        return "SLIDING_WINDOW_COUNTER";
    }
    
    @Override
    public String getDescription() {
        return String.format("Sliding Window Counter (limit=%d per %d seconds) - Recommended", limit, windowSize);
    }
}
