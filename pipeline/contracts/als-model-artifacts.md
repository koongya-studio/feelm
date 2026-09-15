# ALS 모델 산출물 계약

이 문서는 모델을 **어디에, 어떤 형식으로 저장하고 어떤 조건에서 읽을 수 있는지** 정의한다.
Spark ALS 학습 배치가 모델 산출물을 생성한다. 오프라인 평가·모델 선택 작업과
서비스 사용자 Fold-in Consumer가 이를 사용한다.
구체적인 저장 API, 장애 복구 코드와 테스트 절차는 각 구현에서 다룬다.

## 1. 저장 단위와 경로

한 번 학습한 모델, 사용자·영화 벡터(Factor), 메타데이터를 하나의 모델 버전으로 묶는다.

```text
<output-uri>/als/<model-version>/
├── model/           # Spark ML 네이티브 ALSModel
├── user-factors/    # 사용자 Factor Parquet
├── item-factors/    # 영화 Factor Parquet
└── metadata/        # 전체를 읽으면 정확히 한 행인 Parquet
```

- `model-version`은 실행마다 발급하는 고유 식별자이며 `^[a-z0-9][a-z0-9._-]{0,127}$`을 따른다.
  예: `als-20260908t023000z-r64-s42-a1b2c3d4`.
- 공개한 버전은 덮어쓰거나 일부만 수정하지 않는다. 재학습하면 새 버전을 만든다.
- 네 경로는 모두 디렉터리다. 파일명·파일 수·행 순서는 계약에 포함하지 않는다.
- 소비자는 지정된 버전을 읽고, 설정과 용도는 버전 이름이 아닌 메타데이터로 확인한다.

## 2. 모델과 Factor 형식

`model/`은 Spark에서 재로딩할 수 있는 ALSModel이다. v1은 원본 별점을 사용하는
명시적 평점 모델(`implicitPrefs=false`, `nonnegative=false`)이며 다음 컬럼을 사용한다.

| 역할 | 컬럼 | Spark 타입 |
| --- | --- | --- |
| 사용자 | `movielens_user_id` | `integer` |
| 영화 | `movielens_movie_id` | `integer` |
| 평점 | `rating` | `float` |

별도 Factor는 같은 모델에서 내보낸 값이며, 다음 Parquet 스키마를 따른다.

| 경로 | ID 컬럼·타입 | 벡터 컬럼·타입 |
| --- | --- | --- |
| `user-factors/` | `movielens_user_id: integer` | `features: array<float>` |
| `item-factors/` | `movielens_movie_id: integer` | `features: array<float>` |

- ID는 양수·고유하며 학습 데이터의 고유 사용자·영화 ID 집합과 일치한다.
- ID·벡터·벡터 원소에 null을 허용하지 않는다. 벡터 길이는 `rank`이고 모든 원소는 유한하다.
- 벡터는 float32 값과 원소 순서를 그대로 보존한다. 정규화하거나 다른 모델의 벡터와 섞지 않는다.
  음수 값은 허용한다. 같은 ID의 값은 저장한 ALSModel의 Factor와 일치해야 한다.
- 학습에 포함된 서비스 미매핑 영화도 보존한다. 서비스 ID 변환은 서비스 영화 ID 매핑을
  사용하는 후속 단계가 맡는다.
- MovieLens 사용자 ID와 서비스 사용자 ID는 연결하지 않는다.

null 조건은 실제 데이터 값에 대한 제약이다. Parquet을 읽은 Spark 스키마의
`nullable=true` 표시만으로 오류 처리하지 않는다.

## 3. 메타데이터

`metadata/`는 아래 컬럼을 가진 정확히 한 행의 Parquet이다.
필수 컬럼은 null을 허용하지 않으며, 건수는 실제 학습 입력과 저장 결과를 기준으로 기록한다.

| 컬럼 | Spark 타입 | 필수 여부 | 의미 |
| --- | --- | --- | --- |
| `contract_version` | `string` | 필수 | `als-artifacts-v2` |
| `model_version` | `string` | 필수 | 버전 디렉터리명과 같은 식별자 |
| `model_uid` | `string` | 필수 | 저장한 Spark ALSModel의 UID |
| `artifact_stage` | `string` | 필수 | `MODEL_SELECTION`, `FINAL_EVALUATION`, `SERVING` |
| `input_snapshot` | `string` | 필수 | 같은 입력을 다시 찾을 수 있는 불변 스냅샷 식별자 |
| `input_uri` | `string` | 필수 | 전처리된 평점·서비스 영화 ID 매핑 산출물의 루트 URI. 인증정보 포함 금지 |
| `trained_at_utc` | `string` | 필수 | 학습 완료 시각. `YYYY-MM-DDTHH:mm:ssZ` |
| `spark_version` | `string` | 필수 | 학습에 사용한 Spark 버전 |
| `rank` | `integer` | 필수 | ALS rank. 양의 정수 |
| `reg_param` | `double` | 필수 | ALS regParam. 유한한 0 이상 값 |
| `max_iter` | `integer` | 필수 | ALS maxIter. 양의 정수 |
| `als_seed` | `long` | 필수 | ALS 초기화 seed |
| `factor_dimension` | `integer` | 필수 | `rank` 및 실제 벡터 길이와 같은 값 |
| `split_method` | `string` | 분할 시 필수 | `per-user-chronological-v1` |
| `split_seed` | `long` | 분할 시 필수 | 데이터 분할 seed |
| `train_ratio` | `double` | 분할 시 필수 | 실제 사용한 train 비율 |
| `validation_ratio` | `double` | 분할 시 필수 | 실제 사용한 validation 비율 |
| `min_eval_ratings` | `integer` | 분할 시 필수 | 사용자별 최소 평가 이력 기준. 3 이상, 기본 3 |
| `source_ratings_count` | `long` | 필수 | 검증된 입력 평점 수 |
| `training_ratings_count` | `long` | 필수 | 해당 모델 학습에 사용한 평점 수 |
| `user_factor_count` | `long` | 필수 | User Factor 행 수 |
| `item_factor_count` | `long` | 필수 | Item Factor 행 수 |
| `implementation_revision` | `string` | 선택 | 실제 실행 코드를 식별하는 commit SHA 또는 소스 해시 |
| `mapping_revision` | `string` | 필수(v2) | service movie mapping 산출물의 `sha256:<64 소문자 hex>` revision |
| `movie_metadata_revision` | `string` | 필수(v2) | 영화 metadata snapshot 산출물의 `sha256:<64 소문자 hex>` revision |

- 선택 컬럼은 생략하거나 null로 둘 수 있다. 기록했다면 실제 실행 및 필수 컬럼과 일치해야 한다.
  v1의 고정값인 명시적 평점 여부·비음수 제약·float32 타입은 별도 메타데이터 컬럼으로 반복하지 않는다.
- 입력 스냅샷은 보존된 입력 데이터나 파일 목록·내용 해시 기록으로 확인할 수 있어야 한다.
  대상이 바뀌는 `latest` 같은 이름만으로 식별하지 않는다.
- `split_method`는 [사용자별 시간순 분할 규칙](../spark/batch/train/README.md)을 가리킨다.
  정책이 바뀌면 새 식별자를 사용한다. 비율은 실제 사용한 float 값을 반올림 없이 저장한다.
- 평점 건수는 `0 < training_ratings_count <= source_ratings_count`를 만족한다.
  Factor 건수는 각각 1 이상이고 학습 평점 수 이하다.

## 4. 산출물 단계

`artifact_stage`는 모델의 학습 데이터 범위와 사용 목적을 함께 나타낸다.
예시는 60건이 train 48건·validation 6건·test 6건으로 나뉜 경우다.

| `artifact_stage` | 학습 데이터 | 예시 학습 건수 | 사용 목적 |
| --- | --- | --- | --- |
| `MODEL_SELECTION` (설정 비교용) | train | 48 | validation으로 여러 학습 설정의 성능을 비교해 좋은 설정 선택 |
| `FINAL_EVALUATION` (최종 평가용) | train + validation | 54 | 선택한 설정의 성능을 test로 최종 평가 |
| `SERVING` (서비스용) | 검증된 입력 전체 | 60 | 전체 데이터로 재학습해 서비스 Consumer에 전달 |

- 단일 설정 학습 배치의 기본 산출물은 `MODEL_SELECTION`이다.
  평가용 모델 학습에는 test가 들어가지 않는다.
- `MODEL_SELECTION`·`FINAL_EVALUATION`은 다섯 분할 컬럼을 모두 기록한다.
  `SERVING`은 해당 컬럼을 정의된 타입의 null로 기록하고 두 평점 건수를 같게 기록한다.
- 모델별로 별도 버전을 발급한다. 평가 지표는 실제 평가한 버전에 귀속시키며
  전체 재학습 모델의 자체 평가 결과로 옮겨 적지 않는다.
- `SERVING`은 지정한 스냅샷 전체로 학습한 서비스용 산출물이라는 뜻이며,
  32M 검증 완료나 운영 게시 승인을 뜻하지 않는다.

## 5. 저장 완료와 소비 조건

- 작성자는 임시 경로에 `model/`, `user-factors/`, `item-factors/`, `metadata/`를 모두 저장하고,
  재로딩·스키마·건수·Factor 값의 정합성을 확인한다.
- 검증이 끝나면 임시 디렉터리 전체를 동일 파일시스템의 최종 모델 버전 경로로 한 번에
  이름 변경(rename)하여 공개한다. 소비자에게 일부만 저장된 버전이 보여서는 안 된다.
- 최종 경로의 존재 확인과 공개 사이에 다른 실행이 개입해도 기존 버전을 보존해야 한다.
  같은 버전을 동시에 공개하면 하나만 성공하고, 덮어쓰기·기존 디렉터리 아래로 이동은 금지한다.
- 파일을 하나씩 복사한 뒤 원본을 삭제하는 객체 저장소에서는 저장 중인 경로가 노출될 수 있으므로
  같은 공개 방식을 사용하지 않는다.
- `model/`, `user-factors/`, `item-factors/`, `metadata/` 각각의 `_SUCCESS`는 해당 출력만
  완료됐음을 의미한다. 모델 버전 전체가 완료됐다는 표시는 최종 버전 디렉터리의 공개다.
- 소비자는 필수 경로, 메타데이터 한 행, 지원 계약 버전, 산출물 단계, 데이터 제약을 확인한다.
  누락·불일치가 있으면 사용을 거부하며 다른 버전의 산출물로 보충하지 않는다.
- 학습 Batch는 `ALS.fit()`이 반환한 모델을 저장하며, 저장 결과를 검증할 때 해당 모델을 다시 읽는다.
- 저장된 모델을 사용하는 후속 Spark 평가 작업은 `model/`에서 ALSModel을 읽는다.
  다른 Spark 버전에서 읽으려면 호환성을 별도 검증한다.
- Python Consumer는 `item-factors/`와 `metadata/`를 읽으며 Spark 네이티브 모델 형식에 의존하지 않는다.
- v2 Consumer는 mapping·영화 metadata 산출물 전체에서 각각 하나의 자체 revision만 허용하고,
  model metadata의 두 revision과 정확히 일치할 때만 기동한다. `MATCHED` mapping의
  `service_movie_id`와 `movielens_movie_id`는 각각 one-to-one이어야 한다.
- 공개 순서는 immutable mapping·movie metadata snapshot을 검증·공개한 뒤 그 revision을 기록한
  model version을 검증·공개하는 순서다. Consumer는 세 고정 URI만 함께 읽으며 부분 공개나
  서로 다른 revision을 보충해 읽지 않는다.

파일 저장 완료 후 서비스 전환은 [스트리밍 계약](../../docs/api/streaming-contract.md#54-야간-배치-재구축)을
따른다. 데이터·품질 검증과 사용자 추천 재구축 후 서비스에 게시할 때
`model_version`·`factor_dimension`·`trained_at_utc`를 Redis의 `feelm:model:current` 키에
각각 `modelVersion`·`factorDimension`·`trainedAt`으로 기록한다. 이 키는 현재 활성 모델의
메타데이터이며 사용자별 추천 결과가 아니다. 모델 산출물 저장 단계에서는 Redis를 갱신하지 않는다.

## 6. 계약 변경

경로·타입·필수 컬럼·의미를 깨는 변경은 `contract_version`을 올리고 모델 선택·오프라인 평가·
Fold-in Consumer의 소비 코드와 적용 순서를 맞춘다. 기존 의미를 유지하는 선택 컬럼 추가는
소비자가 알 수 없는 컬럼을 무시하는 경우에만 같은 버전에서 허용한다.
