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
`candidates.items` 전체에는 `movieId`, `score`, `type`, nullable `flavorCode`만 포함된다.
Consumer는 추천 문구인 `reason`을 생성하지 않는다. Spring이 최종 추천의 검증 가능한 근거로 문구를 구성한다.
Spring이 후보 전체의 최종 제외·재정렬을 담당한다. 영화별 OTT 제공 정보와 사용자 OTT 클릭 정보는 PostgreSQL에서 조회한다. 별도 `topn`은 생성하거나 복구 조건에 포함하지 않는다.
기존 `topn` 키 자동 삭제는 하지 않는다. 배포 전 Spring reader 전환이 필요하다.
