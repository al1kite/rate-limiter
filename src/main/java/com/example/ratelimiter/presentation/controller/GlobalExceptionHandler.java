package com.example.ratelimiter.presentation.controller;

import com.example.ratelimiter.common.exception.RateLimitExceededException;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.presentation.dto.RateLimitDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 전역 예외 처리 핸들러
 * 
 * SOLID 원칙:
 * - Single Responsibility: 예외 처리 및 에러 응답 생성만 담당
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * Rate Limit 초과 예외 처리
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<RateLimitDto.ErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException ex) {
        
        RateLimitResult result = ex.getRateLimitResult();
        
        RateLimitDto.ErrorResponse response = RateLimitDto.ErrorResponse.builder()
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .error("Too Many Requests")
                .message(ex.getMessage())
                .timestamp(Instant.now())
                .rateLimitInfo(RateLimitDto.ErrorResponse.RateLimitInfo.builder()
                        .algorithm(result.getAlgorithm())
                        .limit(result.getLimit())
                        .remaining(result.getRemaining())
                        .resetAt(result.getResetAt())
                        .build())
                .build();
        
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Limit", String.valueOf(result.getLimit()))
                .header("X-RateLimit-Remaining", String.valueOf(result.getRemaining()))
                .header("X-RateLimit-Reset", result.getResetAt() != null ? 
                        String.valueOf(result.getResetAt().getEpochSecond()) : "")
                .header("X-RateLimit-Algorithm", result.getAlgorithm())
                .body(response);
    }
    
    /**
     * 일반 예외 처리
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RateLimitDto.ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        
        RateLimitDto.ErrorResponse response = RateLimitDto.ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message(ex.getMessage())
                .timestamp(Instant.now())
                .build();
        
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}
