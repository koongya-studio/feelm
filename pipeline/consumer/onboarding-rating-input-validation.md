# S15P21E106-649 온보딩 평가 입력 전환 검증

## 비교 기준

S15P21E106-647 이후 Backend는 온보딩 LIKE·DISLIKE를 각각 `4.0`·`2.0` 평가로 저장하고,
S15P21E106-648은 기존 온보딩 행을 같은 평가로 소급 생성한다. 따라서 649의 변경 전 비교 기준은
“온보딩 상수만 사용하던 더 이전 구현”이 아니라 두 원천을 함께 읽던 당시
`origin/develop` Consumer다. 아래 값은 현재 계약이 아니라 변경 전 golden 비교용 입력이다.

- `ratings`: 영화 10=`4.0`, 영화 20=`2.0`
- `onboarding_preferences` 변환값: 영화 10=`4.271231616529622`, 영화 20=`2.798442509805467`

변경 전 `merge_inputs`는 같은 `movie_id`의 평가가 온보딩 변환값을 덮어쓰므로 실제 계산 입력은
`[(10, 4.0, RATING), (20, 2.0, RATING)]`이다. 변경 후에는 `ratings`만 읽어 동일한 입력을 만든다.

## 독립 golden 결과

MR 514의 변경 전 `origin/develop` 기준 커밋 `44e8456b`에서 `pipeline/consumer`를 별도 임시 디렉터리로
archive한 뒤, 그 사본의 생산 `calculate_user_results`를 아래 표의 입력과 같은 ALS·콘텐츠·그룹 fixture로
직접 실행했다. 시각은 `2026-09-15T00:00:00Z`로 고정하고 실행마다 달라지는 `generationId`만 제거해
전체 vector·Candidate payload를 기록했다. 현재 브랜치의 생산 함수는 golden 산출에 사용하지 않았다.

`tests/test_service.py::test_backfilled_onboarding_profile_matches_rating_only_candidates`와
`test_rating_only_transition_boundaries_match_pre_649_golden`은 이 고정 golden을 전체 payload로 비교한다.
테스트 안에서 변경 전 병합을 다시 구현하거나 변경 후 함수를 양쪽 비교값으로 호출하지 않는다.

| 경계 | 변경 전 golden과 변경 후 기대 |
| --- | --- |
| 평가 0건 | TASTE `[10, 20, 30]` 0점 동점, DISCOVERY 없음 |
| 평가 1건 | `ratingCount=1`, factor 1건, TASTE `[10, 30, 20]`, DISCOVERY `[30]` |
| 같은 영화 rating/onboarding 충돌 | rating 4.0·2.0이 우선된 전체 vector·TASTE·DISCOVERY payload 동일 |
| ALS mapping 누락 평가 포함 | `ratingCount=2`, factor 1건, TASTE `[10, 20, 30]`, DISCOVERY 없음 |

이 비교는 정상적으로 소급 보정된 사용자에게 649가 후보 정책 변경이 아니라 죽은 이중 입력 경로 제거임을
확인한다. 평가가 없는 과거 온보딩 행은 변경 후 계산 신호가 사라지므로, 운영 전환 전에
`V202609192100__backfill_onboarding_ratings.sql` 적용과
`backend/scripts/inspect-onboarding-backfill.sql`의 raw 대상 중 설명되지 않은 미보정 행이 0건인지
확인해야 한다. migration 이후 사용자가 평가를 삭제한 행은 온보딩만 남아도 정상이며 복원하지 않는다.

## 이벤트 중복 검증

Backend 계약 테스트는 온보딩 제출 한 번에 `feelm.rating.v1`의 `ONBOARDING` 아웃박스 이벤트 한 건만
생성하고 선택별 `RATING` 이벤트를 만들지 않음을 검증한다. Consumer의
`tests/test_runner.py::test_onboarding_event_reads_snapshot_and_calculates_once_per_delivery`는 실제
`process_user` 경계를 통과해 ONBOARDING record 한 건당 DB snapshot·계산·Redis 게시·Candidate 알림·input
offset commit이 각각 한 번임을 검증한다. 같은 record를 두 번 재전달하면 각 delivery가 최신 snapshot을
한 번씩 다시 읽는다. payload의 가짜 영화 999/0.5점은 계산에 전달되지 않고 DB의 4.0·2.0 평가만 사용한다.

실제 Kafka broker의 offset 재전달과 rebalance는 이 단위 테스트 범위가 아니다. rating-only 시그니처로
복구한 `tools/verify_fencing_integration.py`와 `tools/verify_fencing_runner_e2e.py`를 승인된 격리 환경에서
별도로 실행해야 한다.

## 운영 전환과 롤백

S15P21E106-648 migration 확인과 S15P21E106-735 전체 사용자 Candidate 재게시는
S15P21E106-649 병합 선행 조건이 아니라 신규 Consumer 운영 배포·전환 게이트다.

649 배포 담당자는 `backend/scripts/ONBOARDING-BACKFILL-OPERATIONS.md`의 사후 점검으로 Flyway
`202609192100 success=true`를 확인한다. raw 미평가 온보딩 행은 정상적인 migration 이후 평가 삭제인지
삭제 이력과 `WATCHED_UNRATED`·rating 없음·frame 없음 상태로 분류한다. 확인된 삭제 행은 재생성하지 않고,
raw 건수·확인된 삭제 건수·설명되지 않은 잔여 0건을 GitLab 배포 job artifact와 MR 배포 체크리스트에
남긴다. 설명되지 않은 행이 남거나 증거가 없으면 배포를 중단한다.

Candidate 키에는 TTL이 없고 648 migration은 재계산 이벤트를 발행하지 않으므로, 새 Consumer 배포 뒤
S15P21E106-735의 승인된 전체 추천 재구축 절차로 모든 활성 사용자를 처리해야 한다.
대상·성공·실패·재시도 건수, 새
`generationId`, `directionSource=ONBOARDING` 잔존 0건을 확인해야 완료다. 저장소에는 아직 이 전체 재구축
실행기가 없으므로 승인된 실행 수단과 실제 실행 증거가 없으면 운영 게이트는 닫히지 않는다. 실패 시 이전
Consumer 이미지를 복구하고 같은 전체 재구축으로 이전 정책 후보를 복원한다.

## 검증 한계와 배포 조건

- 합성 fixture 검증이며 운영 사용자 데이터나 실제 모델 품질을 측정하지 않았다.
- HDFS 산출물 형식과 Redis Candidate schema는 변경하지 않는다.
- 운영 DB 소급 보정, 전체 활성 사용자 재게시, 실제 Kafka/PostgreSQL/Redis fencing 검증은 실행하지 않았다.
