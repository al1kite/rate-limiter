# Contributing Guide

## 브랜치 전략

```
main        ← 최종 릴리즈
develop     ← 통합 브랜치 (PR 대상)
feat/#이슈번호-짧은-설명
fix/#이슈번호-짧은-설명
refactor/#이슈번호-짧은-설명
```

## 커밋 컨벤션

```
<type>: <subject>  (#이슈번호)
```

| type | 설명 |
|------|------|
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `refactor` | 리팩터링 (기능 변경 없음) |
| `test` | 테스트 추가/수정 |
| `docs` | 문서 변경 |
| `chore` | 빌드, 설정 변경 |
| `perf` | 성능 개선 |

### 예시

```
feat: Sliding Window Counter 전략 추가 (#1)
fix: TokenBucket 부동소수점 정밀도 손실 해결 (#3)
refactor: 요청 검증 전용 InvalidRequestException 도입 (#5)
test: RateLimit 전 레이어 통합 테스트 추가 (#6)
perf: Redis KEYS 제거 및 Lua 스크립트 캐싱 적용 (#8)
```

## PR 규칙

- PR 제목: `[#이슈번호] type: 간략한 설명`
- `develop` 브랜치로만 PR
- 셀프 리뷰 후 PR 오픈
- CI 통과 필수

### PR 템플릿

```markdown
## 관련 이슈
closes #이슈번호

## 변경 사항
-

## 핵심 구현 포인트
-

## 테스트
- [ ] 단위 테스트 추가
- [ ] 기존 테스트 통과
```

## 코드 컨벤션

- 도메인 모델: 불변 객체 (final 필드, 팩토리 메서드)
- 예외: `RateLimitExceededException`, `InvalidRequestException` 계층 사용
- null 처리: 생성자/빌더에서 `Fail-Fast` 검증
- 인터페이스: `RateLimitStrategy`, `RedisScriptExecutor` 등 추상화 우선
- Lua Script: 모든 시간 계산에 `redis.call('TIME')` 사용
- 테스트: TestContainers로 실제 Redis 통합 테스트
