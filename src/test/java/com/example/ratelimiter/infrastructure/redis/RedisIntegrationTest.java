package com.example.ratelimiter.infrastructure.redis;

import com.example.ratelimiter.domain.model.RateLimitResult;
import com.example.ratelimiter.domain.strategy.TokenBucketStrategy;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Redis 통합 테스트 (Testcontainers 사용)
 */
@SpringBootTest
@Testcontainers
class RedisIntegrationTest {

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
    private RedisScriptExecutor scriptExecutor;

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
    @DisplayName("Redis 연결 확인")
    void redisConnectionTest() {
        // when
        redisTemplate.opsForValue().set("test:connection", "ok");
        String value = redisTemplate.opsForValue().get("test:connection");

        // then
        assertThat(value).isEqualTo("ok");
    }

    @Test
    @DisplayName("Lua 스크립트 실행 성공")
    void executeLuaScriptTest() {
        // given
        String script = """
                local key = KEYS[1]
                local value = ARGV[1]
                redis.call('SET', key, value)
                return {1, 100}
                """;

        // when
        List<Long> result = scriptExecutor.executeLuaScript(
                script,
                List.of("test:key"),
                List.of("test-value")
        );

        // then
        assertThat(result).containsExactly(1L, 100L);
        assertThat(redisTemplate.opsForValue().get("test:key")).isEqualTo("test-value");
    }

    @Test
    @DisplayName("SCAN을 사용한 키 검색")
    void findKeysWithScanTest() {
        // given
        redisTemplate.opsForValue().set("rate_limit:user1", "1");
        redisTemplate.opsForValue().set("rate_limit:user2", "2");
        redisTemplate.opsForValue().set("rate_limit:user3", "3");
        redisTemplate.opsForValue().set("other:key", "4");

        // when
        List<String> keys = scriptExecutor.findKeys("rate_limit:*");

        // then
        assertThat(keys).hasSize(3)
                .containsExactlyInAnyOrder(
                        "rate_limit:user1",
                        "rate_limit:user2",
                        "rate_limit:user3"
                );
    }

    @Test
    @DisplayName("키 삭제 성공")
    void deleteKeysTest() {
        // given
        redisTemplate.opsForValue().set("key1", "value1");
        redisTemplate.opsForValue().set("key2", "value2");

        // when
        scriptExecutor.deleteKeys("key1", "key2");

        // then
        assertThat(redisTemplate.hasKey("key1")).isFalse();
        assertThat(redisTemplate.hasKey("key2")).isFalse();
    }

    @Test
    @DisplayName("TokenBucket 전체 플로우 테스트")
    void tokenBucketFullFlowTest() throws InterruptedException {
        // given
        TokenBucketStrategy strategy = new TokenBucketStrategy(scriptExecutor, 5, 10.0);

        // when - 5개 요청 (모두 허용되어야 함)
        for (int i = 0; i < 5; i++) {
            RateLimitResult result = strategy.allowRequest("test-user");
            assertThat(result.isAllowed()).isTrue();
        }

        // 6번째 요청은 거부되어야 함 (용량 초과)
        RateLimitResult deniedResult = strategy.allowRequest("test-user");
        assertThat(deniedResult.isAllowed()).isFalse();

        // 200ms 대기 (refillRate=10.0 → 1초에 10개 → 100ms에 1개)
        Thread.sleep(200);

        // 토큰이 리필되어 다시 허용되어야 함
        RateLimitResult allowedAfterRefill = strategy.allowRequest("test-user");
        assertThat(allowedAfterRefill.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Redis TIME 명령 사용 확인")
    void redisTimeCommandTest() {
        // given
        String script = """
                local time = redis.call('TIME')
                local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
                return {tonumber(time[1]), tonumber(time[2])}
                """;

        // when
        List<Long> result = scriptExecutor.executeLuaScript(
                script,
                List.of(),
                List.of()
        );

        // then - 초와 마이크로초가 반환됨
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isGreaterThan(0);  // 초
        assertThat(result.get(1)).isGreaterThanOrEqualTo(0);  // 마이크로초
    }

    @Test
    @DisplayName("executeRawLuaScript - Long/String 혼합 타입 반환")
    void executeRawLuaScriptTest() {
        // given - 숫자와 tostring() 문자열을 혼합 반환하는 Lua 스크립트
        String script = """
                return {1, tostring(3.14159265358979), 42}
                """;

        // when
        List<Object> result = scriptExecutor.executeRawLuaScript(
                script, List.of(), List.of()
        );

        // then
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isInstanceOf(Long.class);
        assertThat((Long) result.get(0)).isEqualTo(1L);
        assertThat(result.get(1)).isInstanceOf(String.class);
        assertThat(Double.parseDouble((String) result.get(1))).isCloseTo(3.14159265358979, within(1e-10));
        assertThat(result.get(2)).isInstanceOf(Long.class);
        assertThat((Long) result.get(2)).isEqualTo(42L);
    }

    @Test
    @DisplayName("tostring()으로 소수점 3자리 이하 정밀도 보존 확인")
    void tostringPrecisionPreservationTest() {
        // given - 구 방식 math.floor(x * 100) / 100 에서 0으로 절삭되는 값
        // 0.000001 * 100 = 0.0001 → math.floor → 0 → 정밀도 손실
        // tostring(0.000001) → "1e-06" → 정밀도 보존
        String script = """
                local tiny = 0.000001
                local old_way = math.floor(tiny * 100)
                return {old_way, tostring(tiny)}
                """;

        // when
        List<Object> result = scriptExecutor.executeRawLuaScript(
                script, List.of(), List.of()
        );

        // then
        long oldWay = (Long) result.get(0);
        double newWay = Double.parseDouble((String) result.get(1));

        assertThat(oldWay).isZero();              // 구 방식: 정밀도 손실
        assertThat(newWay).isEqualTo(0.000001);   // 신 방식: 정밀도 보존
    }

    @Test
    @DisplayName("TokenBucket 저장→조회 전체 경로 소수점 정밀도 보존 확인")
    void tokenBucketStoragePrecisionTest() {
        // given - 고속 refill rate로 소수점 정밀도가 중요한 시나리오
        TokenBucketStrategy strategy = new TokenBucketStrategy(scriptExecutor, 10, 1000.0);

        // when - 요청으로 토큰 소비
        RateLimitResult result = strategy.allowRequest("precision-flow");
        assertThat(result.isAllowed()).isTrue();

        // then - Redis에 저장된 토큰 값이 소수점 정밀도를 유지하는지 직접 검증
        String storedTokens = redisTemplate.opsForValue()
                .get("rate_limit:token_bucket:precision-flow:tokens");
        assertThat(storedTokens).isNotNull();
        double tokens = Double.parseDouble(storedTokens);
        assertThat(tokens).isBetween(8.0, 10.0);

        // 타임스탬프도 마이크로초 정밀도(소수점 포함)를 유지해야 함
        String storedTimestamp = redisTemplate.opsForValue()
                .get("rate_limit:token_bucket:precision-flow:timestamp");
        assertThat(storedTimestamp).isNotNull();
        assertThat(storedTimestamp).contains(".");
    }

    @Test
    @DisplayName("스크립트 캐싱 확인 - 동일 스크립트 여러 번 실행")
    void scriptCachingTest() {
        // given
        String script = "return {1, 2, 3}";

        // when - 같은 스크립트를 여러 번 실행
        List<Long> result1 = scriptExecutor.executeLuaScript(script, List.of(), List.of());
        List<Long> result2 = scriptExecutor.executeLuaScript(script, List.of(), List.of());
        List<Long> result3 = scriptExecutor.executeLuaScript(script, List.of(), List.of());

        // then - 모두 동일한 결과
        assertThat(result1).containsExactly(1L, 2L, 3L);
        assertThat(result2).containsExactly(1L, 2L, 3L);
        assertThat(result3).containsExactly(1L, 2L, 3L);
    }
}
