package com.example.ratelimiter.domain.factory;

import com.example.ratelimiter.common.exception.InvalidRequestException;
import com.example.ratelimiter.domain.strategy.*;
import com.example.ratelimiter.infrastructure.redis.RedisScriptExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rate Limit Strategy를 생성하는 팩토리 클래스
 * 
 * SOLID 원칙:
 * - Single Responsibility: Strategy 생성만 담당
 * - Open/Closed: 새로운 알고리즘 추가 시 팩토리만 수정하면 됨
 * - Dependency Inversion: 구체적인 Strategy가 아닌 인터페이스에 의존
 * - Factory Pattern: 객체 생성 로직을 캡슐화
 */
@Component
@RequiredArgsConstructor
public class RateLimitStrategyFactory {
    
    private final RedisScriptExecutor scriptExecutor;
    
    /**
     * 알고리즘 타입에 따라 적절한 Strategy 생성
     */
    public RateLimitStrategy createStrategy(AlgorithmType type, StrategyConfig config) {
        return switch (type) {
            case TOKEN_BUCKET -> new TokenBucketStrategy(
                    scriptExecutor,
                    config.getCapacity(),
                    config.getRefillRate()
            );
            case LEAKY_BUCKET -> new LeakyBucketStrategy(
                    scriptExecutor,
                    config.getCapacity(),
                    config.getLeakRate()
            );
            case FIXED_WINDOW -> new FixedWindowStrategy(
                    scriptExecutor,
                    config.getLimit(),
                    config.getWindowSize()
            );
            case SLIDING_WINDOW_LOG -> new SlidingWindowLogStrategy(
                    scriptExecutor,
                    config.getLimit(),
                    config.getWindowSize()
            );
            case SLIDING_WINDOW_COUNTER -> new SlidingWindowCounterStrategy(
                    scriptExecutor,
                    config.getLimit(),
                    config.getWindowSize()
            );
        };
    }
    
    /**
     * 알고리즘 타입 Enum
     */
    public enum AlgorithmType {
        TOKEN_BUCKET,
        LEAKY_BUCKET,
        FIXED_WINDOW,
        SLIDING_WINDOW_LOG,
        SLIDING_WINDOW_COUNTER
    }
    
    /**
     * Strategy 설정을 담는 Value Object
     *
     * - 빌더 패턴 (플루언트 API)
     * - 매개변수 유효성 검사
     */
    public static class StrategyConfig {
        private int capacity = 10;
        private double refillRate = 1.0;
        private double leakRate = 1.0;
        private int limit = 10;
        private int windowSize = 60;

        public static StrategyConfig defaults() {
            return new StrategyConfig();
        }

        /**
         * 매개변수 유효성 검사
         */
        public StrategyConfig capacity(int capacity) {
            if (capacity <= 0) {
                throw new InvalidRequestException("Capacity must be positive: " + capacity);
            }
            this.capacity = capacity;
            return this;
        }

        /**
         * 매개변수 유효성 검사
         */
        public StrategyConfig refillRate(double refillRate) {
            if (refillRate <= 0) {
                throw new InvalidRequestException("Refill rate must be positive: " + refillRate);
            }
            this.refillRate = refillRate;
            return this;
        }

        /**
         * 매개변수 유효성 검사
         */
        public StrategyConfig leakRate(double leakRate) {
            if (leakRate <= 0) {
                throw new InvalidRequestException("Leak rate must be positive: " + leakRate);
            }
            this.leakRate = leakRate;
            return this;
        }

        /**
         * 매개변수 유효성 검사
         */
        public StrategyConfig limit(int limit) {
            if (limit <= 0) {
                throw new InvalidRequestException("Limit must be positive: " + limit);
            }
            this.limit = limit;
            return this;
        }

        /**
         * 매개변수 유효성 검사
         */
        public StrategyConfig windowSize(int windowSize) {
            if (windowSize <= 0) {
                throw new InvalidRequestException("Window size must be positive: " + windowSize);
            }
            this.windowSize = windowSize;
            return this;
        }

        public int getCapacity() { return capacity; }
        public double getRefillRate() { return refillRate; }
        public double getLeakRate() { return leakRate; }
        public int getLimit() { return limit; }
        public int getWindowSize() { return windowSize; }
    }
}
