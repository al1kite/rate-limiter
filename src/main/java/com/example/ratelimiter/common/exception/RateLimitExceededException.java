package com.example.ratelimiter.common.exception;

import com.example.ratelimiter.domain.model.RateLimitResult;
import lombok.Getter;

/**
 * Rate Limit 예외
 *
 * SOLID 원칙:
 * - Single Responsibility: Rate limit 초과 상황만 표현
 *
 * - 복구할 수 있는 상황에는 검사 예외를, 프로그래밍 오류에는 런타임 예외를 사용
 * (Rate limit은 복구 가능하지만, 웹 환경에서는 RuntimeException으로 처리)
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final RateLimitResult rateLimitResult;

    /**
     * 생성자 대신 정적 팩토리 메서드 고려 (여기서는 생성자 오버로딩 사용)
     */
    public RateLimitExceededException(String message, RateLimitResult rateLimitResult) {
        super(message);
        this.rateLimitResult = rateLimitResult;
    }

    public RateLimitExceededException(RateLimitResult rateLimitResult) {
        this("Rate limit exceeded", rateLimitResult);
    }
}
