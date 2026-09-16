# Fold-in Consumer

Kafka의 평가·온보딩 이벤트를 받아 최신 사용자 입력으로 ALS Fold-in을 수행하고 부가 정보가 포함된 Candidate 최대 500개를 갱신한다. Python 3.12의 독립 프로젝트이며 Spark 코드를 import하지 않는다.
모델·매핑·영화 메타데이터는 모두 같은 `input_snapshot`을 가리켜야 하며, 하나라도 맞지 않으면 시작을 거부한다.

원별점 Fold-in 계산 참고와 검증 fixture는
[`spark/batch/evaluation/reference.py`](../spark/batch/evaluation/reference.py),
[`spark/tests/batch/test_research_reference.py`](../spark/tests/batch/test_research_reference.py)에 있다.
현재 Consumer가 Spark 프로젝트 코드를 import하는 구조를 도입한 것은 아니다.
서비스 구현 시 계산을 옮기고 같은 fixture로 대조한다.

모델을 읽을 때는 [공식 ALS 산출물 계약](../contracts/als-model-artifacts.md)을 따른다.
연구용 item-only NPZ와 운영용 Parquet 계약을 혼용하지 않는다.

추천 내부 8×128 그룹은 [그룹 서빙 산출물 계약](../contracts/group-serving-artifacts.md)을 따른다.
`feelm_consumer.group_artifacts.load_group_bundle`은 로컬·HDFS immutable URI를 같은 방식으로 읽고,
ALS/content와 `mapping_revision`이 다르거나 manifest·Parquet·배열 검증이 실패하면 시작을 거부한다.
이 loader를 추천 정책에 연결하는 작업은 S15P21E106-415가 담당한다. 공개 팝콘 맛 ID나 DB 컬럼으로
group ID를 쓰지 않는다.

위 exact revision 비교는 함께 공개된 불변 ALS/content bundle 사이의 세대 검증이다. 현재 PostgreSQL
live export는 `compare_live_mapping`으로 ID쌍을 비교하며 전체 revision 문자열 일치를 요구하지 않는다.
순수 추가·삭제는 허용하고, 새 영화는 다음 bundle 전까지 assignment가 없어 발견 추천에서 제외한다.
기존 `service_movie_id`가 다른 `tmdb_id`로 재결합됐거나 live ID가 중복이면 활성화를 거부한다.

연구 pickle을 안전 번들로 바꾸는 1회성 writer는 실행 중인 Consumer 환경과 분리한다. 아래 요구사항으로
별도 Python 3.12.5 환경을 만들고, 저장소의 Consumer 소스 경로를 `PYTHONPATH`에 추가해 실행한다. writer는
recipe에 고정된 원본 바이트·SHA와 NumPy/SciPy/scikit-learn 버전을 먼저 검사한다. 실제 명령의 source,
mapping, output URI에는 승인된 immutable 버전만 전달한다.

```bash
python -m pip install -r pipeline/consumer/group-export-requirements.txt
PYTHONPATH=pipeline/consumer/src \
  python pipeline/consumer/tools/export_group_bundle.py --help
```

movie metadata snapshot은 Spark 프로젝트에서 다음 명령으로 mapping snapshot 뒤에 immutable
version URI로 공개한다. Consumer에는 해당 URI와 그 revision으로 만든 SERVING model만 함께
전달한다. `latest` 별칭이나 서로 다른 revision 조합은 Consumer가 거부한다.

```bash
uv run python -m jobs.batch.movie_metadata --help
```

## 로컬 검증

```bash
uv run pytest
uv run ruff check .
```

CI에서는 develop 대상 MR에 `consumer-mr-test`가 자동 실행되고 `pipeline/consumer`만 격리해
같은 Ruff·pytest 검사를 수행한다. 보호된 `main` push는 Dockerfile의 `test` target을 먼저
통과한 뒤에만 커밋 SHA 태그 이미지를 게시한다. 이 과정은 배포하지 않는다.

실행 명령은 다음과 같다. 실제 Kafka·PostgreSQL·Redis·HDFS 연결 검증은 별도 통합 환경에서 수행한다.

```bash
uv run feelm-foldin-consumer
```

## 컨테이너 빌드와 실행

저장소 루트에서 Consumer 전용 build context를 사용한다. Python 런타임과 `uv` 버전,
`uv.lock`을 고정하며 개발 의존성, 테스트, Secret과 모델·데이터 산출물은 이미지에 넣지
않는다. 운영 산출물의 `hdfs://` URI를 PyArrow로 읽을 수 있도록 분산 런타임과 같은 Hadoop
3.4.3 client jar·`libhdfs`와 OpenJDK 17 JRE를 포함한다. 이미지는 현재 서버와 같은
`linux/amd64`를 대상으로 한다.

```bash
docker build --tag feelm-foldin-consumer:local pipeline/consumer
```

로컬 실행 값은 Git에서 제외되는 `pipeline/consumer/.env`에 두고
`.env.example`의 변수 이름만 복사한다. 실제 DSN, URL과 내부 주소는 이미지 build
argument나 Dockerfile에 넣지 않는다. 서버에서는 `CONSUMER_MODEL_URI`,
`CONSUMER_MAPPING_URI`, `CONSUMER_METADATA_URI`에 NameNode authority가 포함된 immutable
`hdfs://` URI를 주입한다. 로컬 파일 산출물을 사용한다면 버전 디렉터리를 읽기 전용으로
mount하고 URI도 해당 mount 경로의 명시적인 버전을 가리키게 한다.

```bash
docker run --rm --name feelm-foldin-consumer \
  --env-file pipeline/consumer/.env \
  --mount type=bind,src=/path/to/versioned-artifacts,dst=/artifacts,readonly \
  feelm-foldin-consumer:local
```

Consumer에는 HTTP health endpoint가 없다. 컨테이너의 실행 상태와 비민감 운영 로그를
상태 기준으로 사용한다.

```bash
docker inspect --format '{{.State.Status}} exit={{.State.ExitCode}}' feelm-foldin-consumer
docker logs --tail 100 feelm-foldin-consumer
```

- 정상 상태는 프로세스가 `running`이고 할당·처리 오류가 반복되지 않는 상태다.
- Kafka가 일시적으로 연결되지 않으면 client가 재접속을 계속하며 broker 오류는 로그로
  확인한다. 설정·산출물 검증 실패나 복구 불가능한 처리 실패로 프로세스가 종료되면 배포
  supervisor가 재시작해야 한다.
- 종료 시 console script가 PID 1로 SIGTERM을 직접 받아 poll loop를 끝내고 Kafka
  Consumer를 닫는다. `docker stop --time 30 feelm-foldin-consumer`로 정상 종료를 확인한다.
- 로그에는 event payload, DSN, Redis URL 또는 Secret을 남기지 않는다.

Registry 태그, Compose 서비스와 restart policy는 배포 업무에서 설정한다. 이 Dockerfile은
이미지 빌드와 단일 Consumer 프로세스 실행 경계만 정의한다.

필수 설정은 `CONSUMER_KAFKA_BOOTSTRAP_SERVERS`, `CONSUMER_KAFKA_GROUP_ID`,
`CONSUMER_POSTGRES_DSN`, `CONSUMER_REDIS_URL`, `CONSUMER_MODEL_URI`,
`CONSUMER_MAPPING_URI`, `CONSUMER_METADATA_URI`다. DSN·URL의 실제 값은 Secret으로 관리한다.

## ALS + 콘텐츠 하이브리드 점수 (S15P21E106-417)

`CONSUMER_CONTENT_URI`에 [content-artifacts-v2](../contracts/content-artifacts.md#part-b-content-artifacts-v2-서빙)
버전 경로를 주면 ALS Factor가 없는 영화도 후보에 넣는다. **설정하지 않으면 기존 ALS 전용 동작
그대로다.** 콘텐츠 산출물은 ALS 모델과 `mapping_revision`·`movie_metadata_revision`·`input_snapshot`이
정확히 같아야 하며, 하나라도 다르거나 산출물이 불완전하면 시작을 거부한다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `CONSUMER_CONTENT_URI` | 없음(선택) | 공개 완료된 content-artifacts-v2 버전 경로. `latest` 같은 별칭과 `.<version>.tmp-*` staging 경로는 거부한다 |
| `CONSUMER_HYBRID_ALPHA` | `0.5` | 고정 ALS 가중치. 0~1 밖이면 시작을 거부한다 |
| `CONSUMER_MIN_INPUT_COUNT` | `10` | 후보를 계산하기 시작하는 최소 입력 수(평점 + 온보딩 선호). 미만이면 계산·게시를 건너뛴다. `0`이면 제한 없음 |

**`CONSUMER_HYBRID_ALPHA`는 두 성분이 모두 있는 영화에만 적용된다.** 한쪽만 지원하는 영화는
alpha와 무관하게 자기 성분의 순위를 그대로 받으므로, **`alpha=1`로 둔다고 ALS 전용이 되지 않는다.**
콘텐츠를 실제로 끄려면 `CONSUMER_CONTENT_URI`를 비운다.

alpha에 대한 후보 구성은 단조롭지 않다. 실측한 상위 500편의 ALS Factor 보유 편수는
`alpha=0.0` 474편 → **`alpha=0.5`(기본값) 47편** → `alpha=1.0` 370편으로 **기본값에서 가장 적다.**
두 성분을 모두 가진 영화만 평균으로 점수가 깎이고 한쪽만 가진 영화는 그대로이기 때문이며,
양 끝값에서는 한쪽 성분이 사실상 무시되어 깎임이 사라진다. 기본값을 바꿀 때는 이 비단조성을 전제로
실측값을 다시 확인한다.

점수는 S15P21E106-315 규칙을 따른다. 사용자별로 각 컴포넌트가 지원하는 영화 집합에 대해 동률을
인식하는 백분위를 구하고, 둘 다 있으면 `alpha × ALS + (1-alpha) × 콘텐츠`로 섞는다. 한쪽만 있으면
그 백분위를 그대로 쓰고, 둘 다 없으면 후보가 아니다. 정렬은 점수 내림차순·서비스 영화 ID 오름차순이다.

**게시하는 `score`는 이 내부 점수가 아니라 후보 500편 안에서의 동률 인식 백분위다.** 내부 점수의
척도는 점수원마다 다르다 — 원시 ALS는 사용자마다 범위가 다른 예측 별점이고(실측: 같은 1위 후보가
어떤 사용자는 1.53, 어떤 사용자는 7.66), 이후 GBT·FM을 붙이면 또 달라진다. 순위로 바꿔 내보내면
**점수원을 교체해도 Redis 계약과 소비자 코드가 그대로**이고 사용자 간 값 비교도 성립한다. 순서는
변환 전후가 같다.

**이 변환은 콘텐츠 산출물 설정 여부와 무관하게 모든 경로에 적용한다.** ALS 전용으로 돌든 하이브리드로
돌든 저장되는 `score`의 의미가 같아야 하기 때문이다. 콘텐츠 설정은 후보 모집단과 순서만 바꾼다.

`score`의 최종 의미와 소비 규칙(파티 집계식, OTT 임계치)은 미합의 상태이며 위 정의는 잠정 기준이다.

한쪽만 지원하는 영화가 평균 없이 자기 순위를 그대로 받으므로, 콘텐츠 전용 영화가 상위 후보의
다수를 차지한다(실측 사용자 표본에서 상위 500 중 460~496편). 315 규칙 그대로이며 최종 재정렬은
Spring이 한다.

콘텐츠 프로필의 가중치는 평점이 prior 분포의 중앙값보다 위일 때만 양수다. prior가 MovieLens
전체 평점이라 그 경계가 3.5와 4.0 사이에 있다. 프로필은 부호가 섞인 조합이므로, 이력이 낮은 쪽으로
기울면 **모든 영화의 콘텐츠 점수가 0 이하**가 될 수 있다(전부 3.5 이하인 경우뿐 아니라, 좋게 본
영화의 토큰이 싫어한 영화들에 모두 포함된 경우에도 생긴다). 그 상태의 랭킹은 가장 덜 닮은 영화를
1위로 올리고 겹치는 토큰이 없는 수만 편이 0.0으로 공동 1위가 되어 순위 정보가 없다.

**그래도 후보는 게시한다.** 목록이 없는 것보다는 낫다는 판단이다. 다만 그 상태의 목록은 순위가
아니다 — 프로필과 겹치는 토큰이 없는 영화가 전부 정확히 0.0이라 공동 1위가 되고(실측: 토큰 4개
영화를 높게·그 토큰을 포함한 4편을 낮게 평가하면 61,591편), 그 안의 순서는 서비스 영화 ID 순이다.
이 상태에 들어가면 `content_scores_non_positive`에 최상위 동점 편수를 남긴다. 대부분의 사용자는
최소 입력 수 기준에서 걸러진다.

입력이 `CONSUMER_MIN_INPUT_COUNT` 미만인 사용자는 계산 자체를 하지 않는다. 입력이 몇 건뿐인
사용자의 fold-in은 노이즈이고(실측: 평점 40건에서도 평가한 영화 예측 오차 MAE 1.42), 서비스도 같은
기준 아래에서는 추천을 노출하지 않기 때문이다. **이 기준값은 서비스 노출 기준과 같아야 하므로
백엔드와 합의한 값으로 운영한다.**

어떤 성분도 점수를 낼 수 없으면(신규 사용자, 두 산출물 모두에 없는 이력) **아무것도 게시하지 않고**
`no_publishable_result_skipped`를 남긴 뒤 offset을 커밋한다. 임의의 목록을 게시하면 소비자가 실제
추천과 구별할 수 없고, 반대로 이를 실패로 처리하면 partition이 멈추고 재시작 뒤 같은 이벤트가
반복된다. 재시도해도 결과가 달라지지 않는 상태이므로 **정상 skip**으로 다룬다.

콘텐츠 벡터는 평점이 없어도 만들어지므로, 평점이 하나도 없는 신작도 후보가 된다. 반대로 사용자가
ALS Factor 없는 영화만 평가했다면 ALS 벡터는 만들어지지 않고 콘텐츠 점수로만 후보를 만든다. 이때
`vector` 키의 `vector`는 빈 배열, `factorBackedInputCount`는 0이다.

실측(2026-09-15, 서비스 영화 237,817편·vocabulary 571,726): 산출물 로딩 ALS 4.5초 + 콘텐츠 5.1초,
로딩 후 최대 RSS 928 MiB, 사용자 1명 점수화 ALS 전용 0.25~0.33초 / 하이브리드 1.21~1.40초.

`CONSUMER_RETRY_ATTEMPTS`(기본 3), `CONSUMER_RETRY_BACKOFF_SECONDS`(기본 0.25),
`CONSUMER_KAFKA_POLL_TIMEOUT_SECONDS`(기본 1.0), `CONSUMER_MAX_PAUSED_POLLS`(기본 30)는 모두 유한 범위로 검증한다. 처리·게시·commit
실패와 malformed event는 해당 Kafka partition을 pause하고 offset을 commit하지 않는다. 다른
partition은 계속 처리하며, pause가 설정 횟수의 poll 동안 유지되면 Consumer는 종료돼 supervisor가
재시작한다. 운영자는 원인을 수정한 뒤 해당 offset을 재처리한다.
partition을 assign할 때 Consumer group·topic·partition별 Redis fencing token을 새로 설치한다. 새 token을
확보한 뒤 이전 소유자는 Redis 게시와 corrupt/partial 3키 복구 삭제를 할 수 없으므로, rebalance 중 오래된
PostgreSQL snapshot이 새 결과를 덮어쓰지 않는다. Redis fence는 **Redis 게시만** 책임진다. Kafka offset
commit은 Kafka group coordinator의 generation 검증이 책임지며, Consumer는 synchronous commit 실패 시 offset을
진전시키지 않고 재처리한다. 실제 broker rebalance·generation 거부 E2E는 아직 검증하지 않았다.

assign 중 하나라도 Redis fence claim이 실패하면 Consumer는 어떤 partition도 local ownership으로 등록하거나
assign하지 않고 종료한다. 이미 성공한 claim은 새 token으로 남지만 해당 프로세스는 게시·commit하지 않으며,
supervisor 재시작 뒤 새 group assign에서 모두 새로 claim한다.
로그에는 event payload, DSN, Redis URL을 기록하지 않는다.

Redis 출력은 `candidate-version`, `vector`, `candidates` 세 String 키이며 TTL은 없다.
`feelm:consumer-fence:{groupId}:{topic}:{partition}`은 Consumer 내부의 String fencing key다. assign마다 CSPRNG
opaque token으로 교체하며 후보 결과를 읽는 Spring은 이 키에 의존하지 않는다. key가 유실되면 모든 기존 token은
비교에서 거부되고, 다음 assign은 새 token을 설치한다. Redis의 과거 snapshot 복원처럼 fence key가 이전 값으로
되돌아가는 장애는 외부의 durable coordinator 없이는 막지 못하며 이 Consumer의 보장 범위 밖이다.
`candidates.taste`와 향후 `candidates.discovery`의 각 항목은 `movieId`, `score`, `reason`을 포함한다.
`reason`은 Consumer가 만든 구조화된 근거(`code`, `sourceMovieIds`, `matchedGenreCodes`)이며 Spring이 영화·장르명을
조회해 사용자 문구로 변환한다. `SHARED_GENRE`는 고평점 source 영화와 후보의 공통 장르를, `TOP_RATED_SOURCE`는
공통 장르가 없을 때 가장 높은 평가 source 영화를 뜻한다.
두 payload에는 동일한 `candidateVersion`(JSON 형식 버전)과 `generationId`(한 번의 생성 결과 식별자)가
포함된다. `candidateVersion=3` 또는 더 이른 payload에는 `reason`이 없으므로 Spring reader 전환 전에 모든 활성
사용자를 재게시해 `candidateVersion=4`로 갱신해야 한다. Consumer는 추천 문구가 아니라 구조화된 `reason`만 생성하며,
Spring이 최종 추천의 검증 가능한 근거로 문구를 구성한다.
Spring이 후보 전체의 최종 제외·재정렬을 담당한다. 영화별 OTT 제공 정보와 사용자 OTT 클릭 정보는 PostgreSQL에서 조회한다. 별도 `topn`은 생성하거나 복구 조건에 포함하지 않는다.
기존 `topn` 키 자동 삭제는 하지 않는다. 배포 전 Spring reader 전환이 필요하다.
