# 오프라인 평가

`reference.py`는 확정 연구의 계산을 검증할 작은 참고 구현이다. 원점수 ALS Fold-in,
TMDB 구조 특징·상대 입력 가중치, 평가용 Q를 담는다. 운영 추천 Job이 아니다.

`tests/fixtures/research-reference.json`의 정답은 기존 연구 함수로 합성 데이터에서
계산했다. 평점 원자료나 사용자별 결과를 넣지 않았다.
[실행·재현 범위](../../../docs/reproduction.md)를 함께 읽는다.

## ALS 오프라인 후보와 지표

`generate_candidates()`는 모델 Factor가 있는 평가 사용자와 영화의 조합에서 known 평점
이력을 모두 제외한 뒤 점수를 계산한다. 점수 내림차순, MovieLens 영화 ID 오름차순으로
동률을 고정한다. MovieLens와 서비스 카탈로그는 각각 필터한 뒤 Top-N을 선택하며, 서비스
후보에는 `MATCHED` 매핑만 포함한다. holdout은 후보 함수에 전달하지 않는다.

`evaluate_offline()`의 validation 호출자는 train 모델과 train known 이력, validation
holdout을 전달한다. test 호출자는 train+validation으로 다시 학습한 모델과 두 분할을 합친
known 이력, test holdout을 전달해야 한다. 동일 사용자-영화가 known과 holdout 양쪽에 있으면
누수 입력으로 거부한다.

평점 4.0 이상인 고유 사용자-영화 쌍을 기본 선호 정답으로 삼는다. Recall@10의 분모에는
학습 Factor가 없는 영화도 남고, cold-start 사용자도 선호 정답이 있으면 빈 추천의 0점으로
macro 평균에 포함된다. 서비스 지표는 `MATCHED` 정답만 사용하므로 MovieLens 지표와 모집단이
다를 수 있다. 결과에는 모집단, cold-start, 매핑 제외, 중복 제거와 후보 부족 건수가 함께
제공된다. 적격 사용자가 없으면 평균은 `None`이다.

```python
from batch.evaluation.offline import evaluate_offline

result = evaluate_offline(
    model,
    known_ratings,
    holdout_ratings,
    service_movie_map,
    stage="validation",
    candidate_size=500,
    positive_rating_threshold=4.0,
    model_version="example-v1",
)
print(result.summary)
```

이 구현은 정확성 기준을 제공하기 위해 대상 사용자와 Factor 영화의 전수 조합을 Spark에서
점수화한다. 전체 사용자·후보를 driver로 수집하거나 Python UDF로 채점하지 않지만 조합 비용은
사용자 수×영화 수에 비례한다. 32M 입력의 자원·시간과 실제 HDFS 모델 재로딩은 119번의
클러스터 통합 단계에서 검증해야 한다. 이 모듈은 결과를 저장하거나 Redis에 발행하지 않는다.
