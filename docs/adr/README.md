# 기술 결정 기록 (ADR)

**아키텍처와 핵심 기술 선택**처럼 한 번 정하면 프로젝트 전체에 영향을 주는 결정만 **결정 1건당 문서 1개**로 기록합니다. 결정의 배경·대안·감수한 것이 남아 있어야 나중에 "왜 이렇게 했지?"에 답할 수 있고, 바꿀 때 무엇을 다시 검토해야 하는지 알 수 있습니다.

## 무엇을 ADR로 남기나

- 아키텍처·서비스 경계 결정 — 추천 서빙 위치, 배포 단위, 저장소 구성
- 핵심 기술·프레임워크·저장소 선택 — Spring Boot, PostgreSQL, 검색 엔진 등
- 전 테이블·전 API에 걸치는 기술 규약 — 식별자 타입, 인증 방식, 알림 전달 방식

**ADR 대상이 아닌 것**은 각 기준 문서에 기록합니다.

- 데이터 모델 세부(테이블 분리, 컬럼 형식, 집계 방식) → [논리 ERD 5장](../erd.md#5-물리-스키마-전-결정-사항)
- API 규약 세부(오류 코드 명명, 페이지네이션 등) → [API 명세 공통 규약](../api/README.md#공통-규약)
- 제품 정책(감상 기록 없는 평가, 파티 모델 등) → [요구사항 정의서](../requirements.md) 인수 조건
- 값·임계치(비밀번호 규칙, 캐시 TTL 등) → 설정값

## 상태

| 상태 | 의미 |
|---|---|
| 제안 | 회의 안건. 본문의 결정은 `(가정)` 초안값 |
| 채택 | 팀이 결정. 구현·문서의 기준 |
| 대체 | 새 ADR로 바뀜. `관련`에 대체한 ADR 번호를 적고 본문은 수정하지 않음 |
| 폐기 | 전제가 사라져 무효 |

## 규칙

- 파일명은 `NNNN-kebab-case.md`, 번호는 순차 증가하며 재사용하지 않습니다. 새 ADR은 [`0000-template.md`](./0000-template.md)를 복사해 작성합니다.
- **채택된 ADR의 결정 내용은 수정하지 않습니다.** 바꾸려면 새 ADR을 쓰고 이전 ADR을 `대체`로 표시합니다. 오타·링크 수정은 예외입니다.
- 제안 → 채택으로 바뀌면 상태·결정일·결정자를 채우고 본문의 `(가정)`을 제거합니다. 같은 MR에서 관련 API 명세·ERD의 `(가정)` 표기도 정리합니다.
- ADR 추가·상태 변경도 Jira 업무와 MR을 거칩니다. 회의에서 결정만 하고 문서 반영은 담당자가 MR로 올립니다.
- `관련` 항목에는 요구사항(FR/NFR), API ID, ERD 결정 번호(D-n), 회의 안건 번호(A-n)를 적어 추적할 수 있게 합니다.

## 목록

### 확정된 기술 스택 (소급 기록)

| ADR | 결정 | 상태 |
|---|---|---|
| [ADR-0001](./0001-hdfs-spark-distributed-processing.md) | 분산 저장·처리에 Hadoop HDFS와 Spark(PySpark)를 사용한다 | 채택 |
| [ADR-0002](./0002-als-content-hybrid-recommendation.md) | 추천 모델은 ALS + 콘텐츠 기반 Hybrid로 구성한다 | 채택 |
| [ADR-0003](./0003-spring-boot-backend.md) | 서비스 백엔드는 Spring Boot로 구현한다 | 채택 |
| [ADR-0004](./0004-postgresql-redis-serving-store.md) | 서비스 데이터는 PostgreSQL, 캐시·후보는 Redis에 저장한다 | 채택 |
| [ADR-0005](./0005-vue-frontend.md) | 웹 클라이언트는 Vue로 구현한다 | 채택 |
| [ADR-0006](./0006-aws-ec2-two-nodes.md) | 인프라는 AWS EC2 2대로 Spark 클러스터와 서비스를 함께 운영한다 | 채택 |
| [ADR-0007](./0007-tmdb-movielens-data-sources.md) | 영화 메타데이터는 TMDb, 학습 원천은 MovieLens 32M을 사용하고 사용자는 연결하지 않는다 | 채택 |

### 개발 시작 전 확정 사항 중 아키텍처·기술 선택

A 회의 안건 12건 중 아키텍처·기술 선택에 해당하는 5건입니다. 나머지(A-2~A-7, A-9)는 데이터 모델·API 규약·제품 정책이라 ERD·API 명세·요구사항 문서에 기록합니다. 5건 모두 채택되었습니다.

| ADR | 안건 | 결정 | 상태 |
|---|---|---|---|
| [ADR-0008](./0008-identifier-type.md) | A-1 | 내부 식별자는 bigint, 공개 URL에는 별도 public_id를 사용한다 | 채택 (2026-08-30) |
| [ADR-0009](./0009-jwt-auth-httponly-refresh-redis.md) | A-8 | 인증은 JWT Bearer로 하고 Refresh Token은 httpOnly 쿠키로 전달해 Redis에 저장한다 | 채택 (2026-08-31) |
| [ADR-0010](./0010-search-postgresql-fulltext.md) | A-10 | 영화 검색은 PostgreSQL full-text와 pg_trgm으로 구현한다 | 채택 (2026-08-31) |
| [ADR-0011](./0011-notification-polling.md) | A-11 | 알림은 1차 MVP에서 폴링으로 전달하고 알림 발송 기능은 2차로 미룬다 | 채택 (2026-08-31) |
| [ADR-0012](./0012-realtime-recommendation-serving-location.md) | A-12 | 실시간 추천 서빙 계산(Fold-in·재정렬·파티 집계·XAI)은 FastAPI 서비스에서 수행한다 | 채택 (2026-08-31) |

## 관련 문서

- [요구사항 정의서](../requirements.md) · [API 명세](../api/README.md) · [논리 ERD](../erd.md)
- ERD 5장 D-1~D-12와 API 명세 공통 규약 미정 사항 중 기술 선택에 해당하는 항목은 위 ADR로, 나머지는 각 문서의 결정 표로 수렴합니다.
