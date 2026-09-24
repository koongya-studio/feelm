# Fold-in Consumer

742 계약에 연결된 개인 GBT 실행·입력 보존·검증은 [627 안내](GBT-627.md)를 참고한다.

v26 GBT 전용 번들·독립 scorer는 [GBT.md](./GBT.md)를 따른다. 개인 실행은 명시적인
`--gbt-bundle`로 선택한다. 표준 이미지에는 GBT CPU 의존성도 포함된다.
운영 기본 명령의 개인·파티 GBT 선택, 필수 환경 변수와 전환 인계는
[644 실행 안내](GBT-644.md)를 따른다. 기본 `CONSUMER_RUNTIME=legacy`에서는 아래 기존 ALS 경로를 사용한다.

Kafka의 평가·온보딩·관심없음 이벤트를 받아 최신 사용자 입력으로 ALS Fold-in을 수행하고 TASTE 최대 500편과
DISCOVERY 최대 100편을 갱신한다. Python 3.12의 독립 프로젝트이며 Spark 코드를 import하지 않는다.
모델·매핑·영화 메타데이터는 모두 같은 `input_snapshot`을 가리켜야 하며, 하나라도 맞지 않으면 시작을 거부한다.

각 이벤트 처리에서는 이벤트 payload의 과거 별점·선택을 계산값으로 사용하지 않는다. PostgreSQL의
현재 평가·관심없음을 repeatable-read snapshot으로 다시 읽고, DISCOVERY 산출물을 활성화한 경우
확정 감상도 같은 snapshot에서 읽어 결정론적으로 재계산한다. 실제 평가가 없는 관심없음은 계산 시점에만
기본 `1.0`점의 DISMISS 기반 가상 평점 입력으로 합성하고, 같은 영화의 실제 평가는 `RATING`으로
우선한다. 가상 평점 입력을
`ratings`에 저장하지 않으며 관심없음 영화 자체를 제외하는 Spring 동작은 유지한다. 같은 PostgreSQL 상태와
같은 산출물 revision에 대한 이벤트 재전달은 같은 후보를 만든다.

온보딩 LIKE·DISLIKE는 Backend가 각각 `4.0`·`2.0` 평가 행으로 저장한다. Consumer는
`onboarding_preferences`를 계산 입력으로 조회하지 않으며, ONBOARDING 이벤트를 최신 평가를 다시 읽는
재계산 신호로만 사용한다. S15P21E106-648 소급 migration 적용과 **설명되지 않은 미보정 행 0건**을
확인하기 전에는 이 입력 전환 버전을 운영에 배포하지 않는다. migration 뒤 사용자가 평가를 삭제하면
온보딩 행만 남는 것이 정상이며, 이 행을 다시 평가로 만들지 않는다.

### S15P21E106-649 운영 전환 게이트

649 배포 담당자는 다음 순서를 수동 배포 게이트로 실행한다. 출력에 실제 사용자 ID·DB 접속 정보·Redis
payload를 남기지 않고, 건수와 성공 여부만 GitLab 배포 job artifact와 MR 배포 체크리스트에 보관한다.
S15P21E106-648 migration 확인과 S15P21E106-735 전체 사용자 Candidate 재게시는 이 운영
배포·전환을 완료하기 위한 게이트이며, S15P21E106-649 병합 선행 조건이 아니다.

1. `backend/scripts/ONBOARDING-BACKFILL-OPERATIONS.md`의 사후 점검 명령을 승인된 운영 DB에서 실행해
   Flyway `202609192100`의 `success=true`를 확인한다. 1번 보정 대상에 남은 행은 migration 이후 정상
   평가 삭제인지 삭제 이력과 현재 `WATCHED_UNRATED`·rating 없음·frame 없음 상태로 대조한다. 확인된
   삭제 행은 재생성하지 않고 근거만 남기며, 설명되지 않은 미보정 행이 0건이어야 한다. 하나라도 남으면
   배포를 중단한다.
2. 새 Consumer 이미지를 배포하되 아직 전환 완료로 표시하지 않는다. Candidate 키에는 TTL이 없어 기존
   결과가 자동 교체되지 않는다.
3. S15P21E106-735에서 `docs/api/streaming-contract.md` §5.4의 승인된 전체 사용자 추천 재구축
   절차로 모든 활성 사용자를
   rating-only Consumer에 한 번씩 처리한다. 대상·성공·실패·재시도 건수와 배포 전후 게시 순번을 기록하고,
   모든 대상에 새 `generationId`가 게시될 때까지 실패 건만 재시도한다.
4. 실패 0건, 대상 수와 성공 수 일치, 새 Candidate의 `directionSource=ONBOARDING` 0건을 확인해야 전환을
   완료한다. 저장소에는 아직 전체 재구축 실행기가 없으므로 승인된 실행 수단과 실제 증거가 준비되지
   않으면 이 게이트는 열린 상태이며 운영 배포를 완료할 수 없다.
5. 중간 실패 시 새 Consumer로의 트래픽 전환을 중단한다. 이미 전환했다면 마지막 정상 이미지로 복구한 뒤
   같은 전체 재구축을 실행해 모든 활성 사용자를 이전 정책으로 재게시하고 대상·성공·실패 건수를 다시
   대조한다. 부분 재게시 상태에서는 신·구 후보가 섞이므로 성공으로 간주하지 않는다.

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
이 loader를 추천 정책에 연결하는 작업은 S15P21E106-415가 담당한다. 상위 `taste_id` 0~7은
S15P21E106-466에서 공개 팝콘 맛 `FLAVOR_A`~`FLAVOR_H`로 사용한다. 하위 `child_id`와 전체
`group_id`는 계속 추천 내부 값이며 DB 대표 맛이나 공개 API에 저장하지 않는다.

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

439 assignment를 서비스 DB에 반영하는 도구는 기본이 rollback-only dry-run이다. 실제 apply는 운영자가
dry-run 보고서의 ID 재결합 0건과 예상 건수를 확인한 뒤에만 실행한다. URL은 예시처럼 환경변수로 전달하고
명령행·보고서·저장소에 Secret을 남기지 않는다. 기본 DSN 환경변수는 Consumer와 같은
`CONSUMER_POSTGRES_DSN`이다. 검증·반영 구간에는 `movies`와 `popcorn_flavors`의 DML을 잠시
직렬화하므로 영화 동기화와 겹치지 않는 승인된 실행 창을 사용한다.

```bash
PYTHONPATH=pipeline/consumer/src \
  python pipeline/consumer/tools/apply_popcorn_flavors.py \
  --bundle-uri file:///path/to/groups/g913-k128-reference \
  --expected-mapping-revision sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef

# 승인된 실행 창에서만 같은 명령에 --apply --report /path/to/report.json을 추가한다.
```

실제 PostgreSQL 실패·동시성 통합 테스트는 격리된 테스트 DB의 DSN을
`FLAVOR_ASSIGNMENT_TEST_DSN`에 설정한 경우에만 실행된다. 서비스 DB DSN을 테스트에 사용하지 않는다.

movie metadata snapshot은 Spark 프로젝트에서 다음 명령으로 mapping snapshot 뒤에 immutable
version URI로 공개한다. Consumer에는 해당 URI와 그 revision으로 만든 SERVING model만 함께
전달한다. `latest` 별칭이나 서로 다른 revision 조합은 Consumer가 거부한다.

```bash
uv run python -m jobs.batch.movie_metadata --help
```

## 로컬 검증

Candidate를 Redis에 게시한 뒤에는 같은 Kafka 브로커의 `feelm.candidate-updated.v1`에
Kafka 키 `userId`(UTF-8 십진 문자열), 값 `{"userId": 1024}` 형식의 완료 알림을 보낸다. 브로커 전달 확인 전에는
원본 평가·온보딩·관심없음 Offset을 커밋하지 않는다. 재처리 시 알림이 중복될 수 있으므로 수신자는
현재 Redis `generationId`를 다시 확인해야 한다. 이 알림을 파티별 전체 입력으로 바꾸는
Spring 소비자는 별도 구현이다.

Kafka의 Topic 자동 생성이 꺼져 있으므로 Consumer 배포 전에 기존 브로커에서 토픽을 만든다.
로컬은 `feelm-kafka-local`, 분산 환경은 `feelm-kafka` 컨테이너에서 실행한다.

```bash
docker exec feelm-kafka-local /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic feelm.candidate-updated.v1 --partitions 3 --replication-factor 1

# 분산 Kafka 컨테이너에서는 같은 토픽을 한 번 생성한다.
docker exec feelm-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic feelm.candidate-updated.v1 --partitions 3 --replication-factor 1
```

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

## 파티 추천 전용 Consumer (S15P21E106-566)

같은 이미지에서 `feelm_consumer.party_main` 모듈을 실행하지만 개인 Fold-in과는 별도 프로세스,
Kafka Group, 환경변수 prefix로 실행한다. `feelm.party-refresh.v1`의 8바이트 Long Kafka key가
payload의 `partyId`와 같은지 검증하고 기존 `party-mean-fit-v2` 정책으로 최대 500편을 계산한다.
Spark 코드를 import하지 않으며 기존 Spark 구현은 parity와 롤백 기준선으로만 남긴다.

S15P21E106-829는 기존 PostgreSQL CHECK 제약에 맞춰 GBT 요청 최상위를
`partyId`·`inputId`·`members`로 제한한다. 멤버의 엄격한
`userId`·`generationId`·`inputSchemaVersion`·`inputHash`·`candidateMovieIds` 구조로 구 vector 입력과
구분한다. model/revision은 요청한 불변 입력 스냅샷에서 확인하며 모든 멤버 및 현재 로딩 모델과 같아야
한다. 최신 snapshot이나 `model:current`만으로 과거 요청 메타데이터를 추정하지 않는다.

`--gbt-bundle`을 명시하면 627의 불변 입력으로 같은 GBT를 사용해 구성원별 재점수화를 수행한다.
실행과 검증은 [747 안내](GBT-747.md)를 따른다. 새 Consumer를 먼저 배포해 구 vector, 742의
최상위 6필드 GBT, 829의 최상위 3필드 GBT를 모두 소진·격리할 수 있게 한다. 이후 627 snapshot
게시를 확인하고 829 Backend를 배포한 뒤 승인된 `model:current.modelKind=GBT` 전환을 수행한다.
새 3필드 GBT를 이해하지 못하는 Consumer로 rollback하지 않는다.

늦은 vector를 결과 게시 없이 소진하고 최신 GBT refresh를 재요청하는 경로와 snapshot/schema 오류의
지속 격리·재요청 ACK·runner 연결은 747에 구현되어 있다. 829는 3필드 요청의 모델 검증과 성공 후
snapshot 만료 재처리를 연결한다. 오류는 기존 성공 결과를 보존하며 483의 전원 K0·후보 소진 공급은
포함하지 않는다. 이 Consumer와 644 런타임 배포가 Backend의 새 형식 발행보다 먼저 완료되어야 한다.


기존 ALS 실행 경로는 시작할 때 개인 Consumer와 같은 `als-artifacts-v2` SERVING model, mapping,
movie metadata 고정 URI 세 개를 `load_bundle`로 검증한다. Redis 게시 Lua는 partition assign 때
발급한 전용 fence와 `feelm:model:current.modelVersion`을 함께 확인하고
`feelm:party:{partyId}:candidates` JSON 하나를 교체한다. 게시 성공 뒤
`feelm.party-candidate-updated.v1`에 같은 `partyId`, `inputId`, 50자 이하 `modelVersion`,
`generatedAt`과 원본 partition·offset을 발행한다. `modelVersion`은 산출물의 소문자 영숫자·점·밑줄·하이픈
형식을 검증하고, `generatedAt`은 원본 Kafka record timestamp를 UTC로 정규화해 재처리에도 고정한다.
broker ACK까지 확인된 뒤에만 입력 Kafka record의
Offset을 동기 커밋한다. poison record나 영구 실패 partition은 pause하고 다른 partition은 계속 처리한다.

새 `feelm-party-consumer-v1` group이 처음 할당될 때는 `earliest`에서 시작해 Consumer 기동 전에
발행된 미처리 이벤트도 소비한다. 이미 commit된 group은 `auto.offset.reset`과 무관하게 기존
Offset을 그대로 재개한다. 전환 중에는 중복 writer를 막기 위해 Spring 발행을 멈추고 기존 Spark
writer가 당시 topic tail까지 처리한 것을 확인한 뒤 시작한다. 모델 교체 전에는 발행을 멈추고 모든
partition lag가 0인지 확인한다.

```bash
uv run python -m feelm_consumer.party_main
```

필수 설정은 `PARTY_CONSUMER_KAFKA_BOOTSTRAP_SERVERS`,
`PARTY_CONSUMER_KAFKA_GROUP_ID`, `PARTY_CONSUMER_REDIS_URL`,
`PARTY_CONSUMER_MODEL_URI`, `PARTY_CONSUMER_MAPPING_URI`,
`PARTY_CONSUMER_METADATA_URI`다. topic 기본값은 `feelm.party-refresh.v1`, group 기본값은
`feelm-party-consumer-v1`이다. 실제 Redis URL과 내부 URI는 `.env` 또는 승인된 배포 Secret으로만
주입한다. model version은 현재 Spring reader·DB 계약에 맞게 50자 이하여야 한다. 더 긴
`als-artifacts-v2` 식별자를 서비스에 쓰려면 공통 계약을 별도 업무에서 먼저 확장한다. 운영 전환과
롤백 순서는 `infra/README.md`의 파티 Consumer 절을 따른다.

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
| `CONSUMER_DISMISS_KAFKA_TOPIC` | `feelm.dismiss.v1` | 관심없음을 현재 DB snapshot 재계산 신호로 받는 Topic |
| `CONSUMER_DISMISS_SCORE` | `1.0` | 실제 평가가 없는 관심없음에만 적용하는 0.5~5.0 반점 단위 임시 입력값 |
| `CONSUMER_DISMISS_INPUT_VERSION` | `dismiss-virtual-rating-v1` | DISMISS 기반 가상 평점 정책의 로그·배포 추적 버전. Candidate v6 JSON에는 추가하지 않는다 |
| `CONSUMER_CONTENT_URI` | 없음(선택) | 공개 완료된 content-artifacts-v2 버전 경로. `latest` 같은 별칭과 `.<version>.tmp-*` staging 경로는 거부한다 |
| `CONSUMER_GROUP_URI` | 없음(선택) | 공개 완료된 group-serving-artifacts-v1 버전 경로. ALS와 `mapping_revision`이 같아야 하며 설정한 산출물이 불완전하면 시작을 거부한다 |
| `CONSUMER_DISCOVERY_POPULARITY_URI` | 없음(선택) | 공개 완료된 discovery-popularity-artifacts-v1 버전 경로. `CONSUMER_GROUP_URI`와 반드시 함께 설정한다 |
| `CONSUMER_HYBRID_ALPHA` | `0.5` | 고정 ALS 가중치. 0~1 밖이면 시작을 거부한다 |

**`CONSUMER_HYBRID_ALPHA`는 두 성분이 모두 있는 영화에만 적용된다.** 한쪽만 지원하는 영화는
alpha와 무관하게 자기 성분의 순위를 그대로 받으므로, **`alpha=1`로 둔다고 ALS 전용이 되지 않는다.**
콘텐츠를 실제로 끄려면 `CONSUMER_CONTENT_URI`를 비운다.

## 취향 발견 후보 (S15P21E106-415)

`CONSUMER_GROUP_URI`와 `CONSUMER_DISCOVERY_POPULARITY_URI`를 함께 설정하면 기존 `taste` 최대 500편과 독립적으로
그룹 기반 `discovery`를 최대 100편 만든다. 그룹 결과가 없거나 모든 적격 그룹 후보를 소진하면 빈 배열
또는 100편 미만을 게시하며 전체 개인화 모집단으로 보충하지 않는다. 서로 다른 유효 평가 영화 수와
관계없이 가용한 성분으로 두 목록을 계산한다. 온보딩 유래 평가도 저장된 `4.0`·`2.0`을 그대로 사용한다.

발견 방향은 평가가 있는 영화가 속한 고정 그룹 대표로 계산한다. 평가 방향을 만들 수 없으면 DISCOVERY를
게시하지 않는다. 평가와 확정 감상의 합집합에서
`viewed_g <= 2`와 `viewed_g / max(1,total_mapped_viewed) <= 0.20`을 모두 만족하는 그룹을 궁합순으로
정렬한다. 상위 10개 그룹을 우선 공급 그룹으로 사용한다. 각 그룹은 모든
사용자에게 공통인 TMDB 투표 수·KOBIS 누적 관객 백분위의
`max` 순서에서 우선 10편을 공급하며 입력·확정 감상·관심없음은 제외한다. 100편이 모자라면 다음
순위 층을 그룹별로 순회해 11위 한 편씩, 12위 한 편씩 이어서 공급한다. 상위 10개 그룹을 모두
소진해도 부족하면 11번째 적격 그룹을 1위부터 끝까지 공급하고, 계속 부족하면 12번째 이후 그룹도
적합도 순으로 같은 방식으로 소진한다. 모든 적격 그룹 후보가 소진된 경우에만 100편 미만을 허용한다.
이렇게 모은 후보만 현재 평가·서빙 모델의 개인 예측 점수로 다시 정렬한다. 상세 계약은
[`discovery-popularity-artifacts-v1`](../contracts/discovery-popularity-artifacts.md)을 따른다.
후보는 현재 모델이 점수를 낼 수 있는 서비스 게시 가능 모집단으로 한정하지만, 그 모집단의 전체
개인화 순위를 DISCOVERY 부족분 보충에 사용하지 않는다.
Spring은 모바일 기본 요청에서 TASTE 2편과 DISCOVERY 1편, PC 요청에서 TASTE 3편과 DISCOVERY 3편을 조립한다. DISCOVERY가 부족하면 빈 슬롯마다 다음 TASTE로 채운다.

### 관련 업무와 활성화 경계

- S15P21E106-415: 이 절의 DISCOVERY 후보 생성과 로컬 정책 검증
- S15P21E106-471: Candidate v3 `taste`/`discovery` 그룹화와 `generationId` Consumer writer 기준선
- S15P21E106-481: TASTE 후보별 구조화 `reasons[]`를 추가한 Candidate v4 writer·payload 기준선
- S15P21E106-488: 당시 전체 평가 또는 온보딩 이력으로 계산한 저이력 그룹 적합도·상위 기여 비율을
  DISCOVERY 전용 `reasons[]`로 추가했다. S15P21E106-649부터 온보딩 선택은 4.0·2.0 평가로 변환된
  `ratings` 입력으로만 같은 계산에 참여한다.
- S15P21E106-505: 입력 수 제한 제거, TASTE 500·DISCOVERY 100 후보 게시와 당시 그룹 부족분 보충,
  `DISCOVERY_PERSONAL_SCORE`를 추가한 Candidate v6 writer·payload 계약. Spring reader의 화면별 2+1·3+3 조립은
  Backend 소비 계약이며 현재 `develop`에 구현돼 있다
- S15P21E106-482: 그룹·대중성 번들의 HDFS 게시, 두 URI 동시 활성화, 재시작·rollback 검증
- S15P21E106-415 재개선: 상위 10개 그룹의 내부 순위 층별 확장, 소진 후 후순위 적격 그룹 확장과
  DISCOVERY 개인 후보 보충 제거
- S15P21E106-483: 현재 개인 후보 보충 순서를 개선하고 후보 모집단 밖까지 확장하는 별도 POPULAR fallback.
  이 절의 `discovery-popularity`와 다른 산출물

로컬 대중성 번들은 다음처럼 만든다. `--kobis-population`은 직접 코드 2,176편 전체의 백분위
모집단이고, `--verified-kobis`만 실제 서비스 영화에 KOBIS 점수를 붙인다.

```bash
cd pipeline/consumer
export PYTHONPATH=src
python tools/export_discovery_popularity.py \
  --catalog <고정-catalog.parquet> \
  --kobis-population <confirmed-movie-comparison.parquet> \
  --verified-kobis <검토완료-verified-kobis.parquet> \
  --mapping-revision sha256:<서비스-매핑-해시> \
  --popularity-version discovery-popularity-<YYYYMMDD>-v1 \
  --output-root <로컬-번들-root>
```

그룹과 대중성 번들은 같은 서비스 ID↔TMDB ID snapshot으로 만들어야 한다. Consumer는 두 번들의
`mapping_revision`뿐 아니라 전체 ID쌍도 시작 시 비교하고, 하나라도 다르면 Kafka·PostgreSQL·Redis에
연결하기 전에 종료한다.

### DISCOVERY 번들 생성·HDFS 게시 준비

실제 서비스 mapping export를 받은 뒤 그룹 번들을 먼저 만든다. 개인 연구 catalog의
`service_movie_id`를 현재 DB ID라고 가정해서는 안 된다.

```bash
cd pipeline/consumer
export PYTHONPATH=src
python tools/export_group_bundle.py \
  --recipe ../configs/service-v1/group-recipe.v1.json \
  --trusted-source-root <검토된-연구-root> \
  --mapping-uri <불변-service-movie-id-map-URI> \
  --group-version groups-<YYYYMMDD>-v1 \
  --catalog-version catalog-<YYYYMMDD>-v1 \
  --output-root <로컬-번들-root>
```

대중성 번들은 같은 mapping revision으로 생성한다. 두 로컬 버전이 완성되면 하나의 전송 manifest에
함께 고정하되, HDFS 공개는 각각 no-overwrite rename으로 수행한다. 두 publish 사이에 실패하면 아직
Consumer 환경변수를 바꾸지 않고 실패한 쪽만 새 staging 경로에 다시 업로드한다.

```bash
export GROUP_VERSION=groups-<YYYYMMDD>-v1
export POPULARITY_VERSION=discovery-popularity-<YYYYMMDD>-v1
export LOCAL_BUNDLE_ROOT=<로컬-번들-root>
export TRANSFER_MANIFEST=<별도-영속경로>/discovery-upload-manifest.json
export HDFS_ARTIFACT_ROOT=hdfs://<namenode-authority>/<합의경로>
export GROUP_STAGE="$HDFS_ARTIFACT_ROOT/groups/.${GROUP_VERSION}.tmp-$(date +%s)"
export GROUP_FINAL="$HDFS_ARTIFACT_ROOT/groups/$GROUP_VERSION"
export POPULARITY_STAGE="$HDFS_ARTIFACT_ROOT/discovery-popularity/.${POPULARITY_VERSION}.tmp-$(date +%s)"
export POPULARITY_FINAL="$HDFS_ARTIFACT_ROOT/discovery-popularity/$POPULARITY_VERSION"

cd pipeline/spark
uv run python -m jobs.batch.artifact_transfer manifest \
  --root "$LOCAL_BUNDLE_ROOT" \
  --artifact "groups=groups/$GROUP_VERSION" \
  --artifact "discovery-popularity=discovery-popularity/$POPULARITY_VERSION" \
  --output "$TRANSFER_MANIFEST"

hdfs dfs -test -e "$GROUP_FINAL" && { echo 'group version already exists' >&2; exit 1; }
hdfs dfs -test -e "$POPULARITY_FINAL" && { echo 'popularity version already exists' >&2; exit 1; }
hdfs dfs -mkdir -p "$HDFS_ARTIFACT_ROOT/groups" "$HDFS_ARTIFACT_ROOT/discovery-popularity"
hdfs dfs -D dfs.replication=2 -put "$LOCAL_BUNDLE_ROOT/groups/$GROUP_VERSION" "$GROUP_STAGE"
hdfs dfs -D dfs.replication=2 -put \
  "$LOCAL_BUNDLE_ROOT/discovery-popularity/$POPULARITY_VERSION" "$POPULARITY_STAGE"
hdfs dfs -setrep -w 2 "$GROUP_STAGE" "$POPULARITY_STAGE"

uv run python -m jobs.batch.artifact_transfer publish \
  --manifest "$TRANSFER_MANIFEST" --artifact groups \
  --staged-uri "$GROUP_STAGE" --final-uri "$GROUP_FINAL" --spark-master local[2]
uv run python -m jobs.batch.artifact_transfer publish \
  --manifest "$TRANSFER_MANIFEST" --artifact discovery-popularity \
  --staged-uri "$POPULARITY_STAGE" --final-uri "$POPULARITY_FINAL" --spark-master local[2]

cd ../consumer
PYTHONPATH=src python tools/verify_discovery_artifacts.py \
  --group-uri "$GROUP_FINAL" \
  --popularity-uri "$POPULARITY_FINAL"
```

마지막 검증이 성공한 뒤에만 `CONSUMER_GROUP_URI`와
`CONSUMER_DISCOVERY_POPULARITY_URI`를 함께 교체한다. 명령은 manifest SHA 재검증과 두 catalog의
전체 ID쌍 대조까지 수행하며, 실제 Consumer 활성화 전에는 ALS/content도 같은 mapping revision인지
확인해야 한다.

변경 종류별 재생성 범위는 다음과 같다. 산출물은 기존 버전을 덮어쓰지 않고 항상 새 버전을 만든다.

| 변경 | 그룹 번들 | 대중성 번들 | 비고 |
| --- | --- | --- | --- |
| 기존 영화의 TMDB 투표 수 갱신 | 유지 | 재생성 | mapping revision은 유지 가능 |
| KOBIS 누적 관객·검증 연결 갱신 | 유지 | 재생성 | 전체 KOBIS 모집단 백분위도 다시 계산 |
| 서비스 영화 추가·삭제 | 재생성 | 재생성 | 새 불변 mapping revision을 두 번들에 동일 적용 |
| 기존 service ID가 다른 TMDB ID로 재결합 | 활성화 금지 | 활성화 금지 | 먼저 mapping 오류를 해결하고 새 번들 생성 |
| 그룹 입력 장르·키워드·줄거리 변경 | 재생성 | 필요 시 유지 | 고정 transform으로 재할당하며 재학습은 하지 않음 |

새 서비스 영화의 TMDB ID가 고정 연구 catalog에 없으면 현재 reference exporter만으로는 그룹에 넣을 수
없다. 이 경우 frozen transform에 현재 영화 metadata를 공급하는 별도 export 경로가 준비될 때까지
DISCOVERY에서 제외하며, 임의 제목·연도 매핑으로 기존 그룹을 복사하지 않는다.

alpha에 대한 후보 구성은 단조롭지 않다. 실측한 상위 500편의 ALS Factor 보유 편수는
`alpha=0.0` 474편 → **`alpha=0.5`(기본값) 47편** → `alpha=1.0` 370편으로 **기본값에서 가장 적다.**
두 성분을 모두 가진 영화만 평균으로 점수가 깎이고 한쪽만 가진 영화는 그대로이기 때문이며,
양 끝값에서는 한쪽 성분이 사실상 무시되어 깎임이 사라진다. 기본값을 바꿀 때는 이 비단조성을 전제로
실측값을 다시 확인한다.

점수는 S15P21E106-315 규칙을 따른다. 사용자별로 각 컴포넌트가 지원하는 영화 집합에 대해 동률을
인식하는 백분위를 구하고, 둘 다 있으면 `alpha × ALS + (1-alpha) × 콘텐츠`로 섞는다. 한쪽만 있으면
그 백분위를 그대로 쓰고, 둘 다 없으면 후보가 아니다. 정렬은 점수 내림차순·서비스 영화 ID 오름차순이다.

게시하는 `score`는 별점이 아니라 **활성 모델이 만든 사용자 내 상대 순위 점수**다. Consumer는 이를
후보 집합 백분위로 다시 변환하지 않는다. ALS 전용 경로는 ALS 상대 점수를, 현재 하이브리드 경로는
위 백분위 혼합 점수를 그대로 게시하므로 계산식과 범위가 서로 다를 수 있다. Redis 계약은 같은 사용자·
같은 생성 결과의 배열에서 값이 클수록 먼저 추천한다는 순서만 보장한다. 다른 모델의 점수를 한 목록에서
통합하거나 사용자 간 값을 집계해야 할 때 정규화 방식과 버전 계약을 별도로 확정한다.

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
이 상태에 들어가면 `content_scores_non_positive`에 최상위 동점 편수를 남긴다. 입력 수가 적더라도
후보 계산을 막지 않으므로 이 로그는 낮은 정보량의 결과를 운영에서 구분하는 진단값이다.

평가가 한 편뿐이어도 가용한 ALS·콘텐츠 성분으로 후보를 계산·게시한다.
적은 입력에서 품질이 낮을 수 있지만 이를 접근 제한이나 정상 처리 skip의 근거로 사용하지 않는다.

입력이 없거나 어떤 성분도 사용자 프로필을 만들 수 없으면(신규 사용자, 두 산출물 모두에 없는 이력)
ALS에 매핑된 서비스 영화와 콘텐츠 지원 영화의 합집합을 `score=0.0`, 서비스 영화 ID 오름차순으로
TASTE에 게시하고 `candidate_tie_fallback`을 남긴다. 근거가 있으면 TASTE `reasons[]`는
`TOP_RATED_SOURCE`를 사용하고, 입력이 없어 근거가 없으면 `reasons: []`로 게시한다. 사용자 방향이
없으면 DISCOVERY는 빈 배열이다. 서비스 가능한 TASTE 후보 모집단 자체가 비어 있을 때만
`no_publishable_result_skipped` 정상 처리한다.

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
진전시키지 않고 재처리한다. 실제 Redis·Kafka·PostgreSQL을 사용한 격리 rebalance·장애 검증의 절차와
결과는 [401 fencing 통합 검증](fencing-integration-validation.md)에 기록했다. 이 검증은 실제 모델 점수 계산이나
운영 배포 검증을 대신하지 않는다.

assign 중 하나라도 Redis fence claim이 실패하면 Consumer는 어떤 partition도 local ownership으로 등록하거나
assign하지 않고 종료한다. 이미 성공한 claim은 새 token으로 남지만 해당 프로세스는 게시·commit하지 않으며,
supervisor 재시작 뒤 새 group assign에서 모두 새로 claim한다.
로그에는 event payload, DSN, Redis URL을 기록하지 않는다.

Redis 출력은 `candidate-version`, `vector`, `candidates` 세 String 키이며 TTL은 없다.
`feelm:consumer-fence:{groupId}:{topic}:{partition}`은 Consumer 내부의 String fencing key다. assign마다 CSPRNG
opaque token으로 교체하며 후보 결과를 읽는 Spring은 이 키에 의존하지 않는다. key가 유실되면 모든 기존 token은
비교에서 거부되고, 다음 assign은 새 token을 설치한다. Redis의 과거 snapshot 복원처럼 fence key가 이전 값으로
되돌아가는 장애는 외부의 durable coordinator 없이는 막지 못하며 이 Consumer의 보장 범위 밖이다.
`candidates.taste`의 각 항목은 `movieId`, `score`, `reasons` 객체 배열을 포함한다.
`reasons[]`는 Consumer가 만든 구조화된 근거(`code`, `sourceMovieIds`, `matchedGenreCodes`)이며 Spring이 영화·장르명을
조회해 사용자 문구로 변환한다. `SHARED_GENRE`는 고평점 source 영화와 후보의 공통 장르를, `TOP_RATED_SOURCE`는
공통 장르가 없을 때 가장 높은 평가 source 영화를 뜻한다.
`candidates.discovery`는 `movieId`, `score`, DISCOVERY 전용 `reasons` 객체 배열을 포함한다. 그룹 산출물을 설정하면
그룹 후보만 사용하고, 설정하지 않으면 빈 배열을 게시한다. 영화 대표 맛을 포함한 표시용 메타데이터는
Spring이 PostgreSQL에서 조회한다.
`DISCOVERY_AGGREGATE_AFFINITY`는 전체 평가 이력으로 만든 방향만 사용한다. `topContributors`는 후보 그룹 적합도에 양의
기여를 한 입력 중 상위 3개, `contributorCount`는 그런 입력 전체 수, `topContributionCoverage`는 양의 기여
총합 중 상위 3개 기여 합의 비율이다. 이 비율은 그룹 적합도 근거의 집중도이지 영화 최종 점수의 설명률이 아니다.
`viewedCountInGroup`, `affinityRank`, `popularityRankInGroup`은 각각 저이력 판정, 적격 그룹 중 궁합 순위,
제외 후 그룹 내부 대중성 순위를 나타낸다. 공급 그룹 수 같은 전역 진단 값은 Redis에 게시하지 않고 Consumer 로그에만 남긴다.
그룹 후보가 100편보다 적으면 선택한 상위 10개 그룹의 11위 후보를 그룹 적합도 순으로 한 편씩 사용하고,
계속 부족하면 12위, 13위 순으로 같은 층별 선택을 반복한다. 상위 10개 그룹을 모두 소진해도 부족하면
11번째 적격 그룹부터 그룹별 대중성 1위부터 끝까지 사용한 뒤 다음 적격 그룹으로 이동한다. 모든 적격
그룹 후보를 소진한 경우에만 100편 미만을 그대로 게시하고 개인 점수 후보로 보충하지 않는다.
Candidate v6 reader는 전환 호환을 위해 과거
`DISCOVERY_PERSONAL_SCORE` 항목을 계속 읽을 수 있지만 현재 Consumer는 새로 생성하지 않는다.
두 payload에는 동일한 `candidateVersion=6`(JSON 형식·reason code 버전)와 `generationId`(한 번의 생성 결과 식별자)가
포함된다. Backend가 v5/v6를 함께 읽는 전환 reader를 먼저 반영하고 Consumer v6 전환과 함께 모든 활성 사용자를
재게시한다. v6와 `generationId` 일치를 확인한 뒤 Backend의 v5 호환을 제거한다. Consumer는 추천 문구나
`CONCENTRATED` 같은 임의 구간값이 아니라 구조화된
측정값만 생성한다. Spring은 합의한 coverage 기준보다 낮으면 개별 영화명 대신 전체 이력 기반 문구를 사용한다.
Spring이 후보 전체의 최종 제외·재정렬을 담당한다. 영화별 OTT 제공 정보와 사용자 OTT 클릭 정보는 PostgreSQL에서 조회한다. 별도 `topn`은 생성하거나 복구 조건에 포함하지 않는다.
기존 `topn` 키 자동 삭제는 하지 않는다. 배포 전 Spring reader 전환이 필요하다.
