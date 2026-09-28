# 3. DB 덤프 파일

배포 PostgreSQL의 최신 덤프다. `db/` 폴더에 있다.

| 항목 | 값 |
| --- | --- |
| 덤프 시각 | 2026-09-28 00:21 UTC (09:21 KST) |
| 원본 | 배포 서버 `feelm-postgres` (PostgreSQL 17.11, pgvector 0.8.6, pg_trgm 1.6) |
| 스키마 버전 | Flyway 최신 `202609271200` (`V202609271200__record_party_popcorn_snapshots.sql`, 마이그레이션 47개) |
| 형식 | `pg_dump -Fc -Z9 --no-owner --no-privileges` (custom 형식) |
| 크기 | 100,047,038 bytes. GitLab 업로드를 위해 45MiB 단위 3조각으로 나눴다 |

## 3.1 파일

| 파일 | 설명 |
| --- | --- |
| `db/feelm.dump.part00` ~ `db/feelm.dump.part02` | 덤프 분할 조각 |
| `db/feelm.dump.sha256` | 합친 파일(`feelm.dump`)과 각 조각의 SHA-256 |
| `db/anonymize.sql` | 덤프에 적용한 비식별화 SQL(3.4) |

## 3.2 복원

PostgreSQL 17과 pgvector가 필요하다. 배포와 같은 `pgvector/pgvector:0.8.6-pg17-bookworm` 이미지를 권장한다.

```bash
cd exec/db
cat feelm.dump.part00 feelm.dump.part01 feelm.dump.part02 > feelm.dump
sha256sum -c feelm.dump.sha256          # 모두 OK인지 확인

docker run -d --name feelm-postgres -p 5432:5432 \
  -e POSTGRES_DB=feelm -e POSTGRES_USER=feelm -e POSTGRES_PASSWORD=feelm \
  -e POSTGRES_INITDB_ARGS=--locale=C.UTF-8 -e TZ=UTC \
  pgvector/pgvector:0.8.6-pg17-bookworm
docker cp feelm.dump feelm-postgres:/tmp/feelm.dump
docker exec feelm-postgres pg_restore -U feelm -d feelm --no-owner --no-privileges /tmp/feelm.dump
```

Windows PowerShell에서는 `cmd /c copy /b feelm.dump.part00+feelm.dump.part01+feelm.dump.part02 feelm.dump`로 합친다.
Git Bash에서 `docker exec`에 `/tmp/...` 경로를 넘길 때는 `MSYS_NO_PATHCONV=1`을 앞에 붙인다.

복원 후 백엔드를 `local` 프로파일로 띄우면(`DB_URL=jdbc:postgresql://localhost:5432/feelm`, 계정 `feelm`)
Flyway가 이미 적용된 이력을 확인하고 그대로 기동한다. 빈 DB에서 복원한 결과를 검증했다
(`pg_restore` 오류 0건, `movies` 237,817행, `users` 47행, `ratings` 1,029행).

## 3.3 포함 범위

| 구분 | 테이블 | 포함 |
| --- | --- | --- |
| 스키마 | 전체 테이블·인덱스·제약·함수·확장, `flyway_schema_history` | 전체 |
| 기준정보 | `movies` 237,817, `people` 1,211,553, `movie_people` 2,831,198, `movie_genres`, `movie_countries`, `genres`, `otts`, `movie_ott_providers`, `popcorn_flavors` | 전체 |
| 서비스 데이터 | 사용자 47명과 평가·프레임·감상 기록·파티·추천 이력·취향 리포트·알림 | 전체(비식별화) |
| 임베딩 | `movie_embeddings` | **구조만**, 데이터 제외 |

`movie_embeddings`(237,817행, 약 1.9GB, 압축해도 약 866MB)는 저장소에 올릴 수 없는 크기라 뺐다.
유사 영화 추천을 쓰려면 E5 임베딩 배치로 다시 생성해 적재한다
([`pipeline/spark/batch/embedding/README.md`](../pipeline/spark/batch/embedding/README.md)).
임베딩이 없어도 다른 화면은 동작한다.

## 3.4 비식별화

배포 DB에는 팀원·테스터 계정의 개인정보가 있어서, 덤프를 별도 임시 DB에 복원해 아래처럼 바꾼 뒤 다시 덤프했다.
배포 DB 자체는 바꾸지 않았다.

| 대상 | 처리 |
| --- | --- |
| `users.email` | `user{id}@feelm.test` |
| `users.nickname` | `사용자{id}` |
| `users.password_hash` | 원래 비밀번호가 있던 계정만 시연용 비밀번호의 BCrypt 해시로 교체. 소셜 전용 계정은 비어 있는 그대로 둠 |
| `users.birth_date` | 연도만 남기고 1월 1일로 |
| `users.avatar_storage_key` | 비움(사진 파일은 덤프에 없음) |
| `user_auth_providers.provider_user_id` | `anon-{provider}-{id}` |
| `user_feedback.comment` | 비움 |
| `notifications.title`, `body` | 문구 안의 원래 닉네임을 `사용자{id}`로 교체 |
| `password_reset_tokens`, `web_push_subscriptions`, `web_push_deliveries`, `outbox_events` | 비움 |
| `report_images.storage_key` | 가짜 경로(이미지 파일은 덤프에 없음) |

한줄평(`ratings.comment`)과 파티 이름은 서비스 콘텐츠라 그대로 두었다.

### 시연용 계정

비밀번호가 있던 이메일 계정은 모두 같은 시연용 비밀번호를 쓴다. 복원한 DB로 로컬 서비스를 띄워 로그인해 볼 수 있다.

| 항목 | 값 |
| --- | --- |
| 비밀번호 | `FeelmDemo2026!` |
| 추천 계정 | `user13@feelm.test` (프레임 67개, 파티 10개, 취향 리포트 6개). 프레임이 가장 많은 계정은 `user27@feelm.test`(141개) |
| 다른 계정 | `select id, email from users where password_hash is not null and status = 'ACTIVE';` |

이 비밀번호는 덤프를 복원한 DB에서만 유효하다. 배포 서비스 계정과는 관계없다.
