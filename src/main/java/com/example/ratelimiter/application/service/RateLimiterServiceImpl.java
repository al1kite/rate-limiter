package com.example.ratelimiter.application.service;

import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory;
import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory.AlgorithmType;
import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory.StrategyConfig;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.domain.strategy.RateLimitStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate Limiter 서비스 구현체
 * 
 * SOLID 원칙:
 * - Single Responsibility: Rate limiting 비즈니스 로직만 담당
 * - Dependency Inversion: Strategy 인터페이스에 의존
 * - Open/Closed: 새로운 알고리즘 추가 시 이 클래스는 수정 불필요
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterServiceImpl implements RateLimiterService {
    
    private final RateLimitStrategyFactory strategyFactory;
    private final Map<AlgorithmType, RateLimitStrategy> strategyCache = new ConcurrentHashMap<>();
    
    @Override
    public RateLimitResult checkLimit(AlgorithmType algorithmType, String identifier) {
        RateLimitStrategy strategy = getOrCreateStrategy(algorithmType);
        
        try {
            RateLimitResult result = strategy.allowRequest(identifier);

            if (log.isDebugEnabled()) {
                log.debug("Rate limit check - Algorithm: {}, Identifier: {}, Allowed: {}",
                        algorithmType, identifier, result.isAllowed());
            }

            return result;

        } catch (RuntimeException e) {
            // RuntimeException만 catch하여 Error(OOM 등)는 상위로 전파
            // Fail-open: Redis 장애 시 요청 허용 (서비스 가용성 우선)
            log.error("Rate limit check failed for {} with algorithm {}", identifier, algorithmType, e);
            return RateLimitResult.allowed(algorithmType.name(), 0, Integer.MAX_VALUE, null);
        }
    }
    
    @Override
    public void resetLimit(AlgorithmType algorithmType, String identifier) {
        RateLimitStrategy strategy = getOrCreateStrategy(algorithmType);
        
        try {
            strategy.reset(identifier);
            log.info("Rate limit reset - Algorithm: {}, Identifier: {}", algorithmType, identifier);

        } catch (RuntimeException e) {
            log.error("Rate limit reset failed for {} with algorithm {}", identifier, algorithmType, e);
        }
    }
    
    /**
     * Strategy를 캐시에서 가져오거나 새로 생성
     * Thread-safe하게 구현
     */
    private RateLimitStrategy getOrCreateStrategy(AlgorithmType algorithmType) {
        return strategyCache.computeIfAbsent(algorithmType, type -> {
            StrategyConfig config = createDefaultConfig(type);
            return strategyFactory.createStrategy(type, config);
        });
    }
    
    /**
     * 알고리즘별 기본 설정 생성
     * 실제 프로덕션에서는 application.yml이나 환경변수에서 읽어올 수 있음
     */
    private StrategyConfig createDefaultConfig(AlgorithmType type) {
        return switch (type) {
            case TOKEN_BUCKET -> StrategyConfig.defaults()
                    .capacity(10)
                    .refillRate(1.0); // 초당 1개 토큰
                    
            case LEAKY_BUCKET -> StrategyConfig.defaults()
                    .capacity(10)
                    .leakRate(1.0); // 초당 1개 누출
                    
            case FIXED_WINDOW -> StrategyConfig.defaults()
                    .limit(10)
                    .windowSize(60); // 60초당 10개
                    
            case SLIDING_WINDOW_LOG -> StrategyConfig.defaults()
                    .limit(10)
                    .windowSize(60); // 60초당 10개
                    
            case SLIDING_WINDOW_COUNTER -> StrategyConfig.defaults()
                    .limit(10)
                    .windowSize(60); // 60초당 10개
        };
    }
}
