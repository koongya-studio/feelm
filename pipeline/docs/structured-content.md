# TMDB 구조 콘텐츠의 개인화 근거

REC030에서 `STRUCTURED_DIRECT + PERCENTILE_MAGNITUDE`는 이미 평가한 영화의
별점을 가린 조건에서 개인화 신호를 보였다. 앞선 선정에 쓰지 않은 primary 사용자
3,345명·영화 24,167편으로 확인했고 사전 고정 사용자 6개·영화 4개 조건을 모두 통과했다.

| primary, 사용자 동일 가중 | HARM20 ↓ |
| --- | ---: |
| 본인 입력 OWN | 25.25% |
| 타인 입력 SHUFFLE | 33.88% |
| 무작위 RANDOM | 38.16% |

OWN의 HARM20 감소는 RANDOM 대비 12.915%p, 동시 95% 구간 [11.883, 13.947]이고,
SHUFFLE 대비 8.630%p, 구간 [7.182, 10.078]이다. 최소 Q도 각각 0.086862와
0.063112 높았다. [추론 집계](assets/content-primary-inference.json),
[종합 결과](assets/content-final-analysis.json), [독립 감사](assets/content-review.json).
ALS 표와는 평가 집합·조건이 달라 숫자로 두 모델의 우열을 비교하지 않는다.

## 실제 계산

TMDB 구조 특징은 네 그룹이다. 자유 태그나 Tag Genome을 사용한 실험이 아니다.

| 그룹 | 입력 |
| --- | --- |
| 장르 | genre ID |
| 맥락 | 원어, 개봉연도 10년 구간, 상영시간 30분 구간 |
| 인물 | 감독 ID, 미리 추린 상위 5명 배우 ID |
| 키워드 | TMDB keyword ID |

그룹별 중복 토큰을 제거한 binary multi-hot을 L2 정규화하고 각각 0.25를 곱해
이어 붙인 뒤 다시 L2 정규화한다. TF-IDF나 국가 특징은 사용하지 않는다.
특징 부적격·누락 행은 영벡터다. 원본 전체 특징 행은 85,517개이며 평가 영화 수와 다르다.

입력 별점의 상대값은 다음처럼 계산한다.

```text
q(r) = (입력 중 더 낮은 별점 수 + 동점 수/2 + 5×학습 prior[r]) / (입력 수 + 5)
w(r) = 2×q(r) − 1
score(movie) = Σ cosine(movie,input)×w(input) / Σ |w(input)|
```

prior는 학습 사용자별 별점 분포를 먼저 정규화한 뒤 동일 가중 평균한 분포의 midrank다.
많이 평가한 사용자가 prior를 지배하도록 전체 평점 건수를 합산하지 않는다.
분모가 0이거나 모든 후보 점수가 같으면 개인화 비활성으로 처리한다.
[설정·prior](../configs/structured-content.json),
[계산 참고 코드](../spark/batch/evaluation/reference.py).

## 유지할 경계

ALS 학습용 원점수, 콘텐츠 입력 가중치, 평가용 Q는 서로 다른 값이다.
이 결과는 한국 실제 사용자·신작 품질, 서비스 최종 모델, 최종 입력 K를 입증하지 않는다.
K-means/8개 팝콘 군집을 이 실험으로 검증한 것도 아니다.
기존 CSR만으로 새 영화를 변환할 수 없다. 운영에서 재사용하려면 행 ID, vocabulary,
열 순서, 변환 버전 및 미등록 토큰 처리 규칙을 함께 정의·저장해야 한다.
