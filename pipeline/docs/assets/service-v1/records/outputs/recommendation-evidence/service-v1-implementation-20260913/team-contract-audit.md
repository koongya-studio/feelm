# 팀 계약 읽기 감사 — 서비스 추천 v1

상태: READ_ONLY_AUDIT. 작성 2026-09-13. 새 학습·수집·팀 코드 수정·외부 쓰기를 하지 않았다.

## 기준과 현재 사용자 결정

- 팀 작업 트리 HEAD: `b622d5eed62e08823e57d3d14fa7d9bcbdb8d7c8`, 2026-09-12 01:18 KST.
- 보존된 `origin/develop`: `ae9de07be180027d90e4c809d168fab1e7dad88b`, 2026-09-13 02:49 KST. 로컬 HEAD보다 8커밋 앞.
- 보존된 119 브랜치: `84869e8685047dcc9876f9bb4a7fe01f9d19068c`, 2026-09-13 03:40 KST.
- 감사는 `git show origin/develop:<path>`와 저장된 로컬 문서를 사용했다. 이후 읽기 전용 `git ls-remote origin refs/heads/develop`로 현재 원격 develop이 위 `ae9de07`과 같음을 확인했다. 추가 fetch는 필요하지 않았으며 GitLab API·MR 상태·Notion은 재조회하지 않았다.
- `S15P21E106/AGENTS.md`, `pipeline/AGENTS.md`, `FEELM-standalone/AGENTS.md` 전체 확인. 팀 파일은 읽기 전용이다.
- 기존 `docs/jira/plans/requirements-service-structure-round-3.yml` 미추적 파일을 보존했다.

이번 구현 문서의 제품 기준은 사용자 최신 결정이다.

1. 기본 모델은 GBT. FM·ALS도 같은 조건에서 비교 가능하게 구현하되, ALS 자동 선택/자동 혼합을 기본으로 채택하지 않는다.
2. 맞춤 2편 + 발견 1편을 한 번에 제공한다.
3. 맞춤은 배치 후보 생성 → 실시간 재정렬 구조로 구현한다.
4. 발견은 8맛 × 맛당 128그룹이며 **최대 10그룹 × 그룹당 10편 = 최대 100후보**다.
5. 대중 지표 근거가 거의 없는 무명 영화는 후보에서 제외한다. TMDB(T)·KOBIS(K)는 사용자 별점 반응 특징과 후보 순위 양쪽에서 상호 보완한다.

정책 수치의 단일 기준은 새 서비스 v1 POLICY 문서다. 과거 `service-final-20260913/OPERATIONS.md`의
GBT+ALS shrink100 기본, 유형별 2편, 그룹당 25, 실시간 전체 후보 점수화는 이번 기준으로 승계하지 않는다.

## 발견 사항

| ID | 확인 사실과 소스 | 구현 영향 / 소유 |
| --- | --- | --- |
| A01 | 채택 ADR0017:23은 Python이 RATING/ONBOARDING마다 UserVector·Candidate Top500·기본 TopN을 모두 재계산한다. :32의 Spark Batch는 ALS 재학습 담당이다 | 이번 배치후보→실시간재정렬은 최신 사용자 결정으로 기존 채택 ADR의 후보 갱신 책임을 변경하는 것. 과거 Notion/기획과 최신 ADR이 같다고 쓰지 않는다. Pipeline·Backend 공동 |
| A02 | `pipeline/consumer/`는 README/.python-version만 존재. README:4에서 199 구현 예정이라고 명시. `pipeline/spark/streaming/`은 .gitkeep만 존재 | 문서에 적힌 Consumer/Streaming을 이미 동작하는 코드로 표현하면 안 됨. Pipeline 구현 필요 |
| A03 | develop ALS CLI는 입력 검증까지만 연결. 119 브랜치 baseline.py는 train 학습→validation 선택→train+validation test→전체 SERVING 재학습을 구현 | develop 구현과 미병합 119 구현을 분리. 새 GBT 구현은 기존 ALS를 삭제/덮어쓰지 않고 모델 adapter로 연결 |
| A04 | 운영 계약 `als-model-artifacts.md`는 ALSModel/Factor/metadata만 정의. GBT는 configs 및 연구 재현 assets에만 존재 | 공통 predictor manifest와 GBT/FM native→Python 서빙 parity 계약 필요. 연구 NPZ나 Windows 경로를 EC2 운영 artifact로 표기하지 않음 |
| A05 | `RatingEventPayload.java:15`의 score는 `Integer`. `V202609071621__allow_half_step_ratings.sql:5`는 numeric, API·streaming-contract:174는 0.5단위 | 실제 불일치. Backend DTO를 BigDecimal 등 손실 없는 숫자로 변경하고 0.5~5.0/0.5단위 검증. JSON 4.5가 truncation 없이 roundtrip 되는 계약 테스트 필수 |
| A06 | recommendation_sets의 UK(user_id,input_version,model_version), migration V202609021900:140. API046:244는 버퍼 소진 후 같은 입력에서도 새 세트를 생성한다고 명시 | 실제 불일치. 세트 generation/sequence 또는 독립 활성 세트 포인터가 필요. 입력이 안 바뀌었는데 inputVersion을 올려 우회하지 않음. Backend DB/추천 |
| A07 | recommendation_sets에는 input_version(varchar100), model_version(varchar50)뿐. Redis candidateVersion은 단조 증가 규칙만 있고 Redis 유실 시 시작값은 미정(streaming:427,480) | DB가 소유하는 입력/후보 revision과 결과 bundle 식별자 필요. 캐시 리셋이 이전 파티 캐시와 같은 번호를 재사용해서는 안 됨. Backend/Consumer 공동 |
| A08 | 기존 Redis candidates.score는 ALS 원시 점수, 같은 사용자 내 상대 순위(streaming:315). Party/OTT가 이 키를 소비(:281). model:current도 ALS factorDimension 중심 | GBT score를 같은 의미라고 조용히 덮어쓰면 계약 파손. score semantics, modelKind, model/calibration/feature/catalog/policy 버전 선언과 v1→v2 읽기 전환 필요. Party/OTT 영향 확인 |
| A09 | API045/046은 3편=2맞춤+1발견, 기본 버퍼20. discovery가 없으면 TASTE3으로 채우도록 명시(recommendation:162) | 이번 2+1은 UI 계약과 일치. 버퍼20은 3배수가 아니며 타입별 공급·소진/fallback을 새 POLICY에 명시해야 함. 수치·fallback을 감사자가 임의 고정하지 않음 |
| A10 | ADR0015:16과 migration V202609021900:11은 E5 KMeans8, 고정 중심, movies.taste_model_version 제거. 128 하위군집 필드 없음 | 공개 8맛 코드 유지. 내부 child-group ID/할당/고정 중심·전처리 해시의 별도 artifact 계약 필요. 연구 상위 장르/GKT 계층을 E5 공식구현이라고 쓰지 않음 |
| A11 | movies.average_rating/rating_count는 서비스 평가 집계(V202609021030:103). TMDB vote_average/vote_count·KOBIS audience 필드는 없음 | 서비스 평점을 TMDB 평점으로 대체하면 안 됨. T/K는 snapshot/feature artifact의 명시 필드로 관리. KOBIS 미매칭·조회실패·관객0 구분, source scope와 as-of 기록 |
| A12 | 영화 service ID는 실제 DB export의 tmdb_id 연결이며 service-only 영화도 보존(service-movie-id-map.md). 제목/연도 추측 매핑 금지 | 후보·Redis·API는 서비스 DB bigint ID. ML ID는 ALS adapter 내부. TMDB ID를 서비스 ID로 전송하지 않음. KOBIS 매핑 신뢰상태 별도 |
| A13 | API023 온보딩 카드의 ML평가수 인기 기준은 가정. API024는 LIKE/DISLIKE를 실제 ratings와 별도 저장. ratings는 created_at/updated_at을 모두 가짐 | T/K 카드 선정과 최신 입력 H의 정렬 기준·온보딩 반영은 새 POLICY/MODELS에서 고정. 실제 평가 편수와 계산용 온보딩 값 혼동 금지 |
| A14 | API045는 LINK_CLICKED 포함 감상과 관심없음을 제외. watch_records에는 LINK_CLICKED/WATCHED_UNRATED/RATED/EXPIRED 상태 | 최종 노출 제외와 발견의 실제 감상 분모는 다른 개념. LINK_CLICKED를 자동으로 실제 봤다고 계산하지 않음. 평점 삭제 후 WATCHED_UNRATED는 감상 사실 유지 |
| A15 | Outbox Publisher/Relay·migration은 develop에 있음. Rating/Onboarding domain에서 Publisher 호출은 검색상 확인 못함 | Outbox 인프라 구현과 도메인 이벤트 end-to-end 구현 완료를 구분. 새 v1 이벤트 envelope/offset/retry·역전 방지 테스트 필요 |
| A16 | 회원 탈퇴 API013:902~906은 WITHDRAWN·개인평가삭제, 추천학습용 보존 미정. Redis 추천/작업/백필 삭제 연계 미정 | Withdrawn 사용자에게 과거 큐가 결과를 재생성하지 않도록 게시 직전 계정 상태 재검사. 캐시/개인 후보 삭제와 배치/백필 제외. 익명 학습 영구 보존을 임의 채택하지 않음 |

## 현재 재사용할 수 있는 흐름과 새로 정할 흐름

| 흐름 | 현재 계약 | 서비스 v1 연결 |
| --- | --- | --- |
| DB→Kafka | 도메인과 완성 JSON을 같은 트랜잭션 저장, PENDING/PUBLISHED, 500ms 폴러, at-least-once | 유지. 별점 정밀도와 inputVersion을 포함한 도메인 연결 검증 |
| 강한 입력 | Consumer는 event payload 대신 최신 DB 상태 재조회 | 유지. 배치 후보 ID는 재사용하고 사용자 반응 특징·후보 점수 재계산. 후보 누락/콜드 사용자의 별도 준비 경로 필요 |
| 약한 입력 | Spark 집계, Spring은 다음 세트에 Top100 경량 재정렬 | 파이프라인 경계 재사용. 구체 가중치가 없으므로 임의 가산하지 않음. 새 v1 POLICY 값에 맞춘 재정렬 범위 필요 |
| 후보 공급 | 기존은 강한 입력마다 ALS Top500 | 새 배치 후보 상한·출처·갱신주기·콜드 사용자 공유 후보·소진 backfill 규칙을 POLICY에서 고정 |
| 발견 공급 | 군집 기반은 채택됐으나 판정식 미정 | 8×128와10그룹×10후보 규칙. 후보 부족 때 추가 그룹으로 늘릴지 여부까지 POLICY에서 고정 |
| 제외 | Spring이 현재 DB 기준 최종 제외 | 유지. 배치/Consumer 사전 제외도 가능하나 최신 제외 검사를 대신하지 않음 |
| 게시 | vector/candidates/topn 모두 성공 후 offset commit | 원자적 bundle 게시·입력 역전 방지 보강. 파티션 병렬 처리 시 연속 완료 구간만 commit |
| 버퍼 | PostgreSQL recommendation_items에 저장, exposed_at으로 노출 판정 | 2+1 구성 유지. 새 세트 generation, idempotent next-page와 동시성 필요 |

## 정책 문서 담당자에게 전달한 선택

아래 값은 감사 후 문서 총괄과 최신 사용자 결정에 맞춰 정리했다. 새 POLICY에서 같은 값을 공통 settings로 연결한다.

- 맞춤 배치 후보 상한500. 중간 Top100 절단 없이 현재500 전부를 최신 입력으로 재정렬.
- 세트는10라운드×(맞춤2+발견1), 유형별20/10 큐와 공통 중복 제외. 발견 재공급도 실패하면 actualType=PERSONALIZED/reason=DISCOVERY_EXHAUSTED인 맞춤 대체.
- K10/H최신 원본10 유지. 새 v1 저이력은 초기 콘텐츠/대중 경로.
- TMDB 유효 평균+투표≥21 또는 검증된 동일 누적 KOBIS 관객≥10,000. 통과한 출처만 후보 순위에 기여. 이 문턱을 검색·평가·H선정에는 적용하지 않음.
- 발견은 공급이0인 그룹만 건너뛰고 공급1~9도그룹1개로 센다. 11번째그룹/11번째영화로100을억지충족하지 않음.
- 개인 pool의 builtFromInputVersion과 재정렬 rankingInputVersion 분리. 같은 bundle의 기존 pool membership 임시 재사용은 허용하되 과거점수 재사용 금지. 신규는 배치 shared T/K500을 provisional pool로 사용하고 targeted Spark 후보 준비를 비동기로 요청.

## 보존 자료 대조

- `docs/recommendation/service-final-20260913/OPERATIONS.md`: 이전 로컬 DRAFT. GBT+ALS shrink100 기본, 분리된2편노출, 그룹25/B100은 최신 v1 정책과 다르다. 원본은 보존한다.
- 같은 폴더의 `REVIEW-RECEIPT.json`: 이전 문서/기술 검토의 증거다. 새10×10·GBT기본 정책의 검증 완료 증거로 사용하지 않는다.
- `docs/recommendation/service-popularity-v2-20260913/DESIGN.md`: T/K 특징·반응·후보 보완 재설계 참고. 기존 25개 후보·콘텐츠 보충 조건을 최신 무명영화 제외/10×10에 그대로 적용하지 않는다.
- 저장된 Notion 확인 기록은 요구사항 정의서가 폐기 상태, 마지막 편집2026-09-02였다는 내용이다. 현행 승인 기준이나 최신 GitLab과 같다는 증거로 쓰지 않는다.

## 검증 범위

읽은 원격 스냅샷에는 docs README, 채택 ADR0002/0015/0017/0018, 추천/스트리밍/온보딩/회원/취향 API,
논리 ERD, 추천·평가·콘텐츠 migration, Kafka DTO/Publisher/Relay, pipeline README/Consumer README,
settings.py, ALS train/save/load/split 및119 orchestration, configs/artifacts/ID매핑 계약이 포함된다.

이번 작업은 읽기와 로컬 감사 메모 작성이다. 실제 EC2/HDFS/Kafka/Redis, MR 병합 상태, 미공개 다른 작업 브랜치,
추천 서버의 동작은 검증하지 않았다. 신규 정책의 통계적 성능이나 실제 사용자 만족도를 입증하지 않는다.
