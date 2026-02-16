package com.example.ratelimiter.domain.strategy;

import com.example.ratelimiter.domain.model.RateLimitResult;

/**
 * Rate Limiting 전략을 정의하는 인터페이스
 *
 * SOLID 원칙:
 * - Interface Segregation: 클라이언트가 필요한 메서드만 정의
 * - Dependency Inversion: 구체적인 구현이 아닌 추상화에 의존
 * - Strategy Pattern: 알고리즘을 캡슐화하고 상호 교환 가능하게 만듦
 *
 * - 인터페이스는 타입을 정의하는 용도로만 사용
 * - 디폴트 메서드 제공으로 구현의 편의성 증대
 */
public interface RateLimitStrategy {
    
    /**
     * 요청 허용 여부를 판단
     * 
     * @param identifier 사용자/리소스 식별자
     * @return Rate limit 결과
     */
    RateLimitResult allowRequest(String identifier);
    
    /**
     * 특정 식별자의 rate limit 상태 초기화
     * 
     * @param identifier 사용자/리소스 식별자
     */
    void reset(String identifier);
    
    /**
     * 알고리즘 이름 반환
     * 
     * @return 알고리즘 이름
     */
    String getAlgorithmName();
    
    /**
     * 알고리즘 설명 반환
     * 
     * @return 알고리즘 설명
     */
    default String getDescription() {
        return "Rate limiting algorithm";
    }
}
