# 콘텐츠 오프라인 산출물 계약

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
