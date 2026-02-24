# 아키텍처 상세

## 계층 구조 (Clean Architecture)

```
┌─────────────────────────────────────────────────────┐
│  presentation/        외부 세계와의 인터페이스          │
│  ├── controller/      REST 엔드포인트                  │
│  ├── dto/             요청/응답 DTO                    │
│  └── interceptor/     AOP 인터셉터                     │
├─────────────────────────────────────────────────────┤
│  application/         유스케이스 오케스트레이션           │
│  └── service/         비즈니스 로직 조합                 │
├─────────────────────────────────────────────────────┤
│  domain/              순수 도메인 모델 (의존성 없음)      │
│  ├── model/           Value Object                    │
│  ├── strategy/        알고리즘 구현체                   │
│  └── factory/         전략 생성 팩토리                   │
├─────────────────────────────────────────────────────┤
│  infrastructure/      기술 구현                        │
│  └── redis/           Redis Lua Script 실행           │
├─────────────────────────────────────────────────────┤
│  common/              공통 모듈                        │
│  ├── annotation/      @RateLimit 커스텀 어노테이션      │
│  └── exception/       예외 계층                        │
├─────────────────────────────────────────────────────┤
│  config/              Spring 설정                     │
│  └── RedisConfig      Redis 템플릿 설정                │
└─────────────────────────────────────────────────────┘
```

### 의존성 방향

```
presentation → application → domain ← infrastructure
                               ↑
                            common
```

- `domain`은 외부 의존성이 없는 순수 계층
- `infrastructure`는 `domain`의 인터페이스를 구현 (DIP)
- `presentation`은 `application`만 알고 있음

---

## 핵심 클래스 설명

### Presentation Layer

#### RateLimitController

REST 엔드포인트를 제공하며, 5가지 알고리즘 각각을 테스트할 수 있는 API를 노출합니다.

```
GET /api/rate-limit/token-bucket
    → @RateLimit(algorithm = TOKEN_BUCKET, ...)
    → RateLimitAspect가 가로채서 Rate Limit 검사
    → 통과 시 컨트롤러 메서드 실행
    → 초과 시 RateLimitExceededException → 429 응답
```

#### RateLimitAspect

`@RateLimit` 어노테이션이 붙은 메서드를 AOP로 가로채 Rate Limiting을 적용합니다.

```java
@Around("@annotation(rateLimit)")
public Object checkRateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
    // 1. SpEL로 식별자 추출 (IP, 사용자 ID 등)
    // 2. RateLimiterService.checkLimit() 호출
    // 3. 허용 → joinPoint.proceed()
    // 4. 거부 → RateLimitExceededException
}
```

**SpEL 식별자 추출**:
- `#request.remoteAddr` → 클라이언트 IP 주소
- `#userId` → 메서드 파라미터
- 파라미터 불일치 시 `"unknown"`으로 대체 + 경고 로그

#### GlobalExceptionHandler

| 예외 | HTTP 상태 | 설명 |
|------|----------|------|
| `RateLimitExceededException` | 429 | Rate Limit 초과, `X-RateLimit-*` 헤더 포함 |
| `InvalidRequestException` | 400 | 잘못된 요청 파라미터 |
| `Exception` | 500 | 내부 오류 (메시지 마스킹) |

**보안 고려사항**:
- 500 에러 시 내부 구현 세부사항을 노출하지 않음
- `"Internal server error"` 고정 메시지 반환
- `IllegalArgumentException`은 400이 아닌 500으로 처리 (의도치 않은 정보 노출 방지)

### Application Layer

#### RateLimiterServiceImpl

```java
public RateLimitResult checkLimit(AlgorithmType algorithmType, String identifier) {
    RateLimitStrategy strategy = getOrCreateStrategy(algorithmType);

    try {
        return strategy.allowRequest(identifier);
    } catch (RuntimeException e) {
        // Fail-Open: Redis 장애 시 요청 허용
        return RateLimitResult.allowed(...);
    }
}
```

**핵심 설계**:
- **Strategy 캐싱**: `ConcurrentHashMap`으로 알고리즘별 전략 인스턴스 재사용
- **Fail-Open**: Redis 장애 시에도 서비스 가용성 유지 (가용성 > 정확성)
- **RuntimeException만 catch**: `Error`(OOM 등)는 상위로 전파

### Domain Layer

#### RateLimitStrategy (Interface)

```java
public interface RateLimitStrategy {
    RateLimitResult allowRequest(String identifier);  // Rate Limit 검사
    void reset(String identifier);                     // 제한 초기화
    String getAlgorithmName();                         // 알고리즘 이름
    default String getDescription() { ... }            // 설명 (기본 구현)
}
```

5가지 구현체가 이 인터페이스를 구현하여 Strategy Pattern을 적용합니다.

#### RateLimitResult (Value Object)

```java
// 팩토리 메서드로 생성
RateLimitResult.allowed("TOKEN_BUCKET", currentCount, limit, resetAt)
RateLimitResult.denied("TOKEN_BUCKET", currentCount, limit, resetAt)

// 메타데이터 추가
result.withMetadata(RateLimitMetadata.forTokenBucket(tokens))
```

**불변 객체**: 생성 후 변경 불가, 스레드 안전

#### RateLimitStrategyFactory

```java
// Factory Pattern + Builder Pattern
RateLimitStrategy strategy = factory.createStrategy(
    AlgorithmType.TOKEN_BUCKET,
    StrategyConfig.defaults()
        .capacity(10)
        .refillRate(1.0)
);
```

**StrategyConfig Builder**:
- 플루언트 API로 설정 구성
- 각 setter에서 즉시 유효성 검증 (Fail-Fast)
- 기본값 제공: `StrategyConfig.defaults()`

### Infrastructure Layer

#### RedisScriptExecutor (Interface)

```java
public interface RedisScriptExecutor {
    List<Long> executeLuaScript(String script, List<String> keys, List<String> args);
    List<Object> executeRawLuaScript(String script, List<String> keys, List<String> args);
    void deleteKeys(String... keys);
    List<String> findKeys(String pattern);
}
```

#### RedisScriptExecutorImpl

- **스크립트 캐싱**: `ConcurrentHashMap<String, DefaultRedisScript<List>>`로 SHA1 해시 기반 캐싱
- **SCAN 사용**: `KEYS *` 대신 커서 기반 비차단 순회 (Issue #5)
- **두 가지 실행 모드**:
  - `executeLuaScript()`: `Long` 값만 반환
  - `executeRawLuaScript()`: `Long`/`String` 혼합 반환 (부동소수점 정밀도 보존)

---

## 디자인 패턴

### 1. Strategy Pattern

```
                    ┌──────────────────────┐
                    │  RateLimitStrategy   │  ← 인터페이스
                    │  + allowRequest()    │
                    │  + reset()           │
                    └──────────┬───────────┘
           ┌──────────┬───────┴─────┬──────────┬──────────┐
     TokenBucket  LeakyBucket  FixedWindow  SWLog  SWCounter
```

### 2. Factory Pattern

```
RateLimiterServiceImpl
    │
    │ getOrCreateStrategy(TOKEN_BUCKET)
    ▼
RateLimitStrategyFactory
    │
    │ createStrategy(type, config)
    ▼
TokenBucketStrategy (새 인스턴스)
```

### 3. AOP Pattern

```
Client Request
    │
    ▼
RateLimitAspect (@Around)
    │
    ├─ 식별자 추출 (SpEL)
    ├─ RateLimiterService.checkLimit()
    │   ├─ 허용 → joinPoint.proceed() → Controller 메서드 실행
    │   └─ 거부 → RateLimitExceededException
    │
    ▼
GlobalExceptionHandler → 429 Response + Headers
```

### 4. Fail-Open Pattern

```
checkLimit() 호출
    │
    ├─ Redis 정상 → 정상 Rate Limit 결과 반환
    │
    └─ Redis 장애 (RuntimeException)
        │
        ├─ 에러 로그 기록
        └─ RateLimitResult.allowed() 반환 (요청 허용)
            → 서비스 가용성 우선
```

---

## SOLID 원칙 적용

### Single Responsibility Principle (SRP)

| 클래스 | 단일 책임 |
|--------|----------|
| `TokenBucketStrategy` | Token Bucket 알고리즘만 담당 |
| `RateLimitStrategyFactory` | Strategy 생성만 담당 |
| `RateLimiterServiceImpl` | Rate Limiting 비즈니스 로직 조합만 담당 |
| `RateLimitAspect` | Rate Limiting 검사 인터셉트만 담당 |
| `GlobalExceptionHandler` | 예외 → HTTP 응답 매핑만 담당 |

### Open/Closed Principle (OCP)

```
새로운 알고리즘 추가 시:
1. RateLimitStrategy 구현체 생성  ← 확장
2. AlgorithmType enum에 추가
3. Factory에 case 추가
→ 기존 코드 수정 최소화
```

### Liskov Substitution Principle (LSP)

모든 Strategy 구현체는 `RateLimitStrategy` 인터페이스를 완전히 대체 가능합니다.

### Interface Segregation Principle (ISP)

- `RateLimitStrategy`: 알고리즘에 필요한 최소 메서드만 정의
- `RedisScriptExecutor`: Redis 스크립트 실행에 필요한 최소 메서드만 정의
- `RateLimiterService`: 서비스 계층에 필요한 `checkLimit()`, `resetLimit()`만 정의

### Dependency Inversion Principle (DIP)

```
Application Layer
    │
    │ depends on
    ▼
RateLimitStrategy (Interface)    ← 추상화
    ▲
    │ implements
    │
Infrastructure + Domain Layer
    TokenBucketStrategy, etc.    ← 구체 구현
```

---

## 동시성 전략

| 구성요소 | 전략 | 이유 |
|----------|------|------|
| **Lua Script** | Redis 단일 스레드 | 원자적 읽기-수정-쓰기 보장, Race Condition 없음 |
| **Strategy 캐싱** | `ConcurrentHashMap.computeIfAbsent()` | lock-free 읽기, 스레드 안전한 초기화 |
| **Value Object** | 불변 객체 (`RateLimitResult`, `RateLimitMetadata`) | 동기화 불필요 |
| **키 조회** | `SCAN` (not `KEYS`) | 비차단 커서 기반 순회, 프로덕션 안전 |

### Redis Lua Script의 원자성

```
Client A ──┐
           ├── Redis (단일 스레드) ──→ Lua Script 원자적 실행
Client B ──┘                          │
                                      ├── GET → 계산 → SETEX
                                      └── 중간에 다른 명령 끼어들 수 없음
```

---

## 설정 구조

### application.yml

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8    # 최대 활성 연결
          max-idle: 8      # 최대 유휴 연결
          min-idle: 0      # 최소 유휴 연결
          max-wait: -1ms   # 무제한 대기
```

### RedisConfig

```java
@Bean
public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(new StringRedisSerializer());
    return template;
}
```

**직렬화 전략**: `StringRedisSerializer`로 통일하여 Lua Script와의 호환성 보장
