package com.example.ratelimiter.domain.strategy;

import com.example.ratelimiter.domain.model.RateLimitMetadata;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.infrastructure.redis.RedisScriptExecutor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * Leaky Bucket 알고리즘 구현
 * 
 * 특징:
 * - 요청이 큐에 들어가고 일정한 속도로 처리
 * - 큐가 가득 차면 새로운 요청 거부
 * - 출력 속도가 일정함 (smoothing effect)
 * 
 * 트레이드오프:
 * - 장점: 일정한 처리 속도 보장, 네트워크 대역폭 제어에 적합
 * - 단점: 버스트 트래픽 처리 불가, 지연 발생 가능
 * 
 * SOLID 원칙:
 * - Single Responsibility: Leaky Bucket 알고리즘만 담당
 * - Liskov Substitution: RateLimitStrategy를 완전히 대체 가능
 */
@Slf4j
public class LeakyBucketStrategy implements RateLimitStrategy {
    
    private static final String KEY_PREFIX = "rate_limit:leaky_bucket:";
    private static final int REDIS_TTL_SECONDS = 3600;

    private final RedisScriptExecutor scriptExecutor;
    private final int capacity;    // 큐 최대 크기
    private final double leakRate; // 초당 누출 속도

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local leak_rate = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])

            -- Issue #1: Redis 서버 시간 사용 (clock skew 방지)
            local time = redis.call('TIME')
            local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

            local queue_key = key .. ":queue"
            local timestamp_key = key .. ":timestamp"

            local last_leak = tonumber(redis.call("GET", timestamp_key))
            local queue_size = tonumber(redis.call("GET", queue_key))

            if last_leak == nil then
                last_leak = now
            end

            if queue_size == nil then
                queue_size = 0
            end

            -- leak 계산 (시간에 따라 누출)
            local delta = math.max(0, now - last_leak)
            local leaked = math.floor(delta * leak_rate)
            queue_size = math.max(0, queue_size - leaked)

            -- 새 요청 추가 가능 여부
            local allowed = queue_size < capacity

            if allowed then
                queue_size = queue_size + 1
            end

            -- 상태 업데이트
            redis.call("SETEX", queue_key, ttl, queue_size)
            redis.call("SETEX", timestamp_key, ttl, now)

            return {
                allowed and 1 or 0,
                queue_size,
                capacity
            }
            """;
    
    public LeakyBucketStrategy(RedisScriptExecutor scriptExecutor, int capacity, double leakRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        if (leakRate <= 0) {
            throw new IllegalArgumentException("Leak rate must be positive: " + leakRate);
        }

        this.scriptExecutor = scriptExecutor;
        this.capacity = capacity;
        this.leakRate = leakRate;
    }

    @Override
    public RateLimitResult allowRequest(String identifier) {
        String key = KEY_PREFIX + identifier;

        List<Long> result = scriptExecutor.executeLuaScript(
                LUA_SCRIPT,
                List.of(key),
                List.of(
                        String.valueOf(capacity),
                        String.valueOf(leakRate),
                        String.valueOf(REDIS_TTL_SECONDS)
                )
        );
        
        boolean allowed = result.get(0) == 1;
        long queueSize = result.get(1);
        long limit = result.get(2);
        
        RateLimitMetadata metadata = RateLimitMetadata.forLeakyBucket(queueSize);
        
        RateLimitResult rateLimitResult = allowed
                ? RateLimitResult.allowed(getAlgorithmName(), queueSize, limit, calculateResetTime(queueSize))
                : RateLimitResult.denied(getAlgorithmName(), queueSize, limit, calculateResetTime(queueSize));
        
        return rateLimitResult.withMetadata(metadata);
    }
    
    @Override
    public void reset(String identifier) {
        String key = KEY_PREFIX + identifier;
        scriptExecutor.deleteKeys(key + ":queue", key + ":timestamp");
    }
    
    @Override
    public String getAlgorithmName() {
        return "LEAKY_BUCKET";
    }
    
    @Override
    public String getDescription() {
        return String.format("Leaky Bucket (capacity=%d, leakRate=%.2f/sec)", capacity, leakRate);
    }
    
    /**
     * 큐가 완전히 비워질 때까지의 시간 계산
     */
    private Instant calculateResetTime(long currentQueueSize) {
        if (currentQueueSize == 0) {
            return Instant.now();
        }
        
        long secondsUntilEmpty = (long) Math.ceil(currentQueueSize / leakRate);
        return Instant.now().plusSeconds(secondsUntilEmpty);
    }
}
