package com.example.ratelimiter.common.annotation;

import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory.AlgorithmType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rate Limiting을 적용하기 위한 애노테이션
 * 
 * SOLID 원칙:
 * - Single Responsibility: Rate limiting 메타데이터만 제공
 * - Open/Closed: 새로운 설정 추가 시 기존 코드 수정 없이 확장 가능
 * 
 * 사용 예:
 * @RateLimit(algorithm = AlgorithmType.SLIDING_WINDOW_COUNTER)
 * public ResponseEntity<?> myApi() { ... }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    
    /**
     * 사용할 Rate Limiting 알고리즘
     */
    AlgorithmType algorithm() default AlgorithmType.SLIDING_WINDOW_COUNTER;
    
    /**
     * 식별자 추출 방법 (SpEL 표현식)
     * 예: "#request.remoteAddr", "#principal.username"
     */
    String identifierExpression() default "#request.remoteAddr";
    
    /**
     * Rate limit 도달 시 에러 메시지
     */
    String message() default "Too many requests. Please try again later.";
}
