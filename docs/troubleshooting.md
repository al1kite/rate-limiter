# Troubleshooting & 기술적 이슈 해결

개발 과정에서 발견한 기술적 이슈와 해결 과정을 상세히 기록합니다.

---

## Issue #1: Clock Skew — 서버 간 시간 불일치

### 문제

분산 환경에서 클라이언트와 Redis 서버의 시간이 다를 경우, Rate Limit 계산이 부정확해집니다.

```
Client A (10:00:00) ──→ Redis (10:00:03)   ← 3초 차이
Client B (10:00:01) ──→ Redis (10:00:03)

→ 실제로는 1초 차이인 요청이 0초 차이로 계산됨
```

### 원인

- 각 서버의 NTP 동기화 오차
- 네트워크 지연
- VM/컨테이너의 시간 드리프트

### 해결

모든 Lua Script에서 `redis.call('TIME')`을 사용하여 **Redis 서버 시간**을 기준으로 합니다.

```lua
-- Before (문제): 클라이언트에서 전달한 시간 사용
local now = tonumber(ARGV[5])  -- 클라이언트 시간

-- After (해결): Redis 서버 시간 사용
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
```

`redis.call('TIME')` 반환값:
- `time[1]`: Unix 타임스탬프 (초)
- `time[2]`: 마이크로초

### 영향

- 모든 Rate Limit 계산이 단일 시간 소스(Redis) 기준
- 분산 환경에서도 일관된 Rate Limiting 보장

### 관련 커밋

```
628ba00 fix: Redis 서버 시간 기준으로 RateLimit 계산하도록 수정
```

---

## Issue #2: Leaky Bucket 누적 정밀도 손실

### 문제

`math.floor(delta * leak_rate)` 연산에서 소수점 이하를 매번 버리면 **누적 오차**가 발생합니다.

### 재현 시나리오

```
leak_rate = 0.5 (초당 0.5개 누수)

호출 1: delta=3초, leaked = floor(3 × 0.5) = floor(1.5) = 1
         last_leak = now (3초 후)
         → 0.5개분의 시간이 사라짐

호출 2: delta=3초, leaked = floor(3 × 0.5) = floor(1.5) = 1
         → 또 0.5개분의 시간이 사라짐

6초 동안 실제 누수: 2개 (기대값: 6 × 0.5 = 3개)
→ 33%의 손실!
```

### 원인

```lua
-- 문제 코드
local leaked = math.floor(elapsed * leak_rate)
last_leak = now  -- ← 여기서 소수 시간을 날림
```

`now`로 타임스탬프를 갱신하면, `leaked`개를 소진하는 데 실제로 필요한 시간보다 더 많은 시간이 경과한 것으로 기록됩니다.

### 해결: 역산 방식

```lua
-- 해결 코드
local elapsed = math.max(0, now - last_leak)
local leaked = math.floor(elapsed * leak_rate)

if leaked > 0 then
    queue_size = math.max(0, queue_size - leaked)
    -- 핵심: 소진된 정수 개에 대응하는 정확한 시간만 전진
    local time_for_leaked = leaked / leak_rate
    last_leak = last_leak + time_for_leaked  -- now가 아닌 정확한 시간!
end
```

### 검증

```
leak_rate = 0.5

호출 1: delta=3초
  leaked = floor(3 × 0.5) = 1
  time_for_leaked = 1 / 0.5 = 2초
  last_leak += 2 (now가 아님!)
  → 나머지 1초는 다음 호출에 이월

호출 2: elapsed = 3 + 1(이월) = 4초... 아니, 정확히:
  elapsed = (now) - (last_leak + 2) = 3 + 1 = 4?
  → 실제: 다음 호출이 3초 후라면 elapsed = 3 + 1 = 4초
  leaked = floor(4 × 0.5) = 2
  time_for_leaked = 2 / 0.5 = 4초

6초간 총 누수: 1 + 2 = 3개 (정확!)
```

### 관련 커밋

```
8ba291c fix: LeakyBucket에서 소수 시간 손실로 인한 누적 과소 계산 문제 수정
```

---

## Issue #3: Token Bucket 부동소수점 정밀도 손실

### 문제

Lua에서 Redis로 부동소수점 값을 저장/조회할 때 정밀도가 손실됩니다.

### 재현 시나리오

```
refillRate = 1000.0

Lua에서 계산: tokens = 9.999999999999998
SETEX tokens_key 3600 9.999999999999998

GET tokens_key → "10" (Redis가 문자열로 반올림)
→ 토큰이 실제보다 많은 것으로 판정!
```

### 원인

1. Lua의 `number`는 IEEE 754 double
2. Redis의 SETEX는 값을 문자열로 저장
3. Lua → Redis 문자열 변환 시 기본 포맷(`%g`)이 정밀도를 줄임

### 해결

```lua
-- Before: 기본 숫자→문자열 변환 (정밀도 손실)
redis.call("SETEX", tokens_key, ttl, new_tokens)

-- After: tostring()으로 명시적 변환 (정밀도 보존)
redis.call("SETEX", tokens_key, ttl, tostring(new_tokens))
redis.call("SETEX", timestamp_key, ttl, tostring(now))

-- 반환값도 tostring()
return {
    allowed and 1 or 0,
    tostring(new_tokens),  -- 문자열로 반환
    capacity
}
```

Java에서 수신:
```java
// executeRawLuaScript()로 Long/String 혼합 수신
double tokens = Double.parseDouble((String) result.get(1));
```

### 관련 커밋

```
9396376 fix: TokenBucket 부동소수점 정밀도 손실 해결 (tostring 방식)
29058a4 fix: SETEX 저장 시에도 tostring() 적용 및 통합 테스트 보강
```

---

## Issue #4: Sliding Window Log Undercounting

### 문제

같은 마이크로초에 복수 요청이 들어오면 Redis Sorted Set의 ZADD가 기존 멤버를 갱신하여 **카운트가 증가하지 않습니다**.

### 재현 시나리오

```
요청 A (마이크로초: 1706000000:123456)
  ZADD log_key now "1706000000:123456"  → 새 entry 추가 (ZCARD = 1)

요청 B (같은 마이크로초: 1706000000:123456)
  ZADD log_key now "1706000000:123456"  → score 갱신만! (ZCARD = 여전히 1)

→ 2개의 요청이 1개로 카운트됨
```

### 원인

Redis ZADD의 동작: 같은 member가 이미 존재하면 score만 업데이트하고 새 entry를 추가하지 않음.

### 해결: 원자적 시퀀스 넘버

```lua
-- Before: 타임스탬프만 사용 (충돌 가능)
local member = time[1] .. ":" .. time[2]

-- After: 원자적 시퀀스 넘버로 고유성 보장
local seq = redis.call("INCR", seq_key)
redis.call("EXPIRE", seq_key, window * 2)
local member = time[1] .. ":" .. time[2] .. ":" .. seq
```

결과:
```
요청 A → member = "1706000000:123456:1"  (고유)
요청 B → member = "1706000000:123456:2"  (고유)
→ ZCARD = 2 (정확!)
```

**`math.random()` 대신 `INCR`을 사용한 이유**:
- `math.random()`은 충돌 가능성이 있음 (생일 문제)
- `INCR`은 원자적이며 100% 고유성 보장
- Lua Script 내에서도 Redis 명령으로 원자성 유지

### 관련 커밋

```
5acef34 fix: SlidingWindowLog에서 동일 마이크로초 충돌로 인한 undercounting 문제 수정
```

---

## Issue #5: KEYS 명령어 프로덕션 위험

### 문제

Redis의 `KEYS *` 명령어는 **O(N)으로 전체 서버를 차단**합니다.

```
KEYS "rate_limit:fixed_window:*"
→ 100만 개 키가 있으면 전체 서버가 수 초간 멈춤
→ 다른 모든 클라이언트도 대기
```

### 원인

- `KEYS`는 단일 스레드인 Redis 전체를 블로킹
- 프로덕션에서 키 수가 증가하면 응답 지연 급증

### 해결: SCAN 커서 기반 순회

```java
// Before: KEYS 명령어 (위험)
Set<String> keys = redisTemplate.keys(pattern);

// After: SCAN 커서 기반 (안전)
public List<String> findKeys(String pattern) {
    List<String> keys = new ArrayList<>();
    ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();

    try (Cursor<String> cursor = redisTemplate.scan(options)) {
        while (cursor.hasNext()) {
            keys.add(cursor.next());
        }
    }
    return keys;
}
```

**SCAN의 장점**:
- 커서 기반으로 점진적 순회
- 각 반복에서 소량만 처리 (기본 COUNT=100)
- 다른 명령어가 중간에 실행 가능 (비차단)

### 관련 커밋

```
412cad2 perf: Redis KEYS 제거 및 Lua 스크립트 캐싱 적용
```

---

## Issue #6: X-RateLimit-Reset 헤더 null 처리

### 문제

`resetAt`이 null일 때 빈 문자열 헤더가 생성됩니다.

```
X-RateLimit-Reset:     ← 빈 값 (잘못된 HTTP 헤더)
```

### 해결

```java
// Before
response.setHeader("X-RateLimit-Reset",
    String.valueOf(result.getResetAt().getEpochSecond()));

// After
if (result.getResetAt() != null) {
    response.setHeader("X-RateLimit-Reset",
        String.valueOf(result.getResetAt().getEpochSecond()));
}
```

### 관련 커밋

```
a1177ad refactor: 예외 메시지 일관성 및 X-RateLimit-Reset 헤더 처리 개선
```

---

## 보안: 예외 메시지 마스킹

### 문제

내부 예외 메시지가 클라이언트에 노출되면 보안 취약점이 됩니다.

```json
// 위험: 내부 정보 노출
{
    "error": "Connection refused: localhost:6379"
}
```

### 해결

```java
// GlobalExceptionHandler
@ExceptionHandler(Exception.class)
public ResponseEntity<RateLimitDto.ErrorResponse> handleGenericException(Exception e) {
    // 내부 메시지 마스킹
    return ResponseEntity
        .status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new RateLimitDto.ErrorResponse("Internal server error"));
}
```

**설계 결정**:
- `InvalidRequestException` → 400: 사용자가 수정 가능한 에러 (메시지 노출)
- `IllegalArgumentException` → 500 (not 400): 프로그래밍 에러일 수 있으므로 마스킹
- `Exception` → 500: 항상 마스킹

### 관련 커밋

```
930b5d9 fix: validation 예외를 400으로 처리하고 내부 오류 노출 방지
d4c5813 fix: IllegalArgumentException을 400으로 처리하고 내부 오류 메시지 마스킹
d63d9a1 refactor: 요청 검증 전용 InvalidRequestException 도입
```

---

## RateLimitAspect 파라미터 방어

### 문제

SpEL 표현식이 참조하는 파라미터 수와 실제 메서드 인자 수가 다를 때 `ArrayIndexOutOfBoundsException` 발생.

### 해결

```java
// 길이 체크 후 최소값 범위만 순회
if (paramNames.length != args.length) {
    log.warn("Parameter mismatch in method {}: expected {} parameters, but got {}.",
             signature.getMethod().getName(), paramNames.length, args.length);
}
for (int i = 0; i < Math.min(paramNames.length, args.length); i++) {
    context.setVariable(paramNames[i], args[i]);
}
```

### 관련 커밋

```
c5e8e9b refactor: RateLimitAspect 파라미터 불일치 방어 및 경고 로그 추가
```
