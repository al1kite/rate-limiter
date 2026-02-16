package com.example.ratelimiter.application.service;

import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory.AlgorithmType;
import com.example.ratelimiter.domain.model.RateLimitResult;

/**
 * Rate Limiter 서비스 인터페이스
 * 
 * SOLID 원칙:
 * - Interface Segregation: 필요한 메서드만 정의
 * - Dependency Inversion: 구현이 아닌 추상화에 의존
 */
public interface RateLimiterService {
    
    /**
     * 요청 허용 여부 확인
     * 
     * @param algorithmType 알고리즘 타입
     * @param identifier 사용자/리소스 식별자
     * @return Rate limit 결과
     */
    RateLimitResult checkLimit(AlgorithmType algorithmType, String identifier);
    
    /**
     * Rate limit 상태 초기화
     * 
     * @param algorithmType 알고리즘 타입
     * @param identifier 사용자/리소스 식별자
     */
    void resetLimit(AlgorithmType algorithmType, String identifier);
}
