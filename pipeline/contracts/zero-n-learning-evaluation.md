# GBT·FM 0~N 이력 학습·평가 계약

상태: **v1 계약 확정, 학습·평가 미실행**

이 문서는 GBT와 FM의 학습 episode, 특징 lineage, 오프라인 평가 분모를 고정한다.
실제 데이터와 모델이 필요한 실행 설정과 검사 코드는 S15P21E106-622·623의 로컬 실험에 두고,
서비스 저장소의 런타임 설정이나 Spark 배포 대상에는 넣지 않는다. 기존 245열의 이름·순서·해시는
[`feature-schema.v1.json`](../configs/service-v1/feature-schema.v1.json)에 legacy 기준선으로 보존한다.

이 계약은 모델 학습 성공, MovieLens 성능 개선, 서비스 활성화를 뜻하지 않는다. MovieLens 결과는
목표 사용자와 분포가 다른 약한 오프라인 근거이며 최종 선택은 S15P21E106-625의 실사용자 평가를
통과해야 한다.

## 1. episode와 0~N 입력

추천 학습·평가 한 행의 단위는 다음과 같다.

```text
(userId, targetMovieId, predictionAt, historyVariantId)
```

- target은 `predictionAt`에서 예측할 미래 정답이다.
- 이력은 `predictionAt`보다 엄격히 이전에 발생한 실제 별점 이벤트만 포함한다.
- target 영화는 자신의 이력에 포함하지 않는다.
- 이력은 `eventId`를 포함하고 `(eventTime, eventId)` 오름차순으로 고정한다. 같은 시각의 임의
  순서에 의존하지 않는다.
- 서비스 user ID를 MovieLens user ID로 연결하거나 대체하지 않는다.
- 입력은 0 이상의 임의 N을 허용한다. 10개로 자른 입력은 별도 legacy 실험으로 표시한다.

모든 행은 두 수를 함께 기록한다.

| 필드 | 의미 |
| --- | --- |
| `totalHistoryCount` | 예측 시점 이전의 실제 별점 이벤트 수 |
| `supportedHistoryCount` | 선택 모델의 특징 계약으로 실제 계산 가능한 이력 수 |
| `catalogSnapshotAt` | 후보와 이력 특징이 함께 참조한 catalog snapshot 시각. `predictionAt` 이후 금지 |
| `eligiblePopulationDigest`, `targetEligible` | 공통 catalog에 사용자별 제외를 적용해 이 episode가 사용한 적격 집합과 target 포함 여부 |
| `candidateMovieIds` | 실제 노출 순서의 후보 ID. 비어 있거나 문자열이 아닌 ID 금지 |
| `candidateSupported`, `candidateRatingPredictions` | 후보별 지원 여부와 `[0.5,5]` 예측값. 미지원 값은 null |

`supportedHistoryCount`는 `totalHistoryCount`보다 클 수 없다. 결측 특징을 0으로 채웠다는 이유로
지원 이력으로 세거나, 지원되지 않은 과거 영화를 더 오래된 영화로 몰래 대체하지 않는다.

보고 구간은 `K0`, `K1`, `K2`, `K3_4`, `K5_9`, `K10_19`, `K20_29`, `K30_49`,
`K50_PLUS`다. 이는 집계 구간이며 입력 cap이 아니다.

## 2. 사용자 역할과 전역 시점 분리

manifest는 사용자를 `TRAIN`, `VALIDATION`, `FINAL_TEST` 중 정확히 한 역할에 배정한다. 동일 사용자가
둘 이상의 역할에 들어가면 전체 실행을 실패시킨다. 사용자 배정 seed와 사용자 목록 또는 목록의
안전한 역할별 digest, partition algorithm/version을 결과에 남긴다. seed만 같고 배정이 달라진
실행은 같은 분할로 취급하지 않는다.

세 역할은 전역 시점 창도 분리한다.

```text
TRAIN       (-∞, trainEnd]
VALIDATION  (trainEnd, validationEnd]
FINAL_TEST  (validationEnd, finalTestEnd]
```

각 역할은 `targetStartExclusive`, `targetEndInclusive`, `featureCutoffInclusive`를 manifest에
기록한다. 실제 날짜는 데이터 snapshot을 고정하는 실행 업무에서 정하며 이 계약에 임의의 날짜를
넣지 않는다. validation은 모델·특징·하이퍼파라미터 선택에 사용할 수 있지만 final-test 결과는
선택에 사용하지 않는다. 선택 입력 역할은 `TRAIN`, `VALIDATION`의 부분집합이어야 하며 선택 완료
사실과 sealed evaluation ID를 결과에 기록한다. final-test를 본 뒤 선택을 바꾸면 새 sealed 평가로
다시 버전화한다.

### 2.1 모든 실험의 분포 정합성 게이트

모든 GBT·FM 학습, 특징 제거, 하이퍼파라미터 및 기준선 실험은 학습 전에 같은
`splitDistributionReport`를 통과해야 한다. 모델마다 사용자·episode·후보 표본을 다시 뽑지 않고,
한 번 동결한 역할별 episode와 평가 후보를 모든 실험 변형이 공유한다.

TRAIN·VALIDATION·FINAL_TEST는 사용자와 시점이 분리되어도 다음 **구성 규칙**은 같아야 한다.

- 사용자 적격 조건, target 선택 규칙, episode 생성 수와 중복 제거
- N 구간 생성 규칙과 `totalHistoryCount`/`supportedHistoryCount` 계산
- relevance threshold, 후보 적격 catalog, 관측 정답과 `UNKNOWN_SAMPLED` 처리
- TMDB·KOBIS 연결, 결측/OOV 처리와 feature snapshot 기준

분포 보고서는 각 역할과 `NESTED_HISTORY`/`LOW_HISTORY_COHORT`별로 행·사용자·target·고유 영화 수를
기록하고 다음 축을 최소 비교한다.

- 사용자 전체 평가 수, episode 시점의 N, 지원 이력 수와 긍정/부정 이력 비율
- target 별점·relevance 비율과 영화의 TRAIN 지원량(`0`, `1-9`, `10-49`, `50+`)
- target 개봉연도·event time, 장르·국가 등 사용한 콘텐츠 범주
- TMDB·KOBIS 및 모델 feature 연결률·결측률
- episode별 적격 후보 수, 모델 지원 후보 수와 음성 표본 수

수치축은 TRAIN에서 고정한 bin의 역할별 비율·분위수·PSI를, 범주축은 역할별 비율·최대 절대
percentage-point 차이·Jensen-Shannon divergence를 남긴다. bin, 결측 bucket, 비교 쌍과 경고 기준은
final-test를 열기 전에 S15P21E106-621의 실행 manifest에 고정한다. 표본 수가 부족해 계산할 수 없는
셀도 삭제하지 않고 `INSUFFICIENT_SAMPLE`로 남긴다.

통제 가능한 차이는 사용자 역할의 층화 배정과 동일한 episode 표본 규칙으로 먼저 줄인다. 시간 분리로
생기는 실제 영화·시점 drift는 억지로 제거하지 않고 별도 temporal-shift 층으로 보고한다. 경고 기준을
넘은 역할을 비슷한 모집단의 주 비교라고 부르지 않으며 `DISTRIBUTION_SHIFT` 상태로 남긴다. 재분배,
재가중 또는 학습 표본 변경은 TRAIN·VALIDATION만 보고 결정하고 버전화한 뒤 다시 실행한다.
FINAL_TEST의 label이나 분포를 본 뒤 표본·가중치·기준을 맞추지 않는다.

과거 REC046에서는 사용자 전체 평가 수 중앙값이 TRAIN 71편, VALIDATION 319.5편, EVALUATION
407.5편이었고, TRAIN target의 71% 이상이 지원 50+ 영화였지만 VALIDATION·EVALUATION target의
약 60%·56%는 지원 0 영화였다. 개봉연도와 사용자 활동량도 크게 달랐다. REC047 학습 target도
K=0 5,151,574행 대 K>0 1,109,621행으로 치우쳤다. 이 수치는 새 모델의 결과가 아니라, 분포 확인
없이 평균 성능을 비교하면 안 된다는 legacy 경고다. 근거는
[`user-distributions.csv`](../docs/assets/model-comparison/rec-ev-046/data-audit/user-distributions.csv),
[`movie-distributions.csv`](../docs/assets/model-comparison/rec-ev-046/data-audit/movie-distributions.csv),
[`evaluation-data-review.json`](../docs/assets/model-comparison/rec-ev-047/evaluation-data-review.json)에 보존한다.

## 3. 같은 target 개선 곡선과 저이력 cohort

두 평가를 같은 표로 합치지 않는다.

### NESTED_HISTORY

같은 사용자, target, `predictionAt`, 후보 집합, 정답, catalog와 feature version을 유지한다. 오직
과거 이력 prefix만 `K=0→1→2→…→N`으로 늘린다. 뒤 episode의 이벤트 ID열은 앞 episode의 완전한
prefix를 포함해야 한다. 같은 event ID의 영화, 시각, 지원 여부도 바꿀 수 없고 variant ID는 그룹
안에서 유일해야 한다. 이 평가는 같은 미래 정답에서 정보량 증가 효과를 측정한다.

### LOW_HISTORY_COHORT

실제 사용자별 `totalHistoryCount` 구간으로 모집단을 나눈다. 사용자가 서로 다르므로 K 구간 차이에
사용자 구성 차이도 포함된다. 이를 같은 사용자의 성능 개선으로 해석하지 않는다. 각 구간에 사용자,
target, 고유 영화 수와 모델 지원률을 함께 기록한다. 한 결과 manifest에는 정확히 하나의
`(userRole, historyReportBucket)`만 넣어 지표 합계가 다른 구간과 섞이지 않게 한다.

## 4. 특징 namespace와 필수 의미

공통 계약은 episode·label·lineage까지다. GBT와 FM의 최종 vector를 같게 만들지 않는다. 각 실행은
`modelType`, `featureProfileId`, `featureVersion`, 차원·dtype·ordered names hash·transform을 함께
선언하고 전달한 schema에서 ordered-name hash와 profile 전체 digest를 다시 계산한다. profile digest는
ID, modelType, transform, family metadata와 feature의 이름·namespace를 포함한다. episode와 결과
manifest도 같은 feature version을 사용해야 한다. 모델별 profile 변경은 새 ID와 schema로 등록하며
이전 profile을 덮어쓰거나 legacy ID를 재사용하지 않는다.

`rh230-kobis15-v1` 245열 `float32` vector와 이름 hash
`a776529f25edff0b2f0886fcb783094ea5082b33f2a640c04006841d0364688c`는 GBT·FM의 **legacy 기준선
profile** `legacy-rh230-kobis15-v1`로만 고정한다. S15P21E106-622의 GBT 집계·관계 profile과
S15P21E106-623의 FM 희소 profile은 독립 schema·hash·transform을 가진다.

| namespace | 모델 | 의미 |
| --- | --- | --- |
| `shared.candidate` | GBT·FM | 후보 영화의 동결된 catalog 특징 |
| `shared.history_response` | GBT·FM | 엄격한 과거 실제 별점에서 계산한 사용자 반응 특징 |
| `gbt.model_input` | GBT | 선택한 GBT profile의 집계·관계 vector와 bundle의 조합 |
| `fm.model_input` | FM | 선택한 FM 희소/집계 profile과 bundle이 선언한 transform의 조합 |
| `auxiliary.learned_score` | GBT·FM | ALS·콘텐츠 점수·학습 인기도 등 별도 학습된 보조 특징 |

모든 feature family는 다음을 선언한다.

- namespace와 적용 모델
- dtype
- 결측과 실제 0의 구분
- OOV/미지원 처리
- 정규화 수식 또는 버전화된 기준
- 사용자 event time과 외부 source snapshot time
- 원천, catalog/feature/model version
- 데이터로 학습된 값인지 여부

모델 미지원 점수를 실제 0으로 넣지 않는다. `supported=false`, `unsupportedReason`, null 점수로
표현한다. FM이 추가 transform을 사용하면 train 역할에서만 적합하고 bundle에 저장한다. bundle이
transform을 선언하지 않으면 identity다.

새 custom profile은 `shared.candidate`, `shared.history_response`와 선택 모델의
`gbt.model_input` 또는 `fm.model_input`을 각각 한 개 이상의 feature로 포함해야 한다. family
metadata만 선언하고 실제 feature를 넣지 않거나, 공통 feature만으로 모델별 profile을 등록할 수 없다.

## 5. 학습된 보조 특징의 누수 방지

ALS, 콘텐츠 모델 점수, 인기도 통계처럼 데이터에서 학습·집계한 특징은 다음 lineage를 갖는다.

```text
featureName, sourceModelVersion, asOf, generationMethod,
episodeFold, fittedFolds, fittedRoles, fitSourceDigest,
fitSourceMaxEventAt, excludedEpisodeDigest, catalogVersion
```

- `asOf`와 `fitSourceMaxEventAt`은 `predictionAt`뿐 아니라 episode 역할의
  `featureCutoffInclusive`보다 늦을 수 없다.
- TRAIN·VALIDATION episode에 쓰는 학습형 특징은 `OUT_OF_FOLD`여야 한다.
- fold와 fit 역할 집합은 비어 있을 수 없다. TRAIN episode의 fit 역할은 TRAIN만 허용하고,
  VALIDATION episode는 TRAIN 및 다른 VALIDATION fold만 허용한다.
- episode fold는 `fittedFolds`에 들어갈 수 없다.
- FINAL_TEST 특징의 fit 역할은 TRAIN·VALIDATION만 허용한다.
- FINAL_TEST 행, target 정답 또는 target 이후 자료가 fit/집계 원천에 들어가면 실패한다.
- lineage가 없으면 안전하다고 추측하지 않고 해당 특징을 거부한다.

외부 인기도 snapshot도 후보 값과 이력 반응이 같은 `asOf`/catalog version을 사용해야 한다.
TMDB·KOBIS 최신값과 과거 MovieLens 정답을 섞은 결과는 사용 시점 한계를 manifest에 표시한다.

## 6. 후보 정답과 음성 표본

후보 판단의 키는 `(episodeKey, candidateMovieId)`이며 세 종류다. 서로 다른 사용자·target의 같은
영화 판단은 별도 행이다. 관측 target은 후보 생성에서 빠졌더라도 Recall 정답으로 반드시 남긴다.

| 판단 | 조건 |
| --- | --- |
| `POSITIVE_OBSERVED` | 실제 관측 별점이 relevance threshold 이상 |
| `NEGATIVE_OBSERVED` | 실제 관측 별점이 relevance threshold 미만 |
| `UNKNOWN_SAMPLED` | 미평가 후보를 계산량 때문에 표본으로 선택했지만 선호 정답은 모름 |

미평가 영화를 실제 비선호로 바꾸지 않는다. `UNKNOWN_SAMPLED`에는 별점을 넣지 않고 모집단,
제외 조건, seed, 표본 수, 표본 확률, 역확률 importance weight, 알고리즘 버전, 적격 모집단과
표본 영화 ID digest를 남긴다. 명시적 저평점과 미평가 표본의
loss 또는 지표 기여를 같은 label로 합치지 않는다.

v1 표본기는 `SHA256_THRESHOLD_V1`이다. 각 episode에서 관측·이력 영화를 제외한 적격 catalog
영화마다 `SHA256(seed + ":" + movieId) / 2^256`을 계산하고 선언 확률보다 작은 영화만 선택한다.
로컬 실험 실행기는 공통 적격 ID와 `episodeKey -> 사용자 제외 후 적격 ID 집합`, 제외 수, seed, 확률과
실제 UNKNOWN ID를 다시 대조한다. 제외 수는 관측 판단과 적격 집합에 들어 있던 이력 영화를
각각 기록한다. 서로 다른 사용자의 episode digest가 같을 필요는 없다.

후보 모집단은 서비스 연결이 검증된 적격 catalog와 평가 시점의 사용자 제외를 적용한 집합이다.
모델별 후보 생성까지 비교하는 `END_TO_END`와 같은 후보에서 재정렬하는 `SAME_POOL`을 구분한다.

## 7. 지표와 분모

| 지표 | 우선순위 | 분모·UNKNOWN 처리 |
| --- | --- | --- |
| 후보 Recall | 주 | 선언한 적격 catalog에 존재하는 관측 relevant target 전체 |
| NDCG | 주 | 관측 판단이 K편 이상이고 IDCG@K가 양수인 사용자. UNKNOWN 제거 후 재정렬 금지 |
| 저평점 노출 | 주 | 관측된 노출 슬롯. UNKNOWN 슬롯 비율은 별도 보고 |
| 다양성 | 주 | 요청별 실제 반환한 고유 영화. 장르/국가 등 사용한 정의를 버전화 |
| 지원률 | 주 | 요청한 전체 후보행. 모델 미지원과 정답 UNKNOWN을 분리 |
| MSE·MAE | 보조 | 모델이 지원하고 실제 별점이 관측된 후보만 포함 |

각 결과는 고정 calculator version으로 contribution을 직접 다시 만든 뒤 합산한다. NDCG 이외 지표는
모든 `(episodeKey, metricId)` 조합에 한 행이 있어야 한다. NDCG@2는 episode별 점수를 먼저 계산해
사용자 안에서 평균한 뒤 사용자마다 정확히 한 행을 남긴다. 제외된 지표도 denominator 0과 제외
사유를 남긴다. 후보 Recall은 적격 catalog digest, `targetEligible`, 관측 positive target과 candidate
membership에서 재계산한다. 저평점 노출은 실제 후보 순서의 관측 슬롯, 지원률은 후보별 지원 flag,
MSE·MAE는 지원 후보의 관측 별점과 `[0.5,5]` 예측값, 다양성은 실제 반환 고유 ID에서 재계산한다.
UNKNOWN을 제거한 뒤 NDCG 순서를 당기지 않는다.
최상위 `exclusionCounts`는 모든 metric contribution의 제외 사유별 합계와 정확히 같아야 한다.
각 contribution은 metric ID, episode key, contribution ID, numerator, denominator, 제외 사유별 수를
가지며 중복 contribution ID와 외부 episode를 거부한다. 비율/확률형 지표의 numerator는 denominator를
넘을 수 없다. 각 결과는 metric별 numerator, denominator, 단위, 제외 사유별 수, 사용자 수, target행 수,
고유 영화 수를 함께 저장한다. denominator는 `bool`이 아닌 0 이상의 정수여야 한다. 후보가 부족한
요청과 K=0은 결과에서 제거하지 않고 지원률·후보
Recall·부족 사유에 반영한다.

## 8. 결과 manifest

각 실행은 최소 다음을 기록한다.

- `COMPLETE` 상태, 이 계약 문서 version·source revision, 로컬 실험 설정 digest와 dataset snapshot
- catalog, model type·model version과 feature profile ID·version·차원·dtype·ordered names hash·transform·profile digest
- 사용자 partition seed·algorithm, 역할별 사용자 digest와 전역 시점 창
- `splitDistributionReport` version·digest, 구성 규칙 대조 결과, 역할·N별 분포 지표와
  `PASS`/`DISTRIBUTION_SHIFT`/`INSUFFICIENT_SAMPLE` 상태
- 선택 입력 역할, 선택 완료 시점의 final-test 봉인 ID
- 후보 모집단과 음성 표본 정책 및 각각의 내용 digest
- metric calculator version과 원시 후보·지원·예측·판단에서 재생성한 canonical contribution의
  numerator·분모·단위·제외 사유별 수
- history 보고 구간별 사용자·episode·지원 수
- `NESTED_HISTORY` 또는 `LOW_HISTORY_COHORT` 평가 종류와 각각 `USER_TARGET` 또는 `USER`
  bootstrap 단위
- 필수 보조 특징 이름, 집계 입력 digest, result ID, 계약 검사 적용 여부·episode/보조 특징/후보 판단/
  nested group/metric contribution 검사 수·오류 수 receipt. 집계 digest는 target rating·catalog snapshot,
  공통·episode별 후보 모집단, 음성 표본 정책, 사용자/창, profile, 보조 특징 lineage, 평가
  mode·nested membership과 metric contribution을 포함한다. `resultId`는 이 digest로부터 만든다.

S15P21E106-622·623의 로컬 실험 실행기는 schema 결속, 역할, 전역 창, 모든 episode, 보조 특징,
nested group, 후보 판단과 결과 manifest를 한 번에 검사한다. 계약 검사를 통과하지 못한 행을
조용히 버리고 평가를 계속하지 않는다. 실패 수와 예시 식별자를 개인정보 없이 기록하고 실행을
실패시킨다. 이 검사는 모델과 실제 입력 manifest가 준비된 뒤 실행하며, 620에서는 가짜 예측값으로
성능 통과를 주장하지 않는다.

## 9. 작업 경계

- S15P21E106-621: 데이터·장비·서버/HDFS readiness, snapshot·cutoff·seed와 인계 자료
- S15P21E106-622: GBT 로컬 학습·feature ablation과 계약 검사
- S15P21E106-623: FM 로컬 학습·상호작용 구조·ablation과 계약 검사
- S15P21E106-625: 모델 비교와 목표 사용자 평가 게이트
- S15P21E106-627: 선택된 scorer의 서비스 0~N 통합

620은 위 작업의 입력·평가 기준을 고정한다. 실제 날짜, 모델 성능, 최종 feature 채택 또는 서비스
기본 모델 선택을 선결론으로 기록하지 않는다.
