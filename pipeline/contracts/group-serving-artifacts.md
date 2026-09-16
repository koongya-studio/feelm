# 공개 맛·추천 내부 8×128 그룹 서빙 산출물 계약

`group-serving-artifacts-v1`은 공개 팝콘 맛과 영화 발견 추천이 함께 사용하는 고정 2단계 분류다. 먼저 장르 축으로
8개 `taste_id`를 정하고, 각 taste 안에서 장르·키워드·줄거리 축으로 128개 `child_id`를 정한다.
`group_id = taste_id * 128 + child_id`여서 전체 ID 범위는 0~1023이다.

상위 `taste_id` 0~7은 ADR-0020의 공개 팝콘 맛이며 각각 `FLAVOR_A`~`FLAVOR_H` 및
`popcorn_flavors.cluster_id` 0~7과 순서대로 대응한다. `child_id`와 `group_id`는 추천 내부 값으로
공개 API나 `movies.representative_flavor_id`에 저장하지 않는다.

## 1. 원본과 안전 변환

학습·재군집은 이 작업의 범위가 아니다. writer는
[`group-recipe.v1.json`](../configs/service-v1/group-recipe.v1.json)에 고정된 원본 파일의 바이트 수와
SHA-256을 **pickle 역직렬화 전에** 확인한다. 검증된 파일에서 allow-list의 전처리 배열·center·mean만
추출하며, 연구 정책·predictor·품질 객체는 서빙 번들로 옮기지 않는다.

변환은 `group-export-requirements.txt`의 격리 환경에서 실행한다. 서버와 Consumer는 pickle이나
scikit-learn estimator를 읽지 않고 NumPy `allow_pickle=false` 배열과 Parquet만 읽는다. recipe에 고정된
배열 shape·dtype·내용 해시가 하나라도 다르면 writer는 아무것도 공개하지 않는다.

## 2. 경로와 불변 공개

```text
<output-uri>/groups/<group-version>/
├── arrays.npz
├── assignments.parquet
├── metadata.json
├── manifest.json
└── _SUCCESS
```

- `group-version`, `catalog-version`은 `^[a-z0-9][a-z0-9._-]{0,127}$`이다.
- writer는 `groups/.<group-version>.tmp-<uuid>`에서 전체 파일을 생성하고, manifest SHA-256과 공개
  reader 재로딩이 성공한 뒤 같은 파일시스템에서 최종 디렉터리로 한 번에 rename한다.
- 이미 있는 최종 버전은 덮어쓰지 않는다. 서로 다른 버전의 파일을 합쳐 읽지 않는다.
- 로컬→HDFS 전송은 `jobs.batch.artifact_transfer`의 전체 파일 inventory·size·SHA 대조와
  `Rename.NONE` 공개 절차를 그대로 사용한다. 셸 `hdfs dfs -mv`로 최종 공개하지 않는다.

`manifest.json`은 `contract_version=group-serving-artifacts-v1`과 `_SUCCESS`, `arrays.npz`,
`assignments.parquet`, `metadata.json` 각각의 상대 경로·바이트·SHA-256을 가진다. `_SUCCESS`는 빈 파일이다.

## 3. 고정 배열

`arrays.npz`는 아래 key만 가지며 object dtype을 금지한다.

| key | dtype · shape | 의미 |
| --- | --- | --- |
| `genre_ids` | int64 `[19]` | TMDb 장르 ID 축 |
| `keyword_ids` | int64 `[6543]` | 키워드 ID 축 |
| `keyword_idf` | float64 `[6543]` | 고정 키워드 IDF |
| `keyword_components` | float64 `[64,6543]` | 고정 키워드 SVD 축 |
| `text_tokens` | Unicode `[16000]` | overview vocabulary의 index 순서 |
| `text_idf` | float64 `[16000]` | 고정 overview IDF |
| `text_components` | float64 `[48,16000]` | 고정 overview SVD 축 |
| `top_centers` | float64 `[8,131]` | 장르 기반 상위 center |
| `child_centers` | float64 `[8,128,131]` | taste별 하위 center |
| `group_means` | float64 `[1024,131]` | 추천 점수용 원본 평균; 재정규화하지 않음 |
| `top_weights` | float64 `[3]` | 정확히 `[1,0,0]` |
| `sub_weights` | float64 `[3]` | 정확히 `[0.5,0.35,0.15]` |
| `offsets` | int64 `[9]` | 정확히 `[0,128,...,1024]` |

장르·키워드·overview block은 각각 L2 정규화하고, 하위 벡터는
`L2(concat(sqrt(.5)*G, sqrt(.35)*K, sqrt(.15)*T))`로 만든다. 거리는 float64
`sum((x-center)^2)` 고정 축 합이며, 완전히 같은 거리에서는 낮은 고정 ID가 이긴다. 신규 영화에도 fit,
center 재정렬, vocabulary 확장이나 estimator `predict`를 수행하지 않는다.

## 4. Assignment Parquet

`assignments.parquet`은 bundle 생성에 사용한 불변 `service-movie-id-map` snapshot의 모든 행과 정확히 1:1이다.
`service_movie_id`와 `tmdb_id`는 각각 양수·유일하고 제목·연도 추측 매핑을 금지한다.

| 컬럼 | 물리 타입 | 규칙 |
| --- | --- | --- |
| `service_movie_id`, `tmdb_id` | int64 | 현재 DB export와 연결된 양의 고유 ID |
| `taste_id`, `child_id`, `group_id` | int32 | 각각 0~7, 0~127, `taste*128+child` |
| `top_supported`, `sub_supported`, `group_mapped` | boolean | 아래 지원 정책 |
| `catalog_version`, `geometry_version` | string | 불변 입력·geometry 버전 |
| `group_recipe_revision`, `mapping_revision` | string | `sha256:<64 hex>` |
| `vector_hash`, `source_hash` | string | 행별 `sha256:<64 hex>` lineage |

장르 축 영벡터면 `top_supported=false`다. 장르가 없어도 키워드·overview로 계산된 진단용
`taste_id/child_id/group_id`는 보존하지만, `group_mapped=false`로 두어 발견 후보와 시청 group 분모에서
제외한다. 즉 `group_mapped = verified_id_mapping AND top_supported AND sub_supported`다. 장르 없는 영화를
카탈로그나 검색에서 삭제한다는 뜻은 아니다.

## 5. Metadata와 Consumer 시작 조건

`metadata.json` 필수 항목은 `contract_version`, `group_version`, `catalog_version`,
`geometry_version=g913-gkt128-mean-v1`, `mapping_revision`, `group_recipe_revision`, `movie_count`,
`mapped_movie_count`, `genre_missing_count`, `genre_missing_policy=EXCLUDE_FROM_DISCOVERY`,
`public_taste_semantics`다. S15P21E106-466 이후 새 export는 `PUBLIC_POPCORN_FLAVOR_V1`을 사용한다.
이미 해시가 고정된 S15P21E106-439 기준 bundle의
`RECOMMENDATION_INTERNAL_NOT_PUBLIC_POPCORN` 값은 파일을 다시 쓰지 않기 위한 legacy lineage로만
허용하며, 현재 서비스 의미는 이 계약과 ADR-0020의 공개 맛 매핑이 우선한다.

Consumer는 manifest와 모든 파일 해시, 배열 inventory·shape·dtype·유한값, assignment 물리 타입·ID
유일성·lineage를 검사한다. 함께 읽는 ALS/content bundle은 같은 불변 catalog 세대이므로
`mapping_revision`과 정확히 같을 때만 사용하고, 누락·불일치·부분 파일이면 `GROUP_BUNDLE_NOT_READY`로
시작을 거부한다. 최신 별칭이나 다른 버전으로 보충하지 않는다. S15P21E106-415는 이 loader와
`group_mapped=true` 행만 사용해 추천 정책을 구현한다.

반면 **현재 PostgreSQL live export의 전체 `mapping_revision`은 bundle revision과 같을 필요가 없다.**
배포 시 bundle assignment와 live export의 `(service_movie_id, tmdb_id)`를 비교한다. live export의 두 ID는
각각 양수·유일해야 하며, bundle에도 있던 같은 `service_movie_id`가 다른 `tmdb_id`로 재결합됐으면
활성화를 거부한다. 다음 차이는 허용하고 건수를 기록한다.

- live에만 추가된 영화: 현재 bundle에는 assignment가 없으므로 다음 bundle 전까지 발견 추천에서 제외
- bundle에만 남은 삭제·비활성 영화: 현재 서비스 catalog와 결합할 때 무시
- 기존 ID쌍이 그대로인 영화: 기존 assignment 사용

따라서 live 영화 추가·삭제만으로 frozen geometry·mean·vocabulary를 다시 학습하지 않는다. 새 영화를
발견 추천에 포함할 때만 같은 frozen transform과 center로 새 불변 catalog bundle을 생성한다.
`compare_live_mapping`은 이 판정을 수행하며 `activation_allowed=false`인 ID 재결합을 배포 게이트로 사용한다.

## 6. 서비스 DB 반영

`assignments.parquet`을 서비스 DB에 반영할 때는 다음 순서를 지킨다.

1. manifest·파일 SHA·schema와 기준 수치(전체 237,817, mapped 207,240, unmapped 30,577)를 검증한다.
2. `popcorn_flavors`가 정확히 `FLAVOR_A..H ↔ cluster_id 0..7`인지 확인한다.
3. assignment를 임시 staging table에 넣고 `(service_movie_id, tmdb_id)`를 함께 비교한다. 어느 한 ID가
   다른 영화와 재결합됐으면 전체 반영을 거부한다.
4. 정확히 일치하는 행 중 `group_mapped=true`는 `taste_id`와 같은 `cluster_id`의 맛 FK로 갱신하고,
   `group_mapped=false`는 대표 맛을 `NULL`로 만든다.
5. DB에만 있는 영화와 bundle에만 있는 영화는 변경하지 않고 각각 집계한다.
6. staging 적재 뒤 `movies`와 `popcorn_flavors`의 DML을 직렬화하는 잠금을 얻고 검증·반영한다. 잠금 전에
   진행 중이던 writer는 완료 후 같은 DB 상태에 포함되며, 잠금 뒤 시작한 writer는 반영 transaction 이후에
   실행된다.
7. 기본 실행은 rollback-only dry-run이다. 명시적으로 apply한 경우에도 한 transaction으로 처리하고,
   검증·건수 불일치·SQL 오류가 있으면 전체 rollback한다.

`pipeline/consumer/tools/apply_popcorn_flavors.py`가 이 절차를 수행한다. DSN은 파일이나 명령행에 쓰지
않고 기본 `CONSUMER_POSTGRES_DSN` 또는 명시적으로 지정한 환경변수에서 읽는다. 같은 bundle 재실행은
같은 최종 상태를 만들어야 한다.

## 7. 검증 기준

- 원본 237,817행의 `taste_id`, `child_id`, `group_id` 불일치 0건
- 장르 미지원 30,577행은 `group_mapped=false`
- 신규·갱신 영화의 단건/배치 결과와 고정 원본 transform 결과가 동일
- 로컬 immutable 공개·재로딩, 손상·revision 불일치·기존 버전 덮어쓰기 거부
- live mapping 순수 추가·삭제 허용, 기존 service ID 재결합·live ID 중복 거부
- 실제 HDFS의 고유 검증 경로에 전체 upload, no-overwrite rename, SHA 재대조, Consumer 재로딩

실제 HDFS 검증 경로·실행 시각·클러스터 상태는 비밀이나 내부 authority를 저장소에 넣지 않는 별도 검증
기록으로 남긴다. 검증 경로 성공은 운영 활성화를 뜻하지 않는다. Consumer의 DISCOVERY 후보 생성·로컬
정책 검증은 S15P21E106-415가 담당하고, 실제 HDFS 게시와 운영 URI 연결·재시작·rollback 검증은
S15P21E106-482에서 ALS/content와 같은 bundle `mapping_revision`, live mapping의 ID 재결합 없음 및 중복
없음을 확인한 뒤 수행한다.
