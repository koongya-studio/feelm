# 오프라인 평가

`reference.py`는 확정 연구의 계산을 검증할 작은 참고 구현이다. 원점수 ALS Fold-in,
TMDB 구조 특징·상대 입력 가중치, 평가용 Q를 담는다. 운영 추천 Job이 아니다.

`tests/fixtures/research-reference.json`의 정답은 기존 연구 함수로 합성 데이터에서
계산했다. 평점 원자료나 사용자별 결과를 넣지 않았다.
[실행·재현 범위](../../../docs/reproduction.md)를 함께 읽는다.
