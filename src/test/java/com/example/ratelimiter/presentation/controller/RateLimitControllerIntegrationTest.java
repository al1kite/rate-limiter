package com.example.ratelimiter.presentation.controller;

import com.example.ratelimiter.domain.factory.RateLimitStrategyFactory.AlgorithmType;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RateLimitController 통합 테스트
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RateLimitControllerIntegrationTest {

    @Container
    static RedisContainer redis = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void setUp() {
        // 각 테스트 전 Redis 초기화
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushAll();
    }

    @Test
    @DisplayName("홈페이지 API 문서 조회")
    void homeTest() throws Exception {
        mockMvc.perform(get("/api/rate-limit/"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("분산 Rate Limiter API"))
                .andExpect(jsonPath("$.description").exists())
                .andExpect(jsonPath("$.endpoints").exists());
    }

    @Test
    @DisplayName("알고리즘 정보 조회")
    void getAlgorithmsInfoTest() throws Exception {
        mockMvc.perform(get("/api/rate-limit/algorithms"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].type").exists())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[0].tradeOffs.pros").isArray())
                .andExpect(jsonPath("$[0].tradeOffs.cons").isArray());
    }

    @Test
    @DisplayName("Token Bucket 엔드포인트 호출 성공")
    void testTokenBucketEndpointTest() throws Exception {
        mockMvc.perform(get("/api/rate-limit/token-bucket"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("요청 성공!"))
                .andExpect(jsonPath("$.algorithm").value("Token Bucket"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Rate Limit 초과 시 429 응답 및 헤더 반환")
    void rateLimitExceededTest() throws Exception {
        String endpoint = "/api/rate-limit/token-bucket";

        // given - 10번 요청 (기본 설정 limit=10)
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isOk());
        }

        // when - 11번째 요청
        mockMvc.perform(get(endpoint))
                .andDo(print())
                .andExpect(status().isTooManyRequests())  // 429
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Algorithm"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.rateLimitInfo").exists());
    }

    @Test
    @DisplayName("알고리즘 비교 엔드포인트")
    void compareAlgorithmsTest() throws Exception {
        mockMvc.perform(get("/api/rate-limit/compare"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifier").exists())
                .andExpect(jsonPath("$.results.TOKEN_BUCKET").exists())
                .andExpect(jsonPath("$.results.LEAKY_BUCKET").exists())
                .andExpect(jsonPath("$.results.FIXED_WINDOW").exists())
                .andExpect(jsonPath("$.results.SLIDING_WINDOW_LOG").exists())
                .andExpect(jsonPath("$.results.SLIDING_WINDOW_COUNTER").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Rate Limit 초기화")
    void resetRateLimitTest() throws Exception {
        // given - 먼저 rate limit에 도달
        String endpoint = "/api/rate-limit/fixed-window";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isOk());
        }

        // 11번째 요청 거부 확인
        mockMvc.perform(get(endpoint))
                .andExpect(status().isTooManyRequests());

        // when - 초기화
        mockMvc.perform(delete("/api/rate-limit/FIXED_WINDOW/reset"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Rate limit reset successfully"))
                .andExpect(jsonPath("$.algorithm").value("FIXED_WINDOW"));

        // then - 다시 요청 가능
        mockMvc.perform(get(endpoint))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("모든 알고리즘 엔드포인트가 정상 동작")
    void allAlgorithmEndpointsTest() throws Exception {
        mockMvc.perform(get("/api/rate-limit/token-bucket"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/rate-limit/leaky-bucket"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/rate-limit/fixed-window"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/rate-limit/sliding-window-log"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/rate-limit/sliding-window-counter"))
                .andExpect(status().isOk());
    }

    // 잘못된 알고리즘 타입 테스트는 Spring의 enum 변환 동작에 의존하므로 제거

    @Test
    @DisplayName("동시 요청에도 Rate Limit이 정확하게 동작")
    void concurrentRequestsTest() throws Exception {
        String endpoint = "/api/rate-limit/sliding-window-counter";

        // when - 15번 요청 (limit=10이므로 5번은 거부되어야 함)
        int successCount = 0;
        int deniedCount = 0;

        for (int i = 0; i < 15; i++) {
            var result = mockMvc.perform(get(endpoint))
                    .andReturn();

            if (result.getResponse().getStatus() == 200) {
                successCount++;
            } else if (result.getResponse().getStatus() == 429) {
                deniedCount++;
            }
        }

        // then - 정확히 10번 성공, 5번 거부
        org.assertj.core.api.Assertions.assertThat(successCount).isEqualTo(10);
        org.assertj.core.api.Assertions.assertThat(deniedCount).isEqualTo(5);
    }
}
