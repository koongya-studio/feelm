# ALS 데이터 분할

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
uv run pytest tests/batch/test_als_splits.py
uv run ruff check batch/train/splits.py tests/batch/test_als_splits.py
```

테스트는 행 보존·중복 없는 배정, 시간순 분할, 파티션 수와 입력 순서 변경 시 재현성,
동일 시각·희소 사용자·빈 입력, 사용자/영화 cold-start 통계와 잘못된 설정을 확인합니다.
