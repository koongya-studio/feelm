# ADR-0024: 추천 이유 보강은 항목 단위 커밋, movies 참조 다중 행 쓰기는 movie_id 오름차순으로 잠근다

| 항목 | 값 |
|---|---|
| 상태 | 채택 |
| 결정일 | 2026-09-22 |
| 결정자 | 사람 결정, S15P21E106-804에서 기록 |
| 관련 | API-045, API-058, ERD 2.4, S15P21E106-792, S15P21E106-804, docs/backend-convention.md 8장 |

## 맥락

S15P21E106-792(파티 영화 추천 다시 받기) MR을 push한 뒤 CI의 `backend-mr-test` 파이프라인에서
`RecommendationExhaustionApiContractTest.nextBatchExposesGroundedReason`이 전체 2084개 중
1개 간헐적으로 실패했다. 로컬에서 파티 추천 테스트 3회 반복, CI와 동일한 `clean test
spotlessCheck --no-daemon` 전체 실행 2회, 동시성 테스트가 포함된 신설 클래스 10회 반복까지
전부 통과해 792(파티 도메인) 코드 자체의 결함이 아님을 먼저 확인했다.

원인을 추적한 결과 파티 도메인과 무관한, **개인 추천 서빙 조회와 추천 이유 보강 사이의 기존
PostgreSQL 잠금 순서 결함**이었다. 두 트랜잭션이 같은 세트의 카드(팀에서 "추천 버퍼"라고
부르는, `recommendation_items`에 저장된 TASTE 최대 20편 + DISCOVERY 최대 20편)를 서로 다른
순서로 잠갔다.

- **서빙 조회** (`RecommendationServingReader.read` → `findAndLockInitialItems`): 노출할
  카드를 `FOR UPDATE`로 잠글 때 **DISCOVERY 타입을 먼저, TASTE 타입을 나중**에 잠근다. 한
  번에 잠그는 카드 수는 화면에 보여줄 만큼(DISCOVERY 1 + TASTE 2)으로 작다.
- **추천 이유 보강** (`RecommendationReasonSnapshotWriter.complete`, 수정 전): 세트에서 아직
  문구가 확정되지 않은 카드 전부(버퍼가 막 생성된 시점에는 사실상 버퍼 전체, 최대 40장)를
  `id` 오름차순으로 뽑아 **한 트랜잭션**에 묶어 순서대로 UPDATE했다.

카드의 `id`(생성 순서)와 타입 그룹 순서가 어긋나면(예: id 206·207이 TASTE, 208이
DISCOVERY) 조회는 208 → 206, 207 순으로, 보강은 206 → 207 → 208 순으로 잠가 순환 대기가
생긴다. 보강이 세트 전체를 통째로 묶어 두는 쪽이 조회보다 훨씬 넓은 범위를 오래 쥐고 있어
충돌 확률을 키웠다.

분리 리뷰 과정에서 같은 계열의 교착 고리 두 건을 추가로 찾았다.

1. **movies 축 (리뷰 B-F1)**: 온보딩(`OnboardingRatingWriter.createMissing`)이 한
   트랜잭션에서 movies를 `movie_id` 오름차순 `FOR UPDATE`로 여러 개 잠그는데, 추천·파티의
   다중 행 INSERT(`recordExposureHistory`, `RecommendationBufferRepository.insertItems`,
   `PartyRecommendationRepository.insertItems`)는 FK 검사로 movies에 `KEY SHARE`를 항목
   순서대로 건다. `KEY SHARE`는 `FOR UPDATE`와 충돌하므로 다른 사용자 둘이 같은 영화 두 편을
   반대 순서로 건드리면 순환(`40P01`)이 날 수 있었다. 이 고리는 이번 변경이 만든 것도
   악화시킨 것도 아니라 기준 커밋에도 있던 별개 결함이었지만, 같은 Jira에서 함께 닫기로
   했다.
2. **보충 경로의 호출 분할 (2차 리뷰 B-F6)**: 1번을 정렬로 고친 뒤에도
   `RecommendationReplenisher.replenishVacancies`가 빈 슬롯마다 이력 기록을 따로 호출하면,
   각 호출은 원소 1개짜리라 정렬이 무의미해지고 트랜잭션 전체의 실제 잠금 순서는
   `movie_id`가 아니라 **호출 순서(=슬롯을 채우는 position 순)**가 결정해버려 교착이 그대로
   재현됐다.

## 결정

1. `RecommendationReasonSnapshotWriter.complete`는 세트 전체를 한 트랜잭션으로 묶지 않고
   **카드 한 장당 하나의 트랜잭션으로 autocommit**한다. 각 UPDATE는 `exposed_at IS NULL`이고
   문구가 미확정인 항목만 바꾸는 독립·멱등 연산이라 세트 단위 원자성이 필요 없다. 한 번에
   잠금을 최대 1개만 들면 어떤 상대·어떤 순서와도 순환이 성립하지 않는다.
2. 상위 코드가 이 메서드를 트랜잭션 안에서 호출하면 `IllegalStateException`으로 즉시
   실패하는 가드를 건다. 항목 단위 커밋은 나중에 누군가 호출부를 `@Transactional`로 감싸면
   조용히 사라지는 성질이라, 테스트와 운영 로그에서 바로 드러나게 한다.
3. movies를 FK로 참조하는 다중 행 INSERT/UPDATE(추천 이력 기록, 추천 버퍼 저장, 파티 버퍼
   저장)는 **`movie_id` 오름차순**으로 실행해 온보딩의 잠금 방향과 맞춘다. 화면·노출 순서는
   `position`과 응답 리스트가 따로 정하므로 이 정렬은 INSERT/UPDATE 배치 순서에만 영향을
   준다.
4. 위 정렬은 **한 트랜잭션이 다루는 전체 행을 한 번에 모아 한 번만 쓸 때만** 유효하다. 여러
   번 나눠 호출하면 각 호출 내부의 정렬은 무의미해진다. 그래서 슬롯마다 항목을 노출해야 하는
   보충 경로(`RecommendationReplenisher`)는 노출 표시(`markExposed`, movies를 잠그지 않음)는
   슬롯마다 즉시 하되, 이력 기록(`recordExposureHistory`, movies를 `KEY SHARE`로 잠금)은
   새로 노출한 항목을 모아 **트랜잭션당 한 번만** 호출한다.

## 고려한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| 항목 단위 커밋 + movie_id 오름차순 정렬 (선택) | 잠금을 한 번에 하나만 들거나 이미 정해진 방향으로만 들어 순환 자체가 성립하지 않음. 변경 범위가 추천·파티 도메인 안에 국한됨 | 세트 단위 원자성 포기(항목별 독립·멱등으로 대체). 컴파일러가 강제하지 않는 컨벤션이라 향후 신규 코드가 규칙을 어기면 재발 가능 |
| 서빙 조회와 보강의 잠금 순서를 통일 | 근본 원인을 정면으로 제거 | 서빙 순서 자체가 이미 두 번 바뀐 이력이 있고(S15P21E106-219, S15P21E106-708) 다음에 또 바뀌면 조용히 재발한다. 테스트로 잡기 어려운 타이밍 결함 |
| 보강도 조회처럼 사용자 행 잠금을 먼저 잡아 직렬화 | 확실하게 교착을 막음 | 보강이 사용자 행 잠금을 오래 쥐게 되어 같은 사용자의 조회 지연이 커짐 |
| 재시도(deadlock 발생 시 재시도) | 구현이 단순함 | 근본 원인을 없애지 못하고, 충돌이 날 때마다 Postgres의 deadlock 감지 대기(기본 1초)가 매번 붙는다 |
| 평가·온보딩의 `lockMovie`를 `FOR NO KEY UPDATE`로 완화 | `KEY SHARE`와 충돌 자체가 사라져 movies 축 문제를 원천 차단 | watch 도메인(평가·온보딩) 담당자 합의가 필요해 804 범위 밖으로 보류 |

## 결과

- **얻는 것**: 현재 코드에서 `recommendation_items`와 `movies`에 걸린 다중 행 잠금 참가자를
  전수조사해(서빙 조회, 보강, 회차 생성, 관심없음, 평가·감상, 파티 노출, 온보딩) 확인 가능한
  교착 고리를 모두 닫았다. 각 고리는 변이 검사(수정 전 코드로 신설 테스트를 돌려 해당
  테스트만 실패하는지 확인)로 검증했다.
- **감수하는 것**: 이 규칙은 컴파일러가 강제하지 않는 컨벤션(`docs/backend-convention.md`
  8장에 기록)이다. 향후 movies를 참조하는 새 다중 행 쓰기가 정렬 규칙을 따르지 않거나, 이력
  기록 호출을 다시 여러 번으로 쪼개면 같은 유형의 교착이 조용히 재발할 수 있다. 보강 도중
  일부 항목만 반영된 채 중단될 수 있으나(항목별 예외 처리는 도입하지 않음), 각 UPDATE가
  멱등이라 다음 재요청이 남은 항목을 이어받는다.
- **후속 작업**: watch 도메인의 `lockMovie`를 `FOR NO KEY UPDATE`로 완화하는 안은 담당자
  합의 후 별도 Jira로 검토한다. 보강 도중 항목 하나가 실패해도 나머지 항목을 계속 처리할지
  (현재는 중단)는 별도 검토 대상으로 남겼다.
