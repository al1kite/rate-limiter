package com.example.ratelimiter.domain.strategy;

import com.example.ratelimiter.common.exception.InvalidRequestException;
import com.example.ratelimiter.domain.model.RateLimitMetadata;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.infrastructure.redis.RedisScriptExecutor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * Token Bucket 알고리즘 구현
 *
 * 특징:
 * - 버킷에 토큰이 일정 속도로 채워짐
 * - 요청마다 토큰 1개씩 소비
 * - 버스트 트래픽 허용
 *
 * 트레이드오프:
 * - 장점: 버스트 트래픽 유연하게 처리, 평균 요청률 제어 효과적
 * - 단점: 메모리 사용 (토큰 수, 마지막 리필 시간 저장), 시간 동기화 필요
 *
 * SOLID 원칙:
 * - Single Responsibility: Token Bucket 알고리즘만 담당
 * - Open/Closed: 인터페이스 구현으로 확장에는 열려있고 수정에는 닫혀있음
 * - Liskov Substitution: RateLimitStrategy를 완전히 대체 가능
 *
 * - 매개변수가 유효한지 검사 (생성자에서 IllegalArgumentException)
 * - 변경 가능성을 최소화 (final 필드)
 */
@Slf4j
public class TokenBucketStrategy implements RateLimitStrategy {

    private static final String KEY_PREFIX = "rate_limit:token_bucket:";
    private static final int REDIS_TTL_SECONDS = 3600;  // 1시간

    private final RedisScriptExecutor scriptExecutor;
    private final int capacity;      // 버킷 최대 용량
    private final double refillRate; // 초당 토큰 리필 속도
    
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            -- Issue #1: Redis 서버 시간 사용 (clock skew 방지)
            local time = redis.call('TIME')
            local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

            local tokens_key = key .. ":tokens"
            local timestamp_key = key .. ":timestamp"

            local last_tokens = tonumber(redis.call("GET", tokens_key))
            local last_refreshed = tonumber(redis.call("GET", timestamp_key))

            if last_tokens == nil then
                last_tokens = capacity
            end

            if last_refreshed == nil then
                last_refreshed = now
            end

            -- 경과 시간 계산 및 토큰 리필
            local delta = math.max(0, now - last_refreshed)
            local filled_tokens = math.min(capacity, last_tokens + (delta * rate))

            -- 요청 처리 가능 여부 확인
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens

            if allowed then
                new_tokens = filled_tokens - requested
            end

            -- 상태 업데이트
            redis.call("SETEX", tokens_key, ttl, new_tokens)
            redis.call("SETEX", timestamp_key, ttl, now)

            -- Issue #3: tostring()으로 부동소수점 정밀도 보존
            return {
                allowed and 1 or 0,
                tostring(new_tokens),
                capacity
            }
            """;
    
    /**
     * 매개변수 유효성 검사
     * 의존 객체 주입
     */
    public TokenBucketStrategy(RedisScriptExecutor scriptExecutor, int capacity, double refillRate) {
        if (capacity <= 0) {
            throw new InvalidRequestException("Capacity must be positive: " + capacity);
        }
        if (refillRate <= 0) {
            throw new InvalidRequestException("Refill rate must be positive: " + refillRate);
        }

        this.scriptExecutor = scriptExecutor;
        this.capacity = capacity;
        this.refillRate = refillRate;
    }
    
    @Override
    public RateLimitResult allowRequest(String identifier) {
        String key = KEY_PREFIX + identifier;

        List<Object> result = scriptExecutor.executeRawLuaScript(
                LUA_SCRIPT,
                List.of(key),
                List.of(
                        String.valueOf(capacity),
                        String.valueOf(refillRate),
                        "1", // 요청당 토큰 1개
                        String.valueOf(REDIS_TTL_SECONDS)
                )
        );

        boolean allowed = ((Long) result.get(0)) == 1;
        double tokens = Double.parseDouble((String) result.get(1));
        long limit = (Long) result.get(2);
        
        RateLimitMetadata metadata = RateLimitMetadata.forTokenBucket(tokens);
        
        RateLimitResult rateLimitResult = allowed
                ? RateLimitResult.allowed(getAlgorithmName(), (long) (capacity - tokens), limit, calculateResetTime(tokens))
                : RateLimitResult.denied(getAlgorithmName(), capacity, limit, calculateResetTime(tokens));
        
        return rateLimitResult.withMetadata(metadata);
    }
    
    @Override
    public void reset(String identifier) {
        String key = KEY_PREFIX + identifier;
        scriptExecutor.deleteKeys(key + ":tokens", key + ":timestamp");
    }
    
    @Override
    public String getAlgorithmName() {
        return "TOKEN_BUCKET";
    }
    
    @Override
    public String getDescription() {
        return String.format("Token Bucket (capacity=%d, refillRate=%.2f/sec)", capacity, refillRate);
    }
    
    /**
     * 토큰이 가득 찰 때까지의 시간 계산
     */
    private Instant calculateResetTime(double currentTokens) {
        if (currentTokens >= capacity) {
            return Instant.now();
        }
        
        double tokensNeeded = capacity - currentTokens;
        long secondsUntilFull = (long) Math.ceil(tokensNeeded / refillRate);
        
        return Instant.now().plusSeconds(secondsUntilFull);
    }
}
