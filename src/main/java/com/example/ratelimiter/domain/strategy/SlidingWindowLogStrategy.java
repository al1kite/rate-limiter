package com.example.ratelimiter.domain.strategy;

import com.example.ratelimiter.common.exception.InvalidRequestException;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.infrastructure.redis.RedisScriptExecutor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * Sliding Window Log 알고리즘 구현
 * 
 * 특징:
 * - 각 요청의 타임스탬프를 로그에 저장
 * - 슬라이딩 윈도우 내의 요청만 카운트
 * - 정확한 rate limiting 제공
 * 
 * 트레이드오프:
 * - 장점: 가장 정확한 rate limiting, 버스트 방지 효과적
 * - 단점: 메모리 사용량 높음 (모든 요청 타임스탬프 저장), 성능 오버헤드
 * 
 * SOLID 원칙:
 * - Single Responsibility: Sliding Window Log 알고리즘만 담당
 * - Liskov Substitution: RateLimitStrategy를 완전히 대체 가능
 */
@Slf4j
public class SlidingWindowLogStrategy implements RateLimitStrategy {
    
    private static final String KEY_PREFIX = "rate_limit:sliding_window_log:";

    private final RedisScriptExecutor scriptExecutor;
    private final int limit;       // 윈도우당 최대 요청 수
    private final int windowSize;  // 윈도우 크기 (초)

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])

            -- Issue #1: Redis 서버 시간 사용 (clock skew 방지)
            local time = redis.call('TIME')
            local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

            local log_key = key .. ":log"
            local seq_key = key .. ":seq"

            -- 윈도우 밖의 오래된 요청 제거
            local window_start = now - window
            redis.call("ZREMRANGEBYSCORE", log_key, 0, window_start)

            -- 현재 윈도우 내 요청 수 확인
            local current = redis.call("ZCARD", log_key)

            -- 요청 허용 여부 확인
            local allowed = current < limit

            if allowed then
                -- Issue #4: math.random 제거
                -- time[1]:"time[2] 조합은 같은 마이크로초 내 복수 요청 시 ZADD가
                -- 새 entry 추가 대신 기존 score를 갱신하여 undercounting 발생.
                -- 원자적 INCR 시퀀스를 suffix로 추가하여 멤버 고유성 보장.
                local seq = redis.call("INCR", seq_key)
                redis.call("EXPIRE", seq_key, window * 2)
                local member = time[1] .. ":" .. time[2] .. ":" .. seq
                redis.call("ZADD", log_key, now, member)
                redis.call("EXPIRE", log_key, window * 2)
                current = current + 1
            end

            return {
                allowed and 1 or 0,
                current,
                limit,
                window_start
            }
            """;
    
    public SlidingWindowLogStrategy(RedisScriptExecutor scriptExecutor, int limit, int windowSize) {
        if (limit <= 0) {
            throw new InvalidRequestException("Limit must be positive: " + limit);
        }
        if (windowSize <= 0) {
            throw new InvalidRequestException("Window size must be positive: " + windowSize);
        }

        this.scriptExecutor = scriptExecutor;
        this.limit = limit;
        this.windowSize = windowSize;
    }

    @Override
    public RateLimitResult allowRequest(String identifier) {
        String key = KEY_PREFIX + identifier;

        List<Long> result = scriptExecutor.executeLuaScript(
                LUA_SCRIPT,
                List.of(key),
                List.of(
                        String.valueOf(limit),
                        String.valueOf(windowSize)
                )
        );
        
        boolean allowed = result.get(0) == 1;
        long current = result.get(1);
        long limitValue = result.get(2);
        
        // Sliding window이므로 정확한 reset 시간은 없음 (계속 슬라이딩)
        Instant resetAt = Instant.now().plusSeconds(windowSize);
        
        return allowed
                ? RateLimitResult.allowed(getAlgorithmName(), current, limitValue, resetAt)
                : RateLimitResult.denied(getAlgorithmName(), current, limitValue, resetAt);
    }
    
    @Override
    public void reset(String identifier) {
        String key = KEY_PREFIX + identifier;
        scriptExecutor.deleteKeys(key + ":log", key + ":seq");
    }
    
    @Override
    public String getAlgorithmName() {
        return "SLIDING_WINDOW_LOG";
    }
    
    @Override
    public String getDescription() {
        return String.format("Sliding Window Log (limit=%d per %d seconds)", limit, windowSize);
    }
}
