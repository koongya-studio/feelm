# 온보딩 소급 보정 실행·점검과 되돌리기

S15P21E106-648이 배포 DB에서 한 번 수행하는 데이터 보정의 실행 계획이다.
마이그레이션은 `V202609192100__backfill_onboarding_ratings.sql`이며 스키마는 바꾸지 않는다.

S15P21E106-647이 API-024 제출 경로를 LIKE 4.0점·DISLIKE 2.0점 평가와 감상 기록·프레임
생성으로 바꾸기 전에 온보딩을 마친 사용자는 `onboarding_preferences`만 있고 평가가 없다.
그 행을 같은 규칙으로 한 번 보정한다. 저장 규칙의 정본은 `RatingStateWriter.create`이고,
API 계약은 [API-024](../../docs/api/onboarding-ott.md)·[API-036](../../docs/api/watch.md),
삭제·마이그레이션 정책은 [DB 규칙](../../docs/db-schema-convention.md)을 따른다.

## 보정 규칙

- 대상은 `users.status = 'ACTIVE'`인 사용자의 온보딩 선택 중 **같은 영화의 평가가 없는 것**뿐이다.
  탈퇴 계정(ERD D-9 익명화)은 로그인할 수 없고 영화 평균만 흔들므로 제외한다.
- 이미 실제 평가가 있는 영화는 점수·한줄평·공개 범위·감상 기록을 건드리지 않는다.
- 활성 감상 기록(EXPIRED 아님)이 없으면 `RATED` 기록을 새로 만들고, 있으면 그 기록을 `RATED`로
  전이시켜 평가에 연결한다(ERD D-6: 사용자·영화별 활성 기록 1개).
- 평가마다 프레임을 하나 만든다. 팝콘은 저장 대상이 아니라 `ratings`와
  `movies.representative_flavor_id`로 집계되므로 평가가 생기면 함께 생긴다.
- 보정 대상 영화의 `average_rating`·`rating_count`를 `ratings` 전체 재집계로 다시 계산한다.
- 한 실행이 만든 모든 행의 `created_at`·`rated_at`은 **같은 마커 시각**이다. 되돌리기는 이 값과,
  기동 로그가 남기는 **재사용 감상 기록의 이전 상태 목록**을 쓴다.
- 재실행해도 중복되지 않는다. 대상 산출이 "평가 없음"을 조건으로 하고 삽입에
  `ON CONFLICT DO NOTHING`을 둔다.

## 실행 전제

1. **S15P21E106-647이 먼저 배포되어 있어야 한다.** 이 마이그레이션은 647이 정한 규칙을
   기존 행에 맞추는 후속 보정이라, 새 제출 경로가 없는 배포본에 적용하면 신규 사용자와
   기존 사용자의 저장 결과가 다시 갈린다. 두 변경이 같은 `develop` 계보에 있으므로
   이 브랜치를 배포하면 전제는 자동으로 충족된다.
2. 마이그레이션은 **애플리케이션이 떠 있지 않은 상태**를 전제한다. 현재 배포는 컨테이너를
   교체하므로 적용 중 옛 인스턴스가 살아 있지 않다(`V202609181430` 5단계와 같은 전제).
   무중단 배포로 바꾸면 이 보정이 잡는 사용자·영화 잠금과 옛 인스턴스의 API-024/API-036이
   경합한다. 대상 판정을 잠금 뒤에 두어 평가가 끼어들어도 중복이 생기지는 않지만,
   잠금 대기로 배포 시간이 길어질 수 있다.
3. 적용 전 DB 백업 또는 스냅샷과 복구 가능 여부를 확인한다.

## 사전 점검

저장소 루트에서 아래 읽기 전용 조회를 실행하고 출력을 보관한다. `BACKFILL_POSTGRES_CONTAINER`는
실행자가 확인한 대상 컨테이너 이름이며 접속 비밀은 출력하거나 명령 인자에 넣지 않는다.

```bash
docker exec -i "$BACKFILL_POSTGRES_CONTAINER" sh -c \
  'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < backend/scripts/inspect-onboarding-backfill.sql
```

읽기 전용 트랜잭션이라 쓰기를 차단하지 않는다. 다음을 기록한다.

| 항목 | 출처 | 실행 전 기대 |
| --- | --- | --- |
| 보정 대상 행·사용자·영화 수, 팝콘 대상 수 | 1번 | 0보다 크다(0이면 보정할 것이 없다) |
| 활성 감상 기록이 이미 있는 대상의 이전 상태 | 2번 | **되돌리기에 필요한 유일한 정보다. 반드시 보관한다** |
| 제외 대상 건수와 사유 | 3번 | 계정 상태·기존 평가로 설명된다 |
| 필름이 비어 있는 활성 온보딩 사용자 수 | 4번 | 1번의 대상 사용자 수와 대체로 일치한다 |
| 온보딩 영화의 집계 어긋남 수 | 5번 | 참고값. 이 중 보정 대상 영화만 고쳐진다 |

2번 출력이 비어 있지 않으면 그 행의 `watch_record_id`·`previous_status`·`previous_rated_at`을
그대로 남긴다. 다만 되돌리기의 정본은 **적용 시점**에 마이그레이션이 로그로 남기는 같은 목록이다.
점검(배포 전)과 적용(배포 중) 사이에 상태가 바뀔 수 있으므로, 두 값이 다르면 로그를 따른다.

## 적용

일반 배포 절차로 애플리케이션을 기동하면 Flyway가 적용한다. 별도 실행 명령은 없다.

기동 로그에서 아래 형태의 한 줄을 찾아 보관한다. 이 줄이 Jira 완료 조건의 "보정 대상
사용자 수와 생성된 평가·프레임·팝콘 수" 기록이다.

```text
S15P21E106-648 온보딩 소급 보정: 마커=<timestamptz> 대상 사용자=<n> 대상 영화=<n>
생성 평가=<n> 생성 프레임=<n> 생성 팝콘=<n> 신규 감상 기록=<n> 재사용 감상 기록=<n>
재사용 이전 상태(id:status:rated_at)=<id:STATUS:시각 ...  또는 없음>
```

**이 줄은 되돌리기의 유일한 근거다.** 마커 시각과 재사용 목록이 없으면 3단계 복원을 할 수 없고,
2단계 삭제가 보정 이전부터 있던 감상 기록까지 지울 수 있다. 배포 기록에 그대로 옮겨 적는다.

SQL 오류가 나면 이 마이그레이션의 모든 변경이 롤백되고 Flyway와 애플리케이션 기동이 실패한다.
이미 성공한 이전 버전 마이그레이션은 되돌아가지 않는다. 정상 완료를 확인하기 전에 트래픽을
전환하지 않는다.

## 사후 점검

같은 조회 명령을 다시 실행한다.

| 확인 | 통과 조건 |
| --- | --- |
| 1번 보정 대상 | 0 |
| 4번 필름이 비어 있는 활성 온보딩 사용자 | 실행 전 값에서 로그의 "대상 사용자" 수만큼 줄어든다. **0이 아닐 수 있다** |
| 5번 온보딩 영화 집계 어긋남 | 로그의 "대상 영화"에 해당하는 영화가 남아 있지 않다. **0이 아닐 수 있다** |
| 6번 생성 시각 묶음 | 로그의 마커 시각에 `ratings` = 로그의 생성 평가 수, `frames` = 생성 프레임 수 |
| 7번 Flyway 이력 | `202609192100` 행의 `success = true` |

기존 평가가 보존되었는지는 3번의 "이미 평가 있음" 건수가 실행 전후로 같은지로 본다.

4번과 5번이 0으로 떨어지지 않는 것은 정상이며, 보정 범위가 좁기 때문이다.

- 4번에는 **이미 평가가 있어 보정 대상이 아니지만 프레임이 없는** 사용자가 남는다. 647 이전에
  평가만 만든 경로가 있었다면 그 사용자의 필름은 이 보정으로 채워지지 않는다. 남은 건수가 1번의
  실행 전 대상 사용자 수보다 많으면 그 차이가 이 경우이며, 별도 Jira로 다룬다.
- 5번에는 **보정이 건드리지 않은 온보딩 영화**의 기존 어긋남이 남는다. 이 보정은 평가를 만든
  영화만 재계산한다. Jira 완료 조건의 "영향받은 영화"는 로그의 "대상 영화"뿐이다.

## 되돌리기

Flyway Community에는 undo가 없다. 되돌릴 때는 아래 SQL을 하나의 트랜잭션에서 실행한다.
`:marker`는 기동 로그의 마커 시각이다. 2단계의 `ARRAY[...]`에는 같은 로그의 "재사용 이전 상태"
목록에 있는 `watch_record_id`를 모두 적는다. 목록이 `없음`이면 `ARRAY[]::bigint[]`를 그대로 두고
3단계를 건너뛴다. **`NULL`을 넣지 않는다.** `x <> ALL (NULL)`은 참이 아니라 NULL이라 2단계가
한 행도 지우지 않는다.

**순서를 바꾸지 않는다.** 되돌릴 대상을 먼저 고정해야 삭제 뒤에 집계 범위를 잃지 않고,
재사용 행을 제외해야 보정 이전부터 있던 감상 기록을 지우지 않는다.

```sql
BEGIN;

-- 0. 되돌릴 대상을 먼저 고정한다. 삭제하고 나면 어느 영화를 재계산해야 하는지 알 수 없다.
CREATE TEMP TABLE rollback_targets ON COMMIT DROP AS
SELECT rating.id AS rating_id, rating.user_id, rating.movie_id
FROM ratings rating
JOIN onboarding_preferences preference
  ON preference.user_id = rating.user_id
 AND preference.movie_id = rating.movie_id
WHERE rating.created_at = :marker
  AND rating.comment IS NULL
  AND rating.visibility IS NULL
  AND rating.score IN (4.0, 2.0);

-- 확인: 행 수가 로그의 "생성 평가" 수와 같아야 한다. 다르면 여기서 멈추고 원인을 확인한다.
SELECT count(*) AS rollback_ratings FROM rollback_targets;

-- 1. 보정이 만든 평가. 프레임은 ratings -> frames CASCADE로 함께 사라진다.
DELETE FROM ratings WHERE id IN (SELECT rating_id FROM rollback_targets);

-- 2. 보정이 **새로 만든** 감상 기록. 재사용한 기존 기록은 id로 명시적으로 제외한다.
--    제외하지 않으면 모든 시각이 NULL인 WATCHED_UNRATED 기록(평가 생성 후 삭제가 남기는 상태)이
--    삭제 조건에 그대로 걸린다.
DELETE FROM watch_records watch
WHERE watch.rated_at = :marker
  AND watch.status = 'RATED'
  AND watch.id <> ALL (ARRAY[]::bigint[])  -- 로그의 재사용 watch_record_id를 모두 넣는다
  AND watch.clicked_at IS NULL
  AND watch.watched_at IS NULL
  AND watch.expires_at IS NULL
  AND watch.confirm_due_at IS NULL
  AND watch.expired_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM ratings r WHERE r.watch_record_id = watch.id);

-- 3. 보정이 RATED로 전이시킨 기존 감상 기록. 로그의 "재사용 이전 상태" 값으로 되돌린다.
--    항목마다 한 번씩 실행한다. 이 값은 DB에 남아 있지 않으므로 로그가 없으면 복원할 수 없다.
UPDATE watch_records
SET status = '<previous_status>', rated_at = <previous_rated_at 또는 NULL>
WHERE id = <watch_record_id> AND rated_at = :marker;

-- 4. **보정이 평가를 만든 영화만** 재계산한다. 온보딩 영화 전체를 재계산하면 보정과 무관한
--    영화의 기존 집계까지 덮어써 되돌릴 수 없게 된다.
UPDATE movies movie
SET rating_count = aggregate.rating_count,
    average_rating = aggregate.average_rating
FROM (
    SELECT movie_id,
           count(*)::int AS rating_count,
           coalesce(round(avg(score), 2), 0) AS average_rating
    FROM ratings
    WHERE movie_id IN (SELECT DISTINCT movie_id FROM rollback_targets)
    GROUP BY movie_id
) aggregate
WHERE movie.id = aggregate.movie_id;

-- 5. 그중 평가가 하나도 남지 않은 영화의 집계를 0으로 되돌린다. 4번의 GROUP BY가 이 영화를
--    돌려주지 않아 값이 그대로 남기 때문이다.
UPDATE movies movie
SET rating_count = 0, average_rating = 0
WHERE movie.id IN (SELECT DISTINCT movie_id FROM rollback_targets)
  AND NOT EXISTS (SELECT 1 FROM ratings r WHERE r.movie_id = movie.id);

COMMIT;
```

되돌린 뒤 사전 점검을 다시 실행해 1번 대상 수가 실행 전 값으로 돌아왔는지 확인한다.
`flyway_schema_history`의 행은 지우지 않는다. 다시 보정해야 하면 앞으로 가는 새
마이그레이션을 추가한다(DB 규칙 8장: 적용된 마이그레이션 파일은 수정하지 않는다).

## 알려진 한계

- **사용자가 스스로 지운 평가를 되살린다.** 대상 판정은 "지금 평가가 없다"만 보고, 평가 삭제
  이력은 DB에 남지 않는다. 온보딩에서 고른 영화의 평가를 사용자가 지웠다면 이 보정이 4.0 또는
  2.0으로 다시 만든다. Jira가 정한 대상 규칙("같은 영화에 이미 실제 평가가 있는 사용자는
  건너뛴다")이 영화 단위라 이 범위에서는 구별할 수 없다. 같은 이유로 **수동 재적용은 위험하다.**
  이 마이그레이션은 Flyway가 한 번만 적용하며, 되돌린 뒤 다시 보정해야 하면 삭제 이력을 함께
  다루는 별도 Jira로 처리한다.
- 마커 시각과 재사용 목록은 기동 로그에만 남는다. 로그를 잃으면 되돌릴 수 없다. 점검 6번은
  생성 시각 묶음을 보여 주지만, 그 묶음만으로 삭제 대상을 정하지 말라는 것이 같은 절의 주의다.
- 마커는 트랜잭션 시작 시각이라 실제 쓰기 시각보다 약간 앞선다. 로그의 시각과 행의
  `created_at`은 정확히 같지만, 벽시계 기준으로 몇 초 차가 날 수 있다.
- 사용자가 마커 시각과 같은 마이크로초에 온보딩 영화를 직접 평가하면 되돌리기 1단계 조건
  (`score IN (4.0, 2.0)`·한줄평 없음·공개 범위 없음)에 걸릴 수 있다. 적용 중 애플리케이션이
  떠 있지 않다는 전제가 지켜지면 발생하지 않는다.
- 보정은 평가가 이미 있고 프레임만 없는 기존 행을 채우지 않는다. 사후 점검 4번이 0으로
  떨어지지 않는 이유이며, 필요하면 별도 Jira로 다룬다.
- 보정은 Kafka `RATING` 이벤트를 발행하지 않는다. 추천 파이프라인 입력은 최초 온보딩 제출 때
  발행된 `ONBOARDING` 이벤트를 그대로 쓴다. 재학습이 필요하면 별도 Jira로 다룬다.
- `taste_analyses`는 다시 계산하지 않는다. 이 보정의 범위가 아니다.
