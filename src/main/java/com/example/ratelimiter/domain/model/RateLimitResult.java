package com.example.ratelimiter.domain.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * Rate Limit 결과를 나타내는 불변 Value Object
 *
 * SOLID 원칙:
 * - Single Responsibility: Rate Limit 결과 데이터만 담당
 * - Immutable: 불변 객체로 설계하여 부작용 방지
 *
 * - equals()와 hashCode() 재정의 (@EqualsAndHashCode)
 * - 불변 클래스로 설계
 */
@Getter
@Builder
@ToString
@EqualsAndHashCode
public class RateLimitResult {
    
    private final boolean allowed;
    private final String algorithm;
    private final long current;
    private final long limit;
    private final Instant resetAt;
    private final RateLimitMetadata metadata;
    
    /**
     * 요청이 허용되었는지 확인
     */
    public boolean isAllowed() {
        return allowed;
    }
    
    /**
     * 남은 허용 횟수 계산
     */
    public long getRemaining() {
        return Math.max(0, limit - current);
    }
    
    /**
     * Rate limit에 도달했는지 확인
     */
    public boolean isLimitReached() {
        return !allowed;
    }
    
    /**
     * 성공 결과 생성 (Factory Method Pattern)
     */
    public static RateLimitResult allowed(String algorithm, long current, long limit, Instant resetAt) {
        return RateLimitResult.builder()
                .allowed(true)
                .algorithm(algorithm)
                .current(current)
                .limit(limit)
                .resetAt(resetAt)
                .build();
    }
    
    /**
     * 거부 결과 생성 (Factory Method Pattern)
     */
    public static RateLimitResult denied(String algorithm, long current, long limit, Instant resetAt) {
        return RateLimitResult.builder()
                .allowed(false)
                .algorithm(algorithm)
                .current(current)
                .limit(limit)
                .resetAt(resetAt)
                .build();
    }
    
    /**
     * 메타데이터와 함께 결과 생성
     */
    public RateLimitResult withMetadata(RateLimitMetadata metadata) {
        return RateLimitResult.builder()
                .allowed(this.allowed)
                .algorithm(this.algorithm)
                .current(this.current)
                .limit(this.limit)
                .resetAt(this.resetAt)
                .metadata(metadata)
                .build();
    }
}
