package com.example.ratelimiter.application.service;

import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory.AlgorithmType;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.*;

/**
 * RateLimiterService 통합 테스트 (실제 Redis 사용)
 */
@SpringBootTest
@Testcontainers
class RateLimiterServiceIntegrationTest {

    @Container
    static RedisContainer redis = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushAll();
    }

    @Test
    @DisplayName("TokenBucket - 요청이 허용되면 allowed=true 결과 반환")
    void checkLimitAllowedTest() {
        // when
        RateLimitResult result = rateLimiterService.checkLimit(
                AlgorithmType.TOKEN_BUCKET, "user123");

        // then
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getRemaining()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("TokenBucket - 한도 초과 시 요청 거부")
    void checkLimitDeniedTest() {
        // given - 10번 요청
        for (int i = 0; i < 10; i++) {
            rateLimiterService.checkLimit(AlgorithmType.TOKEN_BUCKET, "user456");
        }

        // when - 11번째 요청
        RateLimitResult result = rateLimiterService.checkLimit(
                AlgorithmType.TOKEN_BUCKET, "user456");

        // then
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("reset 호출 후 다시 요청 가능")
    void resetLimitTest() {
        // given - 한도까지 요청
        for (int i = 0; i < 10; i++) {
            rateLimiterService.checkLimit(AlgorithmType.FIXED_WINDOW, "user789");
        }

        RateLimitResult denied = rateLimiterService.checkLimit(
                AlgorithmType.FIXED_WINDOW, "user789");
        assertThat(denied.isAllowed()).isFalse();

        // when - 초기화
        rateLimiterService.resetLimit(AlgorithmType.FIXED_WINDOW, "user789");

        // then - 다시 요청 가능
        RateLimitResult allowed = rateLimiterService.checkLimit(
                AlgorithmType.FIXED_WINDOW, "user789");
        assertThat(allowed.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("다른 식별자는 독립적으로 제한됨")
    void independentLimitsTest() {
        // given
        for (int i = 0; i < 10; i++) {
            rateLimiterService.checkLimit(AlgorithmType.LEAKY_BUCKET, "user-a");
        }

        // when - user-a는 거부, user-b는 허용
        RateLimitResult resultA = rateLimiterService.checkLimit(
                AlgorithmType.LEAKY_BUCKET, "user-a");
        RateLimitResult resultB = rateLimiterService.checkLimit(
                AlgorithmType.LEAKY_BUCKET, "user-b");

        // then
        assertThat(resultA.isAllowed()).isFalse();
        assertThat(resultB.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("모든 알고리즘이 정상 동작")
    void allAlgorithmsWorkTest() {
        for (AlgorithmType algorithm : AlgorithmType.values()) {
            RateLimitResult result = rateLimiterService.checkLimit(
                    algorithm, "test-user-" + algorithm);

            assertThat(result).isNotNull();
            assertThat(result.getAlgorithm()).isNotNull();
        }
    }
}
