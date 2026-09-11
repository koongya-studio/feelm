# ALS 학습 배치

## 단일 설정 모델 학습

`train_als_model()`은 검증된 학습용 평점 DataFrame, `AlsConfig`, ALS 초기화 seed를 받아
명시적 평점 ALSModel 하나를 학습한다. `rank`, `regParam`, `maxIter`, `seed`를 적용하며
MovieLens 사용자·영화 ID와 원본 별점을 사용한다.

이 함수는 데이터 분할, 서비스 영화 ID 매핑, 평가와 저장을 수행하지 않는다. 따라서 같은 함수를
train, train + validation, 검증된 전체 평점에 재사용할 수 있으며, 서비스에 매핑되지 않은 영화도
입력에 있으면 학습에 포함한다.

## 모델 산출물 저장과 로딩

`artifacts.py`는 저장·읽기, Factor 검증과 파일시스템 처리를 담당한다.
`artifact_metadata.py`는 메타데이터의 필드·타입 정의, 저장 형식 변환과 값 검증을 담당한다.

`save_als_artifacts()`는 ALSModel, User/Item Factor와 메타데이터를 임시 경로에 저장하고
재로딩 검증이 끝난 버전만 `<output-uri>/als/<model-version>/`에 공개한다. 이미 존재하는
모델 버전은 덮어쓰지 않는다. 최종 버전 디렉터리의 존재가 저장 완료를 의미한다.

`load_als_artifacts()`는 네 경로를 다시 읽고 계약 버전, 메타데이터, Factor 스키마·건수,
ALSModel과 별도 Factor의 일치 여부를 검사한다. 로컬 경로, `file://` URI와 `hdfs://` URI를
지원하며 경로 해석은 Spark의 Hadoop 파일시스템 설정을 따른다.

최종 공개 시 Linux 로컬 디스크에서는 부모 디렉터리 잠금으로 존재 확인과 rename을
보호한다. 이 API를 사용하는 스레드·프로세스가 동시에 저장해도 한 작업만 성공한다.
잠금은 공개 구간에만 적용하고 종료 시 해제하며, 잠금 파일을 남기지 않는다.
HDFS에서는 덮어쓰기를 금지한 `FileContext.rename(..., Rename.NONE)`을 사용한다.
객체 저장소와 로컬 경로로 마운트한 네트워크 파일시스템은 지원 대상으로 보지 않는다.

## 데이터 분할

`split_ratings()`는 입력 검증을 통과한 평점 DataFrame을 사용자별 시간순으로
`train`, `validation`, `test`에 배정합니다. 사용자·영화 ID는 MovieLens ID입니다.
입력에는 양의 정수 ID, 초 단위 `rated_at_epoch`가 있어야 하며 사용자–영화 조합은
고유해야 합니다. null·타입·중복 등 입력 검증은 호출 전에 수행합니다.

## 분할 규칙

- 기본 비율은 학습 0.8, 검증 0.1, 테스트는 나머지입니다.
- `rated_at_epoch` 오름차순으로 정렬합니다. 동일 시각은
  `xxhash64(seed, movielens_user_id, movielens_movie_id)`, 영화 ID 순으로 정렬합니다.
  기본 seed는 42이며 입력 순서와 Spark 파티션 수가 바뀌어도 같은 행을 같은 그룹에 넣습니다.
  seed는 동일 시각 순서에만 영향을 줍니다. 해시 충돌은 영화 ID로 해소합니다.
- 사용자 평점 수가 `min_eval_ratings`(기본 3) 미만이면 전체를 학습에 남깁니다.
  이 사용자는 검증·테스트에 등장하지 않지만 원본 평점은 삭제하지 않습니다.
- 그 외 사용자에게는 세 그룹에 최소 1개씩 배정합니다. 학습 수는
  `min(max(floor(n × train_ratio), 1), n - 2)`, 검증 수는
  `min(max(floor(n × validation_ratio), 1), n - 학습 수 - 1)`이며 나머지는 테스트입니다.
  비율을 십진수의 정확한 분자·분모로 변환하고 정수 곱셈과 나눗셈으로 계산해,
  부동소수점 오차와 Spark Decimal 정밀도 축소에 의한 경계 반올림을 피합니다.
- 평점의 높고 낮음, 서비스 매핑 여부로 배정을 바꾸거나 행을 제거하지 않습니다.
  학습에 없는 영화와 선호 정답이 없는 사용자의 평가 처리는 후속 평가 단계에서 결정합니다.

| 사용자 평점 수 | 학습 | 검증 | 테스트 |
| --- | --- | --- | --- |
| 1 | 1 | 0 | 0 |
| 2 | 2 | 0 | 0 |
| 3 | 1 | 1 | 1 |
| 9 | 7 | 1 | 1 |
| 10 | 8 | 1 | 1 |
| 11 | 8 | 1 | 2 |

사용자별로 학습 시각 ≤ 검증 시각 ≤ 테스트 시각을 보장합니다. 같은 초의 평점 사이에는
실제 선후관계를 알 수 없어 정렬 규칙으로 배정합니다. 사용자마다 기준 시점이 다르므로
다른 사용자의 미래 행동 유입까지 막는 전역 시간 분할은 아닙니다. 지표 해석 시 이 한계를
고려해야 합니다. 또한 평점이 적은 사용자의 학습 보존은 서비스에서 인기·콘텐츠 추천으로
보완할지 결정하는 정책과 별개입니다.

## 사용과 통계

Spark 프로젝트 루트(`pipeline/spark`)에서 검증된 `ratings`에 다음 함수를 적용합니다.

```python
from dataclasses import asdict
from batch.train.splits import split_ratings, summarize_splits

splits = split_ratings(ratings, train_ratio=0.8, validation_ratio=0.1, seed=42)
splits.assignments.persist()
try:
    report = summarize_splits(splits)
    print(asdict(report))
    train, validation, test = splits.train, splits.validation, splits.test
    # 세 DataFrame을 사용하는 Spark 작업은 unpersist 전에 실행합니다.
finally:
    splits.assignments.unpersist()
```

`assignments`에는 원래 컬럼과 `split` 컬럼이 있고, 세 분할 DataFrame에는 원래 스키마가
유지됩니다. 반환된 DataFrame의 물리적 행 순서는 보장하지 않습니다.

통계에는 보존한 전체 평점 수, 학습에만 남은 사용자 수, 분할별 평점·사용자·영화 수,
학습에 없는 사용자·영화 수와 이들이 포함된 평점 수(`cold_start_ratings`)가 포함됩니다.
영화 수는 고유 ID 수이며 평점 수와 구분합니다. 검증과 테스트 모두 `train`과 비교합니다.
`train + validation` 재학습 이후의 테스트 평가 대상은 후속 평가 단계에서 다시 판단합니다.
함수는 통계를 반환하며 출력 파일을 쓰거나 모델을 학습하지 않습니다.

## 검증

```bash
cd pipeline/spark
uv run pytest tests/batch/test_als_artifacts.py tests/batch/test_als_training.py tests/batch/test_als_splits.py
uv run ruff check batch/train tests/batch
```

산출물 테스트는 로컬 저장·재로딩과 예측값 유지, 기존 버전 보호, 동시 공개,
실패한 임시 결과 정리와 손상된 산출물 거부를 확인합니다.
학습 테스트는 ALS 파라미터 전달, 실제 모델 생성과 서비스 미매핑 영화 Factor 보존을 확인합니다.
분할 테스트는 행 보존·중복 없는 배정, 시간순 분할, 파티션 수와 입력 순서 변경 시 재현성,
동일 시각·희소 사용자·빈 입력, 사용자/영화 cold-start 통계와 잘못된 설정을 확인합니다.

### 실제 HDFS 저장소 검증

로컬 테스트 통과는 실제 HDFS 검증 완료를 뜻하지 않는다. HDFS 접근이 가능한 서버에서
아래 절차를 별도로 실행한다. 작은 합성 평점 60개로 저장 API를 검증하며,
32M 전체 학습·분산 ALS 실행·Redis 서비스 전환은 검증하지 않는다.

1. [파이프라인 실행 환경](../../../README.md)에 맞는 Python·Java·PySpark ML과 테스트 의존성을
   준비한다. 실행 프로세스에서 NameNode와 DataNode에 접근할 수 있어야 하며,
   Spark의 Hadoop 설정과 HDFS 쓰기 권한을 확인한다. 검증 client가 NameNode의 기본 복제
   수를 자동으로 상속한다고 가정하지 않는다. 쓰기 전에 승인된 복제 수가 유효 설정인지
   확인하고 실행 명령에 명시한다.
2. 아래 URI의 주소·경로와 `<APPROVED_REPLICATION>`을 담당자가 승인한 **검증 전용 HDFS
   값**으로 바꾼다. 운영 모델 경로는 사용하지 않는다. 테스트마다 `als-check-<UUID>` 하위
   경로를 생성한다.
3. 저장·재로딩, 중복 저장, 저장 중 실패, 동시 공개 테스트 6개를 실행한다.

```bash
# 저장소 루트에서 실행. URI는 실제 검증 환경 값으로 교체한다.
cd pipeline/spark
ALS_ARTIFACT_TEST_ROOT='hdfs://<NAMENODE_AUTHORITY>:8020/user/feelm/als-verification/RUN_ID' \
PYSPARK_SUBMIT_ARGS='--conf spark.hadoop.dfs.replication=<APPROVED_REPLICATION> pyspark-shell' \
  uv run pytest tests/batch/test_als_artifacts.py -v -s \
  -k 'complete_artifact_version or overwrite_existing or
      temporary_version_after_write_failure or concurrent_publication'
```

`ALS_ARTIFACT_TEST_ROOT`는 테스트 전용 입력이며 서비스 환경변수나 배치 설정이 아니다.
미지정 시 임시 로컬 디렉터리를 사용한다. 테스트의 Spark 실행 모드는 `local[1]`이지만,
위 명령의 저장·읽기·rename 대상은 지정한 실제 HDFS다.

| 검증 | 기대 결과 |
| --- | --- |
| 저장·재로딩 (3개 단계) | 최종 경로에 `model/`, `user-factors/`, `item-factors/`, `metadata/`만 존재. 사용자 6개·영화 14개 Factor, 메타데이터와 재로딩 전후 예측값 일치 |
| 같은 버전 재저장 | `FileExistsError` 발생, 기존 모델 유지 |
| 메타데이터 저장 실패 주입 | 최종 버전과 해당 임시 경로 없음. 로딩은 `FileNotFoundError`로 거부 |
| 같은 목적지로 동시 공개 (30회) | 매회 한 작업만 성공. 실패 작업의 폴더가 최종 디렉터리 안에 끼어들지 않음 |

실패 주입은 테스트 내부에서만 예외를 발생시킨다. HDFS 중단·권한 변경을 수행하지 않는다.
동시 공개 테스트는 학습을 반복하지 않고 최소 디렉터리로 실제 rename 구간을 검증한다.

실행 커밋, Python·Java·Spark 버전, HDFS URI와 **6 passed** 결과를 검증 기록으로 남긴다.
출력의 `artifact verification output:` 경로로 결과를 확인할 수 있다. 검사 후에도 성공 산출물과
동시 공개 테스트의 패배한 임시 디렉터리는 확인용으로 남는다. 정리는 담당자가 이번 실행의
정확한 검증 경로를 확인한 뒤 수행하며, 상위 공용 경로는 삭제하지 않는다.
