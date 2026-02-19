package com.example.ratelimiter.domain.factory;

import com.example.ratelimiter.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * StrategyConfig 유효성 검증 테스트
 */
class StrategyConfigTest {

    @Test
    @DisplayName("기본 설정값이 올바르게 초기화된다")
    void defaultConfigTest() {
        // when
        RateLimitStrategyFactory.StrategyConfig config =
                RateLimitStrategyFactory.StrategyConfig.defaults();

        // then
        assertThat(config.getCapacity()).isEqualTo(10);
        assertThat(config.getRefillRate()).isEqualTo(1.0);
        assertThat(config.getLeakRate()).isEqualTo(1.0);
        assertThat(config.getLimit()).isEqualTo(10);
        assertThat(config.getWindowSize()).isEqualTo(60);
    }

    @Test
    @DisplayName("양수 capacity 설정이 성공한다")
    void validCapacityTest() {
        // when
        RateLimitStrategyFactory.StrategyConfig config =
                RateLimitStrategyFactory.StrategyConfig.defaults()
                        .capacity(100);

        // then
        assertThat(config.getCapacity()).isEqualTo(100);
    }

    @Test
    @DisplayName("음수 capacity 설정 시 InvalidRequestException 발생")
    void negativeCapacityTest() {
        // when & then
        assertThatThrownBy(() ->
                RateLimitStrategyFactory.StrategyConfig.defaults().capacity(-1))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Capacity must be positive");
    }

    @Test
    @DisplayName("0 capacity 설정 시 InvalidRequestException 발생")
    void zeroCapacityTest() {
        // when & then
        assertThatThrownBy(() ->
                RateLimitStrategyFactory.StrategyConfig.defaults().capacity(0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Capacity must be positive");
    }

    @Test
    @DisplayName("양수 refillRate 설정이 성공한다")
    void validRefillRateTest() {
        // when
        RateLimitStrategyFactory.StrategyConfig config =
                RateLimitStrategyFactory.StrategyConfig.defaults()
                        .refillRate(2.5);

        // then
        assertThat(config.getRefillRate()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("음수 refillRate 설정 시 InvalidRequestException 발생")
    void negativeRefillRateTest() {
        // when & then
        assertThatThrownBy(() ->
                RateLimitStrategyFactory.StrategyConfig.defaults().refillRate(-0.5))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Refill rate must be positive");
    }

    @Test
    @DisplayName("양수 leakRate 설정이 성공한다")
    void validLeakRateTest() {
        // when
        RateLimitStrategyFactory.StrategyConfig config =
                RateLimitStrategyFactory.StrategyConfig.defaults()
                        .leakRate(0.5);

        // then
        assertThat(config.getLeakRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("음수 leakRate 설정 시 InvalidRequestException 발생")
    void negativeLeakRateTest() {
        // when & then
        assertThatThrownBy(() ->
                RateLimitStrategyFactory.StrategyConfig.defaults().leakRate(-1.0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Leak rate must be positive");
    }

    @Test
    @DisplayName("양수 limit 설정이 성공한다")
    void validLimitTest() {
        // when
        RateLimitStrategyFactory.StrategyConfig config =
                RateLimitStrategyFactory.StrategyConfig.defaults()
                        .limit(50);

        // then
        assertThat(config.getLimit()).isEqualTo(50);
    }

    @Test
    @DisplayName("음수 limit 설정 시 InvalidRequestException 발생")
    void negativeLimitTest() {
        // when & then
        assertThatThrownBy(() ->
                RateLimitStrategyFactory.StrategyConfig.defaults().limit(-10))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Limit must be positive");
    }

    @Test
    @DisplayName("양수 windowSize 설정이 성공한다")
    void validWindowSizeTest() {
        // when
        RateLimitStrategyFactory.StrategyConfig config =
                RateLimitStrategyFactory.StrategyConfig.defaults()
                        .windowSize(120);

        // then
        assertThat(config.getWindowSize()).isEqualTo(120);
    }

    @Test
    @DisplayName("음수 windowSize 설정 시 InvalidRequestException 발생")
    void negativeWindowSizeTest() {
        // when & then
        assertThatThrownBy(() ->
                RateLimitStrategyFactory.StrategyConfig.defaults().windowSize(-60))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Window size must be positive");
    }

    @Test
    @DisplayName("빌더 패턴으로 여러 설정을 체이닝할 수 있다")
    void builderChainingTest() {
        // when
        RateLimitStrategyFactory.StrategyConfig config =
                RateLimitStrategyFactory.StrategyConfig.defaults()
                        .capacity(50)
                        .refillRate(2.0)
                        .leakRate(1.5)
                        .limit(100)
                        .windowSize(120);

        // then
        assertThat(config.getCapacity()).isEqualTo(50);
        assertThat(config.getRefillRate()).isEqualTo(2.0);
        assertThat(config.getLeakRate()).isEqualTo(1.5);
        assertThat(config.getLimit()).isEqualTo(100);
        assertThat(config.getWindowSize()).isEqualTo(120);
    }
}
