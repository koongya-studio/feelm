# 모델 비교 발표 원고

**핵심 문장:** “모델 순위 하나로 결정하지 않고, 입력 편수와 영화의 학습 근거를 나누어 보았다.”

1. ALS·FM·혼합·공유 리지·GBT를 같은 평가 대상으로 비교했다. 초기 표본 편향을 감사한 뒤 지정 학습 pool의 전체 과거 이력으로 다시 비교했다.
2. 학습량 25→100% 증가의 개선은 사전 비교에서 확정하지 못했다. K30에서는 ALS의 순서가 FM·리지·GBT보다 높았지만 혼합과의 차이는 미확정이다.
3. 저활동 사용자는 4명뿐이다. MovieLens의 과거 관측을 현재 한국 서비스 정답으로 취급하지 않으며 최종 모델과 K는 보류한다.

![최종 학습량 비교](../assets/model-comparison/rec-ev-047/learning-curves.png)

보조 장표: [초기 비교](../assets/model-comparison/rec-ev-046/comparison.png), [표본 감사](../assets/model-comparison/rec-ev-046/data-audit/population-audit.png).
[수치·분모·검증 근거](../model-comparison.md). MSE는 낮을수록, 관측 영화의 선호 순서는 높을수록 좋다.
