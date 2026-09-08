# Fold-in Consumer

서비스 Consumer 구현 위치다. 기존 Python 3.12 런타임 경계를 유지한다.
실제 모델 로더·Kafka·Redis·추천 정책 연결은 S15P21E106-199에서 구현한다.

원별점 Fold-in 계산 참고와 검증 fixture는
[`spark/batch/evaluation/reference.py`](../spark/batch/evaluation/reference.py),
[`spark/tests/batch/test_research_reference.py`](../spark/tests/batch/test_research_reference.py)에 있다.
현재 Consumer가 Spark 프로젝트 코드를 import하는 구조를 도입한 것은 아니다.
서비스 구현 시 계산을 옮기고 같은 fixture로 대조한다.

모델을 읽을 때는 [공식 ALS 산출물 계약](../contracts/als-model-artifacts.md)을 따른다.
연구용 item-only NPZ와 운영용 Parquet 계약을 혼용하지 않는다.
