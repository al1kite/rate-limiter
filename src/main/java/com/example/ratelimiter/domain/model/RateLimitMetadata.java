package com.example.ratelimiter.domain.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.Map;

/**
 * 알고리즘별 추가 메타데이터를 담는 Value Object
 *
 * SOLID 원칙:
 * - Single Responsibility: 메타데이터 표현만 담당
 * - Open/Closed: 새로운 메타데이터 추가 시 기존 코드 수정 없이 확장 가능
 *
 * - equals()와 hashCode() 재정의 (@EqualsAndHashCode)
 * - 불변 클래스로 설계
 * - 방어적 복사 (extra Map을 불변으로 보호)
 */
@Getter
@Builder
@ToString
@EqualsAndHashCode
public class RateLimitMetadata {
    
    private final Double tokens;              // Token Bucket: 남은 토큰 수
    private final Long queueSize;             // Leaky Bucket: 큐 크기
    private final Long windowStart;           // Window 알고리즘: 윈도우 시작 시간
    private final Long previousWindowCount;   // Sliding Window Counter: 이전 윈도우 카운트
    private final Long currentWindowCount;    // Sliding Window Counter: 현재 윈도우 카운트
    private final Map<String, Object> extra;  // 확장 가능한 추가 데이터

    /**
     * 방어적 복사
     * null이 아닌 빈 컬렉션 반환
     */
    public Map<String, Object> getExtra() {
        return extra == null ? Collections.emptyMap() : Collections.unmodifiableMap(extra);
    }
    
    /**
     * Token Bucket용 메타데이터 생성
     */
    public static RateLimitMetadata forTokenBucket(double tokens) {
        return RateLimitMetadata.builder()
                .tokens(tokens)
                .build();
    }
    
    /**
     * Leaky Bucket용 메타데이터 생성
     */
    public static RateLimitMetadata forLeakyBucket(long queueSize) {
        return RateLimitMetadata.builder()
                .queueSize(queueSize)
                .build();
    }
    
    /**
     * Sliding Window Counter용 메타데이터 생성
     */
    public static RateLimitMetadata forSlidingWindowCounter(
            long previousWindowCount, 
            long currentWindowCount,
            long windowStart) {
        return RateLimitMetadata.builder()
                .previousWindowCount(previousWindowCount)
                .currentWindowCount(currentWindowCount)
                .windowStart(windowStart)
                .build();
    }
}
