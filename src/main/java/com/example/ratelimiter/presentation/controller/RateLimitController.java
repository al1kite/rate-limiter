package com.example.ratelimiter.presentation.controller;

import com.example.ratelimiter.application.service.RateLimiterService;
import com.example.ratelimiter.common.annotation.RateLimit;
import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory.AlgorithmType;
import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.presentation.dto.RateLimitDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rate Limiter REST Controller
 * 
 * SOLID 원칙:
 * - Single Responsibility: HTTP 요청 처리만 담당
 * - Dependency Inversion: Service 인터페이스에 의존
 */
@Slf4j
@RestController
@RequestMapping("/api/rate-limit")
@RequiredArgsConstructor
public class RateLimitController {
    
    private final RateLimiterService rateLimiterService;
    
    /**
     * Token Bucket 테스트 엔드포인트
     */
    @GetMapping("/token-bucket")
    @RateLimit(algorithm = AlgorithmType.TOKEN_BUCKET)
    public ResponseEntity<Map<String, Object>> testTokenBucket(HttpServletRequest request) {
        return createSuccessResponse("Token Bucket", request.getRemoteAddr());
    }
    
    /**
     * Leaky Bucket 테스트 엔드포인트
     */
    @GetMapping("/leaky-bucket")
    @RateLimit(algorithm = AlgorithmType.LEAKY_BUCKET)
    public ResponseEntity<Map<String, Object>> testLeakyBucket(HttpServletRequest request) {
        return createSuccessResponse("Leaky Bucket", request.getRemoteAddr());
    }
    
    /**
     * Fixed Window 테스트 엔드포인트
     */
    @GetMapping("/fixed-window")
    @RateLimit(algorithm = AlgorithmType.FIXED_WINDOW)
    public ResponseEntity<Map<String, Object>> testFixedWindow(HttpServletRequest request) {
        return createSuccessResponse("Fixed Window", request.getRemoteAddr());
    }
    
    /**
     * Sliding Window Log 테스트 엔드포인트
     */
    @GetMapping("/sliding-window-log")
    @RateLimit(algorithm = AlgorithmType.SLIDING_WINDOW_LOG)
    public ResponseEntity<Map<String, Object>> testSlidingWindowLog(HttpServletRequest request) {
        return createSuccessResponse("Sliding Window Log", request.getRemoteAddr());
    }
    
    /**
     * Sliding Window Counter 테스트 엔드포인트 (추천)
     */
    @GetMapping("/sliding-window-counter")
    @RateLimit(algorithm = AlgorithmType.SLIDING_WINDOW_COUNTER)
    public ResponseEntity<Map<String, Object>> testSlidingWindowCounter(HttpServletRequest request) {
        return createSuccessResponse("Sliding Window Counter (Recommended)", request.getRemoteAddr());
    }
    
    /**
     * 모든 알고리즘 비교
     */
    @GetMapping("/compare")
    public ResponseEntity<RateLimitDto.ComparisonResponse> compareAlgorithms(HttpServletRequest request) {
        String identifier = request.getRemoteAddr();
        Map<AlgorithmType, RateLimitDto.Response> results = new HashMap<>();
        
        for (AlgorithmType type : AlgorithmType.values()) {
            RateLimitResult result = rateLimiterService.checkLimit(type, identifier);
            results.put(type, RateLimitDto.Response.from(result));
        }
        
        RateLimitDto.ComparisonResponse response = RateLimitDto.ComparisonResponse.builder()
                .identifier(identifier)
                .results(results)
                .timestamp(Instant.now())
                .build();
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 알고리즘 정보 조회
     */
    @GetMapping("/algorithms")
    public ResponseEntity<List<RateLimitDto.AlgorithmInfo>> getAlgorithmsInfo() {
        List<RateLimitDto.AlgorithmInfo> algorithms = Arrays.asList(
                createAlgorithmInfo(
                        AlgorithmType.TOKEN_BUCKET,
                        "Token Bucket",
                        "버킷에 토큰이 일정 속도로 채워지고, 요청마다 토큰을 소비하는 방식",
                        new String[]{"버스트 트래픽 유연하게 처리", "평균 요청률 제어 효과적"},
                        new String[]{"메모리 사용 (토큰 수, 타임스탬프)", "시간 동기화 필요"},
                        "API rate limiting, 평균 요청률 제어가 필요한 경우"
                ),
                createAlgorithmInfo(
                        AlgorithmType.LEAKY_BUCKET,
                        "Leaky Bucket",
                        "요청이 큐에 들어가고 일정한 속도로 처리되는 방식",
                        new String[]{"일정한 처리 속도 보장", "네트워크 대역폭 제어에 적합"},
                        new String[]{"버스트 트래픽 처리 불가", "지연 발생 가능"},
                        "네트워크 대역폭 제어, 일정한 처리 속도가 필요한 경우"
                ),
                createAlgorithmInfo(
                        AlgorithmType.FIXED_WINDOW,
                        "Fixed Window",
                        "고정된 시간 윈도우 내 요청 수를 제한하는 방식",
                        new String[]{"매우 간단하고 빠름", "메모리 효율적"},
                        new String[]{"윈도우 경계에서 2배 트래픽 가능"},
                        "간단한 rate limiting, 높은 성능이 필요한 경우"
                ),
                createAlgorithmInfo(
                        AlgorithmType.SLIDING_WINDOW_LOG,
                        "Sliding Window Log",
                        "각 요청의 타임스탬프를 로그에 저장하여 정확하게 제한하는 방식",
                        new String[]{"가장 정확한 rate limiting", "버스트 방지 효과적"},
                        new String[]{"메모리 사용량 높음", "성능 오버헤드"},
                        "정확한 rate limiting이 필요하고 메모리 여유가 있는 경우"
                ),
                createAlgorithmInfo(
                        AlgorithmType.SLIDING_WINDOW_COUNTER,
                        "Sliding Window Counter (추천)",
                        "이전/현재 윈도우 카운터를 가중 평균으로 계산하는 하이브리드 방식",
                        new String[]{"메모리 효율적", "정확도 높음", "성능 좋음"},
                        new String[]{"Fixed Window보다 약간 복잡"},
                        "대부분의 경우 (균형잡힌 선택)"
                )
        );
        
        return ResponseEntity.ok(algorithms);
    }
    
    /**
     * 특정 알고리즘의 rate limit 초기화
     */
    @DeleteMapping("/{algorithm}/reset")
    public ResponseEntity<Map<String, Object>> resetRateLimit(
            @PathVariable AlgorithmType algorithm,
            HttpServletRequest request) {
        
        String identifier = request.getRemoteAddr();
        rateLimiterService.resetLimit(algorithm, identifier);
        
        return ResponseEntity.ok(Map.of(
                "message", "Rate limit reset successfully",
                "algorithm", algorithm,
                "identifier", identifier
        ));
    }
    
    /**
     * 홈페이지 (API 문서)
     */
    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> home() {
        return ResponseEntity.ok(Map.of(
                "title", "분산 Rate Limiter API",
                "description", "5가지 Rate Limiting 알고리즘 데모 (SOLID 원칙 준수)",
                "endpoints", Map.of(
                        "GET /api/rate-limit/token-bucket", "Token Bucket 알고리즘 테스트",
                        "GET /api/rate-limit/leaky-bucket", "Leaky Bucket 알고리즘 테스트",
                        "GET /api/rate-limit/fixed-window", "Fixed Window 알고리즘 테스트",
                        "GET /api/rate-limit/sliding-window-log", "Sliding Window Log 알고리즘 테스트",
                        "GET /api/rate-limit/sliding-window-counter", "Sliding Window Counter 알고리즘 테스트 (추천)",
                        "GET /api/rate-limit/compare", "모든 알고리즘 비교",
                        "GET /api/rate-limit/algorithms", "알고리즘 정보 조회",
                        "DELETE /api/rate-limit/{algorithm}/reset", "Rate limit 초기화"
                ),
                "tips", List.of(
                        "각 엔드포인트를 여러 번 호출해보세요",
                        "응답 헤더에서 X-RateLimit-* 정보를 확인하세요",
                        "429 상태 코드가 반환되면 rate limit에 도달한 것입니다"
                )
        ));
    }
    
    private ResponseEntity<Map<String, Object>> createSuccessResponse(String algorithm, String identifier) {
        return ResponseEntity.ok(Map.of(
                "message", "요청 성공!",
                "algorithm", algorithm,
                "identifier", identifier,
                "timestamp", Instant.now()
        ));
    }
    
    private RateLimitDto.AlgorithmInfo createAlgorithmInfo(
            AlgorithmType type, String name, String description,
            String[] pros, String[] cons, String useCases) {
        
        return RateLimitDto.AlgorithmInfo.builder()
                .type(type)
                .name(name)
                .description(description)
                .tradeOffs(RateLimitDto.AlgorithmInfo.TradeOffs.builder()
                        .pros(pros)
                        .cons(cons)
                        .useCases(useCases)
                        .build())
                .build();
    }
}
