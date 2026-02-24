# Spring Rate Limiter

**Java 17 + Spring Boot 3.2 | 분산 환경을 위한 Redis 기반 Rate Limiting 구현**

5가지 Rate Limiting 알고리즘을 Redis Lua Script로 원자적(atomic)으로 구현하고, 각 알고리즘의 특성과 트레이드오프를 실험적으로 비교합니다.

---

## 핵심 학습 목표

| 주제 | 구현 내용 |
|------|-----------|
| **5가지 알고리즘** | Token Bucket / Leaky Bucket / Fixed Window / Sliding Window Log / Sliding Window Counter |
| **Redis Lua Script** | 원자적 읽기-수정-쓰기로 Race Condition 방지 |
| **서버 시간 동기화** | `redis.call('TIME')`으로 Clock Skew 해결 |
| **부동소수점 정밀도** | `tostring()` 방식으로 Lua ↔ Redis 정밀도 손실 방지 |
| **AOP 기반 적용** | `@RateLimit` 커스텀 어노테이션으로 선언적 Rate Limiting |
| **Fail-Open 패턴** | Redis 장애 시에도 서비스 가용성 보장 |

---

## 기술 스택

| 분류 | 기술 | 버전 | 선택 이유 |
|------|------|------|-----------|
| Framework | Spring Boot | 3.2.0 | 생산성, AOP/Redis 생태계 |
| Language | Java | 17 | Record, Sealed Class |
| Build | Gradle | 8.10.2 | 빠른 빌드, 캐싱 |
| Cache | Redis (Lettuce) | 7+ | 원자적 Lua Script 실행 |
| AOP | Spring AOP | - | 선언적 Rate Limiting |
| Validation | Spring Validation | - | 요청 검증 |
| Test | JUnit 5 + TestContainers | 1.19.3 | 실제 Redis 통합 테스트 |

---

## 실행 방법

### 요구사항

- Java 17+
- Redis 6.0+ (로컬 또는 Docker)

### 실행

```bash
# Redis 실행 (Docker)
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 빌드 & 테스트
./gradlew test

# 로컬 실행
./gradlew bootRun

# API 접속
open http://localhost:8080/api/rate-limit/
```

---

## API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/rate-limit/` | API 문서 및 홈 |
| `GET` | `/api/rate-limit/algorithms` | 5가지 알고리즘 정보 조회 |
| `GET` | `/api/rate-limit/compare` | 현재 클라이언트 기준 알고리즘 비교 |
| `GET` | `/api/rate-limit/token-bucket` | Token Bucket 테스트 |
| `GET` | `/api/rate-limit/leaky-bucket` | Leaky Bucket 테스트 |
| `GET` | `/api/rate-limit/fixed-window` | Fixed Window 테스트 |
| `GET` | `/api/rate-limit/sliding-window-log` | Sliding Window Log 테스트 |
| `GET` | `/api/rate-limit/sliding-window-counter` | Sliding Window Counter 테스트 (권장) |
| `DELETE` | `/api/rate-limit/{algorithm}/reset` | Rate Limit 초기화 |

### 응답 헤더 (429 Too Many Requests)

```
X-RateLimit-Limit: 10              ← 허용 최대 요청 수
X-RateLimit-Remaining: 0           ← 남은 요청 수
X-RateLimit-Algorithm: TOKEN_BUCKET ← 사용된 알고리즘
X-RateLimit-Reset: 1700000000      ← 초기화 시각 (Unix timestamp)
```

---

## 핵심 시나리오 실험

### 1. 알고리즘별 Rate Limit 테스트

```bash
# Token Bucket — 버스트 허용, 평균 속도 제어
for i in $(seq 1 15); do
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/rate-limit/token-bucket
  echo " - Request $i"
done
# 기대: 1~10번 → 200, 11~15번 → 429

# Sliding Window Counter (권장) — 균형 잡힌 정확도
for i in $(seq 1 15); do
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/rate-limit/sliding-window-counter
  echo " - Request $i"
done
```

### 2. 알고리즘 비교

```bash
curl http://localhost:8080/api/rate-limit/compare | jq
# 기대 응답: 5가지 알고리즘의 현재 상태 (남은 요청 수, 메타데이터)
```

### 3. Rate Limit 초기화

```bash
curl -X DELETE http://localhost:8080/api/rate-limit/token-bucket/reset
# 기대: 해당 알고리즘의 Rate Limit 카운터 초기화
```

---

## 5가지 Rate Limiting 알고리즘

### 비교 요약

| 알고리즘 | 정확도 | 메모리 | 성능 | 버스트 처리 | 권장 사용처 |
|----------|--------|--------|------|-------------|-------------|
| **Token Bucket** | ★★★★ | 중간 | ★★★★ | 허용 | API Rate Limiting |
| **Leaky Bucket** | ★★★★ | 중간 | ★★★★ | 차단 (균일 출력) | 네트워크 대역폭 제어 |
| **Fixed Window** | ★★★ | 적음 | ★★★★★ | 경계에서 2배 가능 | 단순/고성능 시나리오 |
| **Sliding Window Log** | ★★★★★ | 많음 | ★★★ | 완벽 차단 | 정밀한 Rate Limiting |
| **Sliding Window Counter** | ★★★★ | 적음 | ★★★★ | 근사적 차단 | **대부분의 시나리오 (권장)** |

### Token Bucket

```
[토큰 버킷]  용량: 10  |  보충 속도: 1 token/sec

요청 → 토큰 있음? → YES → 토큰 소비 → 허용
                  → NO  → 거부 (429)

특징: 토큰이 쌓여서 버스트 트래픽 허용
```

### Leaky Bucket

```
[누수 버킷]  용량: 10  |  누수 속도: 1 item/sec

요청 → 큐에 추가 → 일정 속도로 처리 (누수)
     → 큐 가득 참? → 거부 (429)

특징: 출력 속도가 항상 일정, 트래픽 평활화
```

### Fixed Window

```
[고정 윈도우]  윈도우: 60초  |  제한: 10회

|--- Window 1 ---|--- Window 2 ---|
  8 requests        3 requests

특징: 단순하지만 윈도우 경계에서 2배 트래픽 가능
```

### Sliding Window Log

```
[슬라이딩 윈도우 로그]  윈도우: 60초  |  제한: 10회

    ← -------- 60초 -------- →
    |  req  req  req  req  req |  ← 각 요청 타임스탬프 기록

특징: 가장 정확, 메모리 사용량 높음
```

### Sliding Window Counter

```
[슬라이딩 윈도우 카운터]  윈도우: 60초  |  제한: 10회

|--prev window--|--curr window--|
   8 requests     3 requests
                  ↑ 경과 30초 (50%)

가중치 = 8 × (1 - 0.5) + 3 = 7  →  허용 (< 10)

특징: Fixed Window + Sliding Log의 장점 결합
```

> 각 알고리즘의 상세 구현과 Lua Script는 [docs/algorithms.md](docs/algorithms.md)를 참고하세요.

---

## 아키텍처

### 계층 구조

```
presentation/                REST 진입점
  ├── controller/
  │   ├── RateLimitController    알고리즘 테스트, 비교, 초기화 엔드포인트
  │   └── GlobalExceptionHandler 예외 → HTTP 응답 매핑 (429, 400, 500)
  ├── dto/
  │   └── RateLimitDto           응답/에러 DTO
  └── interceptor/
      └── RateLimitAspect        @RateLimit AOP 인터셉터

application/                 유스케이스 오케스트레이션
  └── service/
      ├── RateLimiterService     인터페이스
      └── RateLimiterServiceImpl Fail-Open 패턴, 전략 캐싱

domain/                      순수 도메인 모델 (외부 의존성 없음)
  ├── model/
  │   ├── RateLimitResult        불변 결과 객체 (허용/거부)
  │   └── RateLimitMetadata      알고리즘별 메타데이터
  ├── strategy/
  │   ├── RateLimitStrategy      전략 인터페이스
  │   ├── TokenBucketStrategy    토큰 버킷 구현
  │   ├── LeakyBucketStrategy    누수 버킷 구현
  │   ├── FixedWindowStrategy    고정 윈도우 구현
  │   ├── SlidingWindowLogStrategy      슬라이딩 윈도우 로그 구현
  │   └── SlidingWindowCounterStrategy  슬라이딩 윈도우 카운터 구현
  └── factory/
      └── RateLimitStrategyFactory      팩토리 + 설정 빌더

infrastructure/              기술 구현
  └── redis/
      ├── RedisScriptExecutor          Lua 스크립트 실행 인터페이스
      └── RedisScriptExecutorImpl      SHA1 캐싱, SCAN 기반 키 관리

config/                      Spring 설정
  └── RedisConfig                      Redis 템플릿 + 직렬화 설정
```

### 핵심 설계 패턴

| 패턴 | 적용 |
|------|------|
| **Strategy** | 5가지 알고리즘을 동일 인터페이스로 교체 가능 |
| **Factory** | `RateLimitStrategyFactory`로 알고리즘 + 설정 조합 생성 |
| **Builder** | `StrategyConfig` 빌더로 안전한 설정 구성 |
| **AOP** | `@RateLimit` 어노테이션으로 비즈니스 로직과 분리 |
| **Fail-Open** | Redis 장애 시 요청 허용 (가용성 우선) |
| **Value Object** | `RateLimitResult`, `RateLimitMetadata` 불변 객체 |

### 동시성 전략

| 구성요소 | 전략 | 이유 |
|----------|------|------|
| Lua Script | Redis 단일 스레드 실행 | 원자적 읽기-수정-쓰기 보장 |
| Script 캐싱 | `ConcurrentHashMap` | lock-free 읽기 |
| 도메인 모델 | 불변 객체 | 동기화 불필요 |
| 키 조회 | SCAN (not KEYS) | 비차단 순회 |

> 아키텍처 상세는 [docs/architecture.md](docs/architecture.md)를 참고하세요.

---

## 해결한 기술적 이슈

| # | 이슈 | 원인 | 해결 |
|---|------|------|------|
| 1 | Clock Skew | 클라이언트/서버 시간 불일치 | `redis.call('TIME')` 서버 시간 사용 |
| 2 | Leaky Bucket 정밀도 손실 | `math.floor(delta * rate)` 누적 오차 | 역산 방식: `leaked / rate`로 정확한 시간 전진 |
| 3 | TokenBucket 부동소수점 손실 | SETEX/GET 문자열 변환 시 정밀도 유실 | Lua `tostring()` + Java `Double.parseDouble()` |
| 4 | SlidingWindowLog Undercounting | 같은 마이크로초 요청의 ZADD 덮어쓰기 | 원자적 시퀀스 넘버로 고유 멤버 생성 |
| 5 | KEYS 명령어 블로킹 | `KEYS *` O(N) 전체 서버 차단 | SCAN 커서 기반 비차단 순회 |
| 6 | 429 헤더 null 값 | resetAt이 null일 때 빈 헤더 | null 체크 후 조건부 헤더 추가 |

> 각 이슈의 상세 분석은 [docs/troubleshooting.md](docs/troubleshooting.md)를 참고하세요.

---

## 테스트 전략

```
presentation/
  ├── controller/
  │   ├── RateLimitControllerIntegrationTest   REST 엔드포인트 통합 테스트
  │   │     ├── HTTP 상태 코드 검증 (200, 429)
  │   │     ├── 응답 헤더 검증
  │   │     ├── 동시 요청 처리 (15 requests, limit=10)
  │   │     └── Rate Limit 초기화 기능
  │   └── GlobalExceptionHandlerTest           예외 → HTTP 매핑 검증
  │         ├── 429 응답 + X-RateLimit-* 헤더
  │         ├── 400 Bad Request 매핑
  │         └── 500 메시지 마스킹 (보안)

application/
  └── service/
      └── RateLimiterServiceIntegrationTest    서비스 계층 통합 테스트
            ├── 허용/거부 판정 정확성
            ├── 식별자별 독립 제한
            └── 초기화 후 재시작

domain/
  └── factory/StrategyConfigTest               설정 빌더 검증
  └── strategy/StrategyConstructorTest         생성자 유효성 검증

infrastructure/
  └── redis/RedisIntegrationTest               Redis + Lua 통합 테스트
        ├── 연결 및 스크립트 실행
        ├── SCAN 기반 키 조회
        ├── 부동소수점 정밀도 보존
        └── 스크립트 캐싱 검증
```

### 테스트 환경

- **TestContainers**: Redis 7-alpine 실제 컨테이너 사용
- **테스트 격리**: 매 테스트 전 `FLUSHALL`
- **동적 속성**: 런타임 Redis 연결 정보 주입

---

## 설계 원칙 요약

| 원칙 | 적용 |
|------|------|
| **불변성** | Value Object, 방어적 복사 |
| **Fail-Fast** | 생성자/빌더에서 즉시 검증, 의미 있는 예외 |
| **Fail-Open** | Redis 장애 시 요청 허용 (가용성 > 정확성) |
| **DIP** | `RateLimitStrategy`, `RedisScriptExecutor` 인터페이스 |
| **관심사 분리** | domain → application → infrastructure → presentation |
| **보안** | 내부 예외 메시지 마스킹, 검증 전용 예외 분리 |

---

## 주차 연계

이 프로젝트는 다음 주차의 기반이 됩니다:

- **Week 2** (Consistent Hashing): Rate Limit 대상 노드를 해시 링으로 라우팅
- **Week 3** (KV Store): 분산 캐시의 각 노드에 Rate Limiting 적용
