# Rate Limiting 알고리즘 상세

5가지 알고리즘의 원리, Lua Script 구현, 트레이드오프를 상세히 설명합니다.

---

## 1. Token Bucket

### 원리

버킷에 토큰이 일정 속도(refillRate)로 채워지며, 요청마다 토큰 1개를 소비합니다.
토큰이 없으면 요청을 거부합니다. 버킷 용량(capacity)까지 토큰이 쌓이므로 **버스트 트래픽을 허용**합니다.

```
시간 ────────────────────────────────────→

토큰 수:  10 → 9 → 8 → ... → 0 (거부!)
           ↑    ↑    ↑
         요청  요청  요청

       ↓ 시간 경과 (refillRate=1/sec) ↓

토큰 수:  0 → 1 → 2 → 3 (다시 허용)
```

### Redis 키 구조

| 키 | 값 | TTL |
|----|-----|-----|
| `rate_limit:token_bucket:{id}:tokens` | 현재 토큰 수 (double) | 3600s |
| `rate_limit:token_bucket:{id}:timestamp` | 마지막 리필 시간 (double) | 3600s |

### Lua Script

```lua
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

-- Redis 서버 시간 사용 (clock skew 방지)
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

local tokens_key = key .. ":tokens"
local timestamp_key = key .. ":timestamp"

local last_tokens = tonumber(redis.call("GET", tokens_key))
local last_refreshed = tonumber(redis.call("GET", timestamp_key))

if last_tokens == nil then
    last_tokens = capacity
end
if last_refreshed == nil then
    last_refreshed = now
end

-- 경과 시간 계산 및 토큰 리필
local delta = math.max(0, now - last_refreshed)
local filled_tokens = math.min(capacity, last_tokens + (delta * rate))

-- 요청 처리 가능 여부 확인
local allowed = filled_tokens >= requested
local new_tokens = filled_tokens

if allowed then
    new_tokens = filled_tokens - requested
end

-- tostring()으로 부동소수점 정밀도 보존
redis.call("SETEX", tokens_key, ttl, tostring(new_tokens))
redis.call("SETEX", timestamp_key, ttl, tostring(now))
return {
    allowed and 1 or 0,
    tostring(new_tokens),
    capacity
}
```

### 핵심 포인트

- **`tostring()` 사용**: Lua의 숫자 → 문자열 변환 시 정밀도 손실 방지 (Issue #3)
- **`redis.call('TIME')`**: 서버 시간 사용으로 분산 환경 Clock Skew 방지 (Issue #1)
- **`math.min(capacity, ...)`**: 토큰이 용량을 초과하지 않도록 보장

### 트레이드오프

| 장점 | 단점 |
|------|------|
| 버스트 트래픽 유연하게 처리 | 메모리 사용 (토큰 수 + 타임스탬프) |
| 평균 요청률 제어에 효과적 | 시간 동기화 필요 |
| 구현이 직관적 | 파라미터 튜닝 필요 (capacity, refillRate) |

### 적합한 사용처

- API Gateway Rate Limiting
- 평균 요청률 제어가 중요한 서비스
- 일시적 트래픽 급증 허용이 필요한 경우

---

## 2. Leaky Bucket

### 원리

요청이 큐에 들어가고, 일정한 속도(leakRate)로 "누수"됩니다.
큐가 가득 차면 새 요청을 거부합니다. **출력 속도가 항상 일정**합니다.

```
        요청 유입 (불규칙)
        ↓  ↓↓  ↓    ↓↓↓
    ┌─────────────────────┐
    │  ■ ■ ■ ■ ■ ■       │  ← 큐 (capacity=10)
    │  ■ ■ ■              │
    └────────┬────────────┘
             │
             ▼  누수 (일정 속도: leakRate/sec)
        처리 완료
```

### Redis 키 구조

| 키 | 값 | TTL |
|----|-----|-----|
| `rate_limit:leaky_bucket:{id}:queue` | 현재 큐 크기 (int) | 3600s |
| `rate_limit:leaky_bucket:{id}:timestamp` | 마지막 누수 시간 (double) | 3600s |

### Lua Script

```lua
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local leak_rate = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

local queue_key = key .. ":queue"
local timestamp_key = key .. ":timestamp"

local last_leak = tonumber(redis.call("GET", timestamp_key))
local queue_size = tonumber(redis.call("GET", queue_key))

if last_leak == nil then last_leak = now end
if queue_size == nil then queue_size = 0 end

-- [핵심] 정밀 시간 기반 계산 (Issue #2)
local elapsed = math.max(0, now - last_leak)
local leaked = math.floor(elapsed * leak_rate)

if leaked > 0 then
    queue_size = math.max(0, queue_size - leaked)
    -- 소진된 정수 개에 정확히 대응하는 시간만큼만 전진
    local time_for_leaked = leaked / leak_rate
    last_leak = last_leak + time_for_leaked
end

local allowed = queue_size < capacity

if allowed then
    queue_size = queue_size + 1
end

redis.call("SETEX", queue_key, ttl, queue_size)
redis.call("SETEX", timestamp_key, ttl, last_leak)

return {
    allowed and 1 or 0,
    queue_size,
    capacity
}
```

### 핵심 포인트: 정밀도 손실 해결 (Issue #2)

**기존 방식의 문제**:
```lua
-- 기존: math.floor(delta * leak_rate)로 소수점 버림 → 누적 오차
-- leak_rate=0.5, 3초 경과 → leaked=1 (실제 1.5개여야 함, 0.5 손실)
-- 이후 3초 경과 → leaked=1 (또 0.5 손실)
-- 6초간 총 2개만 소진 (실제: 6×0.5=3개)
```

**해결: 역산 방식**
```lua
-- leaked개를 소진하는 데 정확히 필요한 시간만큼만 last_leak 전진
-- leak_rate=0.5, 3초 경과
-- leaked = floor(3×0.5) = 1
-- time_for_leaked = 1/0.5 = 2초
-- last_leak += 2 (3이 아닌 2!)
-- → 다음 호출 시 elapsed는 1초부터 시작 → 오차 없음
```

### 트레이드오프

| 장점 | 단점 |
|------|------|
| 일정한 처리 속도 보장 | 버스트 트래픽 처리 불가 |
| 네트워크 대역폭 제어에 적합 | 큐 대기로 지연 발생 가능 |
| 트래픽 평활화 효과 | Token Bucket 대비 유연성 부족 |

### 적합한 사용처

- 네트워크 대역폭 제어
- 일정한 처리율이 보장되어야 하는 시스템
- 메시지 큐 소비 속도 제한

---

## 3. Fixed Window Counter

### 원리

고정된 시간 윈도우(예: 60초) 내 요청 수를 카운트합니다.
윈도우가 바뀌면 카운터가 리셋됩니다. **가장 단순한 구현**입니다.

```
|--- Window 1 (00:00~01:00) ---|--- Window 2 (01:00~02:00) ---|
   ■■■■■■■■ (8 requests)          ■■■ (3 requests)

⚠️ 경계 문제:
|--- Window 1 ---|--- Window 2 ---|
           ■■■■■■■■■■  ■■■■■■■■■■
           (끝 10개)    (시작 10개)  ← 2초 내 20개 통과!
```

### Redis 키 구조

| 키 | 값 | TTL |
|----|-----|-----|
| `rate_limit:fixed_window:{id}:{window_id}` | 요청 카운트 (int) | window×2 |

### Lua Script

```lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

-- 현재 윈도우 키 생성
local window_id = math.floor(now / window)
local window_key = key .. ":" .. window_id

local current = tonumber(redis.call("GET", window_key))
if current == nil then current = 0 end

local allowed = current < limit

if allowed then
    current = redis.call("INCR", window_key)
    redis.call("EXPIRE", window_key, window * 2)
end

-- 윈도우 리셋 시간 계산
local reset_at = (window_id + 1) * window

return {
    allowed and 1 or 0,
    current,
    limit,
    reset_at
}
```

### 트레이드오프

| 장점 | 단점 |
|------|------|
| 구현이 매우 간단 | 윈도우 경계에서 2배 트래픽 가능 |
| 메모리 효율적 (카운터 1개) | 정확도 낮음 |
| 빠른 성능 | 버스트 제어 불가 |

### 적합한 사용처

- 단순한 Rate Limiting이 필요한 경우
- 성능이 최우선인 고트래픽 시스템
- 정확도보다 속도가 중요한 시나리오

---

## 4. Sliding Window Log

### 원리

각 요청의 타임스탬프를 Redis Sorted Set에 기록합니다.
슬라이딩 윈도우 내의 요청만 카운트하여 **가장 정확한 Rate Limiting**을 제공합니다.

```
현재 시각: 10:01:30

    ← --------- 60초 윈도우 ---------- →
    10:00:30                       10:01:30
    |  req  req  req  req  req  req  |
    |  (오래된 것은 ZREMRANGEBYSCORE) |

    현재 요청 수 = ZCARD = 6  →  limit(10) 미만이므로 허용
```

### Redis 키 구조

| 키 | 값 | TTL |
|----|-----|-----|
| `rate_limit:sliding_window_log:{id}:log` | Sorted Set (score=시간, member=고유ID) | window×2 |
| `rate_limit:sliding_window_log:{id}:seq` | 원자적 시퀀스 넘버 (int) | window×2 |

### Lua Script

```lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

local log_key = key .. ":log"
local seq_key = key .. ":seq"

-- 윈도우 밖의 오래된 요청 제거
local window_start = now - window
redis.call("ZREMRANGEBYSCORE", log_key, 0, window_start)

-- 현재 윈도우 내 요청 수 확인
local current = redis.call("ZCARD", log_key)

local allowed = current < limit

if allowed then
    -- [핵심] 원자적 시퀀스로 멤버 고유성 보장 (Issue #4)
    local seq = redis.call("INCR", seq_key)
    redis.call("EXPIRE", seq_key, window * 2)
    local member = time[1] .. ":" .. time[2] .. ":" .. seq
    redis.call("ZADD", log_key, now, member)
    redis.call("EXPIRE", log_key, window * 2)
    current = current + 1
end

return {
    allowed and 1 or 0,
    current,
    limit,
    window_start
}
```

### 핵심 포인트: Undercounting 해결 (Issue #4)

**기존 문제**: 같은 마이크로초에 복수 요청이 들어오면 ZADD가 기존 멤버의 score만 갱신하여 undercounting 발생

```
-- 문제 상황
ZADD log_key now "1706000000:123456"  → 새 entry 추가 (OK)
ZADD log_key now "1706000000:123456"  → score 갱신만! (카운트 증가 안됨)

-- 해결: 원자적 시퀀스 넘버 추가
ZADD log_key now "1706000000:123456:1"  → 새 entry
ZADD log_key now "1706000000:123456:2"  → 다른 entry (고유!)
```

### 트레이드오프

| 장점 | 단점 |
|------|------|
| 가장 정확한 Rate Limiting | 메모리 사용량 높음 (모든 타임스탬프 저장) |
| 버스트 완벽 차단 | 성능 오버헤드 (ZRANGEBYSCORE, ZCARD) |
| 경계 문제 없음 | 대량 트래픽에서 Sorted Set 크기 증가 |

### 적합한 사용처

- 정밀한 Rate Limiting이 필수인 경우
- 결제, 인증 등 엄격한 제한이 필요한 시스템
- 트래픽이 비교적 낮은 API

---

## 5. Sliding Window Counter (권장)

### 원리

Fixed Window와 Sliding Window Log의 **하이브리드**입니다.
이전 윈도우와 현재 윈도우의 카운터를 가중 평균하여 근사적 슬라이딩 윈도우를 구현합니다.

```
공식: weighted_count = prev_count × (1 - elapsed%) + curr_count

|--prev window--|--curr window--|
   prev: 8개       curr: 3개
                   ↑ 경과 30초/60초 = 50%

weighted = 8 × (1 - 0.5) + 3 = 7  →  limit(10) 미만 → 허용
weighted = 8 × (1 - 0.1) + 3 = 10.2  →  limit(10) 이상 → 거부
```

### Redis 키 구조

| 키 | 값 | TTL |
|----|-----|-----|
| `rate_limit:sliding_window_counter:{id}:{window_id}` | 윈도우별 요청 카운트 (int) | window×2 |

### Lua Script

```lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

-- 현재 윈도우와 이전 윈도우 ID 계산
local current_window = math.floor(now / window)
local prev_window = current_window - 1

local curr_key = key .. ":" .. current_window
local prev_key = key .. ":" .. prev_window

local prev_count = tonumber(redis.call("GET", prev_key)) or 0
local curr_count = tonumber(redis.call("GET", curr_key)) or 0

-- 현재 윈도우 내 경과 시간 비율 계산
local window_start = current_window * window
local elapsed_time = now - window_start
local elapsed_percent = elapsed_time / window

-- 가중 평균 계산
local weighted_count = prev_count * (1 - elapsed_percent) + curr_count

local allowed = weighted_count < limit

if allowed then
    curr_count = redis.call("INCR", curr_key)
    redis.call("EXPIRE", curr_key, window * 2)
    weighted_count = prev_count * (1 - elapsed_percent) + curr_count
end

local next_window_start = (current_window + 1) * window

return {
    allowed and 1 or 0,
    math.floor(weighted_count),
    limit,
    next_window_start,
    prev_count,
    curr_count
}
```

### 트레이드오프

| 장점 | 단점 |
|------|------|
| 메모리 효율적 (카운터 2개) | Fixed Window보다 약간 복잡 |
| 높은 정확도 | Sliding Window Log보다 근사적 |
| 빠른 성능 | 가중치 계산 오버헤드 (미미) |
| 경계 문제 완화 | - |

### 적합한 사용처

- **대부분의 Rate Limiting 시나리오 (권장)**
- 정확도와 성능의 균형이 필요한 경우
- 메모리 효율이 중요한 대규모 시스템

---

## 알고리즘 선택 가이드

```
Rate Limiting이 필요하다
    │
    ├─ 버스트 허용? ─── YES ──→ Token Bucket
    │
    ├─ 일정 출력 속도? ─ YES ──→ Leaky Bucket
    │
    ├─ 단순 + 고성능? ── YES ──→ Fixed Window
    │
    ├─ 최고 정확도? ──── YES ──→ Sliding Window Log
    │
    └─ 잘 모르겠다 ─────────────→ Sliding Window Counter (권장)
```

### 기본 설정값

| 알고리즘 | 파라미터 | 기본값 |
|----------|---------|--------|
| Token Bucket | capacity / refillRate | 10 / 1.0/sec |
| Leaky Bucket | capacity / leakRate | 10 / 1.0/sec |
| Fixed Window | limit / windowSize | 10 / 60sec |
| Sliding Window Log | limit / windowSize | 10 / 60sec |
| Sliding Window Counter | limit / windowSize | 10 / 60sec |
