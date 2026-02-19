package com.example.ratelimiter.domain.strategy;

import com.example.ratelimiter.common.exception.InvalidRequestException;
import com.example.ratelimiter.infrastructure.redis.RedisScriptExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.*;

/**
 * Strategy 생성자 파라미터 검증 테스트
 * (Mockito 없이 실제 RedisScriptExecutor 사용)
 */
@SpringBootTest
class StrategyConstructorTest {

    @Autowired
    private RedisScriptExecutor scriptExecutor;

    // TokenBucketStrategy 테스트
    @Test
    @DisplayName("TokenBucket - 양수 파라미터로 생성 성공")
    void tokenBucketValidConstructorTest() {
        assertThatNoException().isThrownBy(() ->
                new TokenBucketStrategy(scriptExecutor, 10, 1.0));
    }

    @Test
    @DisplayName("TokenBucket - 음수 capacity 시 InvalidRequestException")
    void tokenBucketNegativeCapacityTest() {
        assertThatThrownBy(() ->
                new TokenBucketStrategy(scriptExecutor, -1, 1.0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Capacity must be positive");
    }

    @Test
    @DisplayName("TokenBucket - 음수 refillRate 시 InvalidRequestException")
    void tokenBucketNegativeRefillRateTest() {
        assertThatThrownBy(() ->
                new TokenBucketStrategy(scriptExecutor, 10, -1.0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Refill rate must be positive");
    }

    // LeakyBucketStrategy 테스트
    @Test
    @DisplayName("LeakyBucket - 양수 파라미터로 생성 성공")
    void leakyBucketValidConstructorTest() {
        assertThatNoException().isThrownBy(() ->
                new LeakyBucketStrategy(scriptExecutor, 10, 0.5));
    }

    @Test
    @DisplayName("LeakyBucket - 음수 capacity 시 InvalidRequestException")
    void leakyBucketNegativeCapacityTest() {
        assertThatThrownBy(() ->
                new LeakyBucketStrategy(scriptExecutor, -1, 0.5))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Capacity must be positive");
    }

    @Test
    @DisplayName("LeakyBucket - 음수 leakRate 시 InvalidRequestException")
    void leakyBucketNegativeLeakRateTest() {
        assertThatThrownBy(() ->
                new LeakyBucketStrategy(scriptExecutor, 10, -0.5))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Leak rate must be positive");
    }

    // FixedWindowStrategy 테스트
    @Test
    @DisplayName("FixedWindow - 양수 파라미터로 생성 성공")
    void fixedWindowValidConstructorTest() {
        assertThatNoException().isThrownBy(() ->
                new FixedWindowStrategy(scriptExecutor, 10, 60));
    }

    @Test
    @DisplayName("FixedWindow - 음수 limit 시 InvalidRequestException")
    void fixedWindowNegativeLimitTest() {
        assertThatThrownBy(() ->
                new FixedWindowStrategy(scriptExecutor, -1, 60))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Limit must be positive");
    }

    // SlidingWindowLogStrategy 테스트
    @Test
    @DisplayName("SlidingWindowLog - 양수 파라미터로 생성 성공")
    void slidingWindowLogValidConstructorTest() {
        assertThatNoException().isThrownBy(() ->
                new SlidingWindowLogStrategy(scriptExecutor, 10, 60));
    }

    // SlidingWindowCounterStrategy 테스트
    @Test
    @DisplayName("SlidingWindowCounter - 양수 파라미터로 생성 성공")
    void slidingWindowCounterValidConstructorTest() {
        assertThatNoException().isThrownBy(() ->
                new SlidingWindowCounterStrategy(scriptExecutor, 10, 60));
    }
}
