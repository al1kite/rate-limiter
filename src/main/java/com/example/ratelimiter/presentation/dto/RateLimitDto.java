package com.example.ratelimiter.presentation.dto;

import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory.AlgorithmType;
import com.example.ratelimiter.domain.model.RateLimitResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * API 응답 DTO들
 *
 * SOLID 원칙:
 * - Single Responsibility: 각 DTO는 하나의 응답 타입만 표현
 *
 * - null이 아닌 빈 컬렉션 반환
 */
public class RateLimitDto {
    
    /**
     * Rate Limit 결과 응답 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private boolean allowed;
        private String algorithm;
        private long current;
        private long limit;
        private long remaining;
        private Instant resetAt;
        private Map<String, Object> metadata;
        
        /**
         * 정적 팩토리 메서드
         * null이 아닌 빈 컬렉션 반환
         */
        public static Response from(RateLimitResult result) {
            ResponseBuilder builder = Response.builder()
                    .allowed(result.isAllowed())
                    .algorithm(result.getAlgorithm())
                    .current(result.getCurrent())
                    .limit(result.getLimit())
                    .remaining(result.getRemaining())
                    .resetAt(result.getResetAt());

            // null이 아닌 빈 Map 반환
            if (result.getMetadata() != null) {
                builder.metadata(Map.of(
                        "tokens", result.getMetadata().getTokens() != null ?
                                result.getMetadata().getTokens() : 0,
                        "queueSize", result.getMetadata().getQueueSize() != null ?
                                result.getMetadata().getQueueSize() : 0,
                        "previousWindowCount", result.getMetadata().getPreviousWindowCount() != null ?
                                result.getMetadata().getPreviousWindowCount() : 0,
                        "currentWindowCount", result.getMetadata().getCurrentWindowCount() != null ?
                                result.getMetadata().getCurrentWindowCount() : 0
                ));
            } else {
                // 메타데이터가 없어도 빈 Map 반환
                builder.metadata(Map.of());
            }

            return builder.build();
        }
    }
    
    /**
     * 에러 응답 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorResponse {
        private int status;
        private String error;
        private String message;
        private Instant timestamp;
        private RateLimitInfo rateLimitInfo;
        
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class RateLimitInfo {
            private String algorithm;
            private long limit;
            private long remaining;
            private Instant resetAt;
        }
    }
    
    /**
     * 알고리즘 비교 결과 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComparisonResponse {
        private String identifier;
        private Map<AlgorithmType, Response> results;
        private Instant timestamp;
    }
    
    /**
     * 알고리즘 정보 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlgorithmInfo {
        private AlgorithmType type;
        private String name;
        private String description;
        private TradeOffs tradeOffs;
        
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class TradeOffs {
            private String[] pros;
            private String[] cons;
            private String useCases;
        }
    }
}
