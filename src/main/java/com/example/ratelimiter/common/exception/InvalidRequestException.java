package com.example.ratelimiter.common.exception;

/**
 * 잘못된 요청 파라미터 예외
 *
 * IllegalArgumentException 대신 사용하여 의도된 검증 실패만 400 응답으로 처리.
 * 예상치 못한 IllegalArgumentException은 500으로 처리되어 내부 정보 노출 방지.
 *
 * 사용 예:
 * - StrategyConfig 빌더 파라미터 검증
 * - Strategy 생성자 파라미터 검증
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
