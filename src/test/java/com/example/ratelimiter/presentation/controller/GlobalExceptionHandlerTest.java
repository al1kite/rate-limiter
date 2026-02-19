package com.example.ratelimiter.presentation.controller;

import com.example.ratelimiter.common.exception.InvalidRequestException;
import com.example.ratelimiter.common.exception.RateLimitExceededException;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.presentation.dto.RateLimitDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * GlobalExceptionHandler 테스트
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("InvalidRequestException 발생 시 400 응답 반환")
    void handleInvalidRequestExceptionTest() {
        // given
        InvalidRequestException exception =
                new InvalidRequestException("Capacity must be positive: -1");

        // when
        ResponseEntity<RateLimitDto.ErrorResponse> response =
                handler.handleInvalidRequest(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getError()).isEqualTo("Bad Request");
        assertThat(response.getBody().getMessage()).contains("Capacity must be positive");
    }

    @Test
    @DisplayName("RateLimitExceededException 발생 시 429 응답 및 헤더 반환")
    void handleRateLimitExceededExceptionTest() {
        // given
        RateLimitResult result = RateLimitResult.denied(
                "TOKEN_BUCKET",
                0,
                10,
                Instant.now().plusSeconds(60)
        );
        RateLimitExceededException exception =
                new RateLimitExceededException("Rate limit exceeded", result);

        // when
        ResponseEntity<RateLimitDto.ErrorResponse> response =
                handler.handleRateLimitExceeded(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(429);
        assertThat(response.getBody().getError()).isEqualTo("Too Many Requests");

        // 헤더 검증
        assertThat(response.getHeaders()).containsKey("X-RateLimit-Limit");
        assertThat(response.getHeaders()).containsKey("X-RateLimit-Remaining");
        assertThat(response.getHeaders()).containsKey("X-RateLimit-Algorithm");
        assertThat(response.getHeaders()).containsKey("X-RateLimit-Reset");
    }

    @Test
    @DisplayName("RateLimitExceededException에서 resetAt이 null인 경우 헤더 생략")
    void handleRateLimitExceededWithoutResetAtTest() {
        // given
        RateLimitResult result = RateLimitResult.denied(
                "TOKEN_BUCKET",
                0,
                10,
                null  // resetAt이 null
        );
        RateLimitExceededException exception =
                new RateLimitExceededException("Rate limit exceeded", result);

        // when
        ResponseEntity<RateLimitDto.ErrorResponse> response =
                handler.handleRateLimitExceeded(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().get("X-RateLimit-Reset")).isNull();
    }

    @Test
    @DisplayName("일반 Exception 발생 시 500 응답 및 마스킹된 메시지 반환")
    void handleGenericExceptionTest() {
        // given
        Exception exception = new RuntimeException("Internal database connection failed");

        // when
        ResponseEntity<RateLimitDto.ErrorResponse> response =
                handler.handleGenericException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(500);
        assertThat(response.getBody().getError()).isEqualTo("Internal Server Error");
        // 내부 메시지가 노출되지 않음
        assertThat(response.getBody().getMessage())
                .isEqualTo("An internal server error occurred. Please try again later.")
                .doesNotContain("database connection");
    }

    @Test
    @DisplayName("IllegalArgumentException은 500으로 처리되어 내부 정보 노출 방지")
    void illegalArgumentExceptionNotHandledAs400Test() {
        // given
        // InvalidRequestException이 아닌 일반 IllegalArgumentException
        IllegalArgumentException exception =
                new IllegalArgumentException("Internal validation failed: secret detail");

        // when
        // GlobalExceptionHandler는 InvalidRequestException만 400으로 처리하므로
        // IllegalArgumentException은 일반 Exception 핸들러로 처리됨
        ResponseEntity<RateLimitDto.ErrorResponse> response =
                handler.handleGenericException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("An internal server error occurred. Please try again later.")
                .doesNotContain("secret detail");
    }
}
