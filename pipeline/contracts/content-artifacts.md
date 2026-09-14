# 콘텐츠 산출물 계약

같은 S15P21E106-315 구조화 콘텐츠 표현(장르·맥락·인물·키워드 네 그룹 희소 벡터)을 용도별로
두 버전의 산출물로 포장한다. 두 버전은 경로·형식·결합 대상이 다르며 서로 보충해 읽지 않는다.

| 버전 | 용도 | 형식·경로 | 결합 대상 | 절 |
| --- | --- | --- | --- | --- |
| `content-artifacts-v1` | 오프라인 Content/ALS Hybrid 비교 평가 | 로컬 JSON, `content-artifacts/<content-version>/` | ALS 모델 버전·매핑 스냅샷 | 1~3장 |
| `content-artifacts-v2` | 서빙 Consumer 입력 | Parquet(로컬·HDFS), `content/<content-version>/` | 매핑·영화 메타데이터 revision | 4~7장 |

# Part A. content-artifacts-v1 (오프라인)

이 계약은 Jira 315의 **오프라인 Content/ALS Hybrid 비교**에만 적용한다. Redis,
Consumer 후보, API 응답 또는 기존 `als-artifacts-v1`의 의미를 변경하지 않는다.

## 1. 버전 묶음

콘텐츠 artifact는 `<output-uri>/content-artifacts/<content-version>/`에 불변으로
공개한다. `content-version`, `als-model-version`, `mapping-snapshot`은 각각 공백·`.`·`..`·플랫폼
path separator·NUL 없는 식별자이며, 기존 버전을 덮어쓰지 않는다. 세 값은 artifact와 selection
경로에서 같은 path-component 검증을 거친다.

```text
<content-version>/
├── features.json
├── manifest.json
└── _SUCCESS
```

`manifest.json` 필수 필드는 `schema_version=content-artifacts-v1`, `version`,
`als_model_version`, `mapping_snapshot`, `feature_count`, `vocabulary_size`,
`features_sha256`이다. reader는 요청한 content/ALS/mapping 세 버전이 정확히 일치하고
SHA-256과 행 수가 일치할 때만 읽는다. 서로 다른 실행의 값을 보충하거나 섞지 않는다.

평가 선택 결과는 콘텐츠 bundle에 사후 추가하지 않는다. 같은 **exact content/ALS/mapping
bundle**을 재실행할 수 있도록
`<output-uri>/content-evaluations/<content-version>/<als-model-version>/<mapping-snapshot>/selection/selection.json`
및 SHA-256 sidecar를 UUID 소유 staging에서 함께 검증한 뒤 불변 directory로 공개한다. 세
version은 각각 검증된 path component여서 canonical filesystem path에서 tuple 직렬화 충돌이
없다. 이미 공개된 artifact의 재사용은 manifest lineage가 요청한 세 version과 정확히 일치할
때만 허용하며, ALS 또는 mapping만 바꾼 재실행은 새 content artifact가 필요하다.

## 2. 표현

`features.json`에는 `features`, `vocabulary`, `prior`가 있다.

- feature 한 행은 `tmdb_id: positive integer`, `indices: sorted unique int[]`,
  `values: finite float[]`이며 두 배열 길이는 같다.
- vocabulary index는 0부터 빈칸 없이 이어진다.
- prior는 0.5~5.0 반별점 순서의 길이 10 확률 배열이며 합은 1이다.
- Content-only 서비스 영화는 품질 정답에 넣지 않고 SERVICE_COVERAGE에서만 센다.
- 평가 입력의 `service_movie_id`와 `tmdb_id`는 각각 양의 유일 값이어야 한다. MATCHED의
  `movielens_movie_id`도 양의 유일 값이며, MATCHED와 SERVICE_ONLY_CONTENT_BASED 후보의
  `tmdb_id`는 artifact feature에 있어야 한다. 영벡터 feature는 정상 Content 미지원이고,
  누락·중복 결합은 입력 오류다.

현재 local writer는 stage 검증·재로딩·동일 파일시스템 rename 뒤에만 `_SUCCESS`를 만든다.
HDFS adapter와 Consumer/Redis 게시 형식은 199/167의 합의 후 별도 계약 버전으로 추가한다.

## 3. 오프라인 평가 경계

validation은 train known/validation holdout만, final test는 train+validation known/test
holdout만 사용한다. `SERVING`은 평가 입력이 아니다. known/holdout 사용자·영화 쌍의
중복은 오류다. validation에서 ALS_ONLY, CONTENT_ONLY, alpha 0.25/0.5/0.75를
NDCG@10, Recall@10, 고정 정책 순서로 선택한다. test는 ALS_ONLY, CONTENT_ONLY 및
선택된 hybrid 하나만 실행하며 test 결과로 재선택하지 않는다.

이 산출물의 상태는 `OFFLINE_ONLY`다. MovieLens known 제외 evidence는 실제 서비스
사용자 추천 결과가 아니므로 Redis에 그대로 게시해서는 안 된다.

# Part B. content-artifacts-v2 (서빙)

v2는 서비스 영화 전체의 콘텐츠 벡터를 서빙 Consumer가 로컬 파일시스템 또는 HDFS에서 읽도록
포장한다. 목적은 ALS Factor가 없는 영화(MovieLens 미연결·모호 연결)와 ALS 입력이 부족한 사용자를
콘텐츠 점수로 보완하는 것이다. 점수 결합·후보 게시·Redis 형식은 S15P21E106-417과
[스트리밍 계약](../../docs/api/streaming-contract.md)이 정한다. v2는 v1과 ALS 계약의 의미를 바꾸지 않는다.

## 4. 입력 스냅샷과 공개 순서

아래 순서로 각 산출물을 불변 경로에 공개한다. 앞 단계의 revision은 다음 단계의 명시 입력이다.

1. 영화 ID 매핑 스냅샷(118): `service-movie-id-map/`의 단일 `mapping_revision`
2. TMDb 정규화 스냅샷: 단일 `tmdb_snapshot_revision`
3. 영화 메타데이터 스냅샷(199): **서비스 매핑 전체 상태**를 포함하며 단일 `movie_metadata_revision`
4. ALS SERVING(`als-artifacts-v2`)과 content-artifacts-v2: 같은 `mapping_revision`,
   `movie_metadata_revision`, `input_snapshot`을 기록

TMDb 정규화 스냅샷은 서비스 매핑의 모든 `tmdb_id`를 한 행씩 가진 Parquet이다. 영화 메타데이터
builder가 그대로 읽도록 TMDb의 `id`·`title`·`genres` 이름을 유지한다.

| 컬럼 | Spark 타입 | 규칙 |
| --- | --- | --- |
| `id` | `integer` | TMDb ID. 원본 파일명 `<id>.json`과 본문 `details.id`가 같아야 한다 |
| `mapping_revision` | `string` | 이 스냅샷의 카탈로그 범위가 된 매핑 스냅샷의 `sha256:<64 소문자 hex>`. 모든 행이 같다 |
| `title`, `original_title` | `string` nullable | 앞뒤 공백 제거, 빈 값은 null |
| `genres` | `array<struct<id:int,name:string>>` | 양의 ID와 비어 있지 않은 이름만 |
| `original_language` | `string` nullable | 공백 제거·소문자, 빈 값은 null |
| `release_year` | `integer` nullable | `release_date` 앞 4자리 숫자가 양수일 때만 |
| `runtime_minutes` | `integer` nullable | 양수일 때만 |
| `genre_ids`, `director_ids`, `keyword_ids` | `array<int>` | 양의 고유 ID 오름차순. 감독은 crew `job=Director`, 키워드는 `keywords` 또는 `results` |
| `top5_cast_ids` | `array<int>` | cast를 (`order` 오름차순, 없으면 1,000,000), `id`로 정렬한 뒤 처음 등장한 고유 ID 최대 5개 |
| `top5_cast_source_order` | `array<int>` | 각 배우가 위 정렬 목록에서 처음 등장한 0부터의 위치. 엄격 증가 |
| `keywords_present` | `boolean` | 키워드 원본 문서 존재 여부 |
| `feature_eligible` | `boolean` | `original_language`·`release_year`가 있고 `genre_ids`가 비어 있지 않음 |
| `tmdb_snapshot_revision` | `string` | 모든 행이 같은 `sha256:<64 소문자 hex>` |

서비스 영화의 원본 문서 누락, 파일명과 본문 ID 불일치, 같은 ID의 중복 문서는 오류이며 아무것도
공개하지 않는다. 키워드 문서가 없는 영화는 빈 `keyword_ids`로 둔다.

"공백 제거"는 Python `str.strip()`과 같은 문자 집합(`str.isspace()`가 참인 모든 문자, 탭·전각 공백
포함)을 뜻한다. Spark `trim`은 U+0020만 제거하므로 그대로 쓰지 않는다. 315 함수와 token 문자열이
같아야 하기 때문이다.

영화 메타데이터 스냅샷(199)의 `title`과 `genre_codes`는 위 TMDb 스냅샷의 `title`·`genres[].name`을
그대로 옮긴 값이다. content 생성기는 두 값이 입력 TMDb 스냅샷에서 유래했는지 양방향으로 대조해
세대가 섞인 번들을 거부한다. `genre_codes`는 따라서 **TMDb 표시명**(수집 언어에 따라 한국어)이며
서비스 장르 코드 체계와 다르다. 서비스 코드 기준 통일은 백엔드 담당과 합의할 후속 과제다.

## 5. 경로와 형식

```text
<output-uri>/content/<content-version>/
├── features/    # 서비스 영화별 희소 콘텐츠 벡터 Parquet
├── vocabulary/  # feature index와 token Parquet
└── metadata/    # 전체를 읽으면 정확히 한 행인 Parquet
```

- `content-version`은 `^[a-z0-9][a-z0-9._-]{0,127}$`를 따르고 공개 후 덮어쓰거나 일부만 수정하지 않는다.
- 세 경로는 디렉터리이며 파일명·파일 수·행 순서는 계약에 포함하지 않는다.

| 경로 | 컬럼 · Spark 타입 | 제약 |
| --- | --- | --- |
| `features/` | `service_movie_id: long`, `tmdb_id: int`, `indices: array<int>`, `values: array<double>` | 서비스 매핑 행마다 정확히 한 행. 두 ID 양수·고유. `indices`는 `[0, vocabulary_size)`의 엄격 증가 값, `values`는 같은 길이의 유한한 양수. 미지원 영화는 두 배열이 모두 빈 영벡터 |
| `vocabulary/` | `feature_index: int`, `token: string` | index는 0부터 빈칸 없이 이어지고 token은 비어 있지 않은 고유 값 |
| `metadata/` | 아래 표 | 정확히 한 행 |

| 메타데이터 컬럼 | Spark 타입 | 의미 |
| --- | --- | --- |
| `contract_version` | `string` | `content-artifacts-v2` |
| `content_version` | `string` | 버전 디렉터리명 |
| `input_snapshot` | `string` | 영화 메타데이터·ALS SERVING과 같은 불변 입력 스냅샷 식별자 |
| `input_uri` | `string` | `ratings/`와 `service-movie-id-map/`을 읽은 매핑 스냅샷 루트. 이동 가능한 별칭 경로는 거부한다 |
| `mapping_revision`, `movie_metadata_revision`, `tmdb_snapshot_revision` | `string` | 입력 스냅샷 revision |
| `feature_rule` | `string` | `structured-4group-v1` (6장) |
| `eligibility_rule` | `string` | `language-year-genre-v1` (4장 `feature_eligible`) |
| `built_at_utc` | `string` | `YYYY-MM-DDTHH:mm:ssZ` |
| `spark_version` | `string` | 생성 Spark 버전 |
| `service_movie_count`, `supported_movie_count` | `long` | features 행 수, 비어 있지 않은 벡터 수 |
| `vocabulary_size` | `int` | vocabulary 행 수 |
| `prior_probability`, `prior_midrank` | `array<double>` | 0.5~5.0 반별점 10구간 prior와 midrank (6장) |
| `prior_user_count`, `prior_rating_count` | `long` | prior 계산에 쓴 사용자·평점 수 |
| `content_revision` | `string` | features·vocabulary·prior 내용의 `sha256:<64 hex>` (7장) |
| `implementation_revision` | `string` nullable | 실행 코드 commit SHA 또는 소스 해시 |

## 6. 벡터와 prior 규칙

`structured-4group-v1`은 315 `batch.content.features.build_content_vector`와 같다.
`feature_eligible=false`이면 영벡터다. 그 외에는 네 그룹의 고유 token을 만든다.

| 그룹 | token |
| --- | --- |
| genre | `genre:<id>` |
| context | `language:<code>`, `decade:<year//10*10>`, `runtime30:<minutes//30>` (값이 있을 때만) |
| people | `director:<id>`, `cast:<id>` |
| keyword | `keyword:<id>` |

비어 있지 않은 그룹 g의 token 수를 n_g라 할 때 각 token 가중치는 `0.25 / sqrt(n_g)`이고, 전체
벡터를 L2 정규화한다. vocabulary는 (genre, context, people, keyword) 그룹 순서와 token 문자열
순서로 정렬해 index를 부여한다(315 `build_vocabulary`). 구현은 315 함수와 float64 상대오차
1e-12 이내로 일치해야 한다.

prior는 매핑 스냅샷의 MovieLens `ratings/` 전체에서 사용자별 10구간 히스토그램을 만들고 315
`user_equal_prior`로 사용자 동일 가중 평균 확률과 midrank를 계산한다. 서빙 사용자의 콘텐츠 profile은
이 prior로 315 `content_profile`을 계산한다. v1 writer의 placeholder prior를 사용하지 않는다.

## 7. 저장 완료와 소비 조건

- 작성자는 `content/.<content-version>.tmp-<uuid>`에 세 경로를 모두 쓰고, 공개 reader와 같은 검증
  (스키마·건수·ID 고유성·index 범위·유한값·prior·`content_revision` 재계산)을 통과한 뒤 같은
  파일시스템에서 `content/<content-version>`으로 한 번에 rename한다. 로컬은 부모 디렉터리 잠금,
  HDFS는 `Rename.NONE`을 사용하고 이미 있는 버전은 보존한다. 실패하면 임시 경로를 삭제한다.
- `content_revision`은 `features`와 `vocabulary`의 각 행을 컬럼 순서대로 JSON 직렬화한 SHA-256을
  정렬·연결해 각각 해시한 값과 prior 확률의 Python `repr` 목록을
  `features:<hex>\nvocabulary:<hex>\nprior:<p1,...,p10>`로 묶은 SHA-256이다. 행 직렬화는 Spark
  `to_json(struct(...))`의 숫자 표기를 그대로 쓰므로 **재계산은 Spark reader만** 한다. Spark가
  아닌 소비자는 값을 다시 계산하지 말고 메타데이터의 `content_revision` 문자열 일치만 확인한다.
  Spark·JDK 버전이 바뀌면 같은 내용에서 다른 표기가 나올 수 있다. 따라서 **런타임 업그레이드 뒤의
  재계산 불일치는 손상의 증거가 아니다.** 메타데이터의 `spark_version`과 생성 당시 런타임을 비교해
  판단하고, 필요하면 같은 입력으로 새 `content-version`을 만들어 교체한다(기존 버전은 보존한다).
- 로컬에서 만든 버전을 HDFS에 올릴 때도 같은 파일시스템의 임시 경로에 전체를 올리고 파일 목록·
  바이트·SHA-256을 **모두** 대조한 뒤 덮어쓰기 없이 rename한다(`jobs.batch.artifact_transfer`).
  대조 대상은 `_SUCCESS`를 포함한 모든 파일이며, 로컬 전용 체크섬 사이드카(`.crc`)만 제외한다.
  스테이징에 manifest에 없는 파일이 하나라도 있거나 manifest의 파일이 하나라도 없으면 공개하지 않는다.
  셸 `hdfs dfs -mv`는 최종 경로가 이미 있으면 그 디렉터리 *안으로* 옮기고 성공으로 끝나므로 쓰지
  않는다. 복제 수는 클러스터 설정을 상속한다고 가정하지 않고 명시한다.
- 소비자는 세 경로, 메타데이터 한 행, `contract_version`, 규칙 식별자와 데이터 제약을 확인한다.
  `mapping_revision`·`movie_metadata_revision`·`input_snapshot`이 함께 읽는 ALS SERVING 모델
  메타데이터와 정확히 같을 때만 사용하며, 누락·불일치 시 거부하고 다른 버전으로 보충하지 않는다.
- 콘텐츠 점수 후보 자격은 매핑 상태가 `MATCHED`·`SERVICE_ONLY_CONTENT_BASED`·`TMDB_ID_AMBIGUOUS`이고
  벡터가 비어 있지 않은 영화다. ALS 점수는 기존대로 Factor가 있는 `MATCHED` 영화에만 존재한다.
  영벡터는 정상적인 콘텐츠 미지원이며 입력 오류가 아니다.
- 두 점수가 모두 없는 서비스 영화는 추천 후보가 되지 않는다. 실데이터 r1 기준 237,817편 중
  29,929편(12.6%)이 여기 해당하며 대부분 TMDb에 장르가 없어 `feature_eligible=false`인 영화다.
  `eligibility_rule` 완화나 대중성 fallback으로 이 범위를 줄일지는 소비자(S15P21E106-417)가 정한다.
- 경로·타입·필수 컬럼·token·가중치·prior 의미를 깨는 변경은 `contract_version`을 올린다.
