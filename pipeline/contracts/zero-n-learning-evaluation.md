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
| `totalHistoryCount` | 예측 시점 이전에 실제로 존재하는 전체 별점 이벤트 수. 통제된 prefix에서는 cohort 분석용이며 모델 입력에서 제외 |
| `providedHistoryCount` | 해당 variant에서 모델에 실제 제공한 prefix 이벤트 수. 운영 입력에서는 `totalHistoryCount`와 같음 |
| `supportedHistoryCount` | 제공된 prefix 중 선택 모델의 특징 계약으로 실제 계산 가능한 이력 수 |
| `catalogSnapshotAt` | 후보와 이력 특징이 함께 참조한 catalog snapshot 시각. `predictionAt` 이후 금지 |
| `eligiblePopulationDigest`, `targetEligible` | 공통 catalog에 사용자별 제외를 적용해 이 episode가 사용한 적격 집합과 target 포함 여부 |
| `candidateMovieIds` | 실제 노출 순서의 후보 ID. 비어 있거나 문자열이 아닌 ID 금지 |
| `candidateSupported`, `candidateRatingPredictions` | 후보별 지원 여부와 `[0.5,5]` 예측값. 미지원 값은 null |

`supportedHistoryCount <= providedHistoryCount <= totalHistoryCount`여야 한다. 통제된 prefix에서
숨긴 suffix의 `totalHistoryCount`나 통계를 모델 특징으로 넣지 않는다. 같은 prefix 뒤의 suffix만
바꿔도 모델 입력과 예측은 같아야 한다. 결측 특징을 0으로 채웠다는 이유로 지원 이력으로 세거나,
지원되지 않은 과거 영화를 더 오래된 영화로 몰래 대체하지 않는다.

보고 구간은 `K0`, `K1`, `K2`, `K3_4`, `K5_9`, `K10_19`, `K20_29`, `K30_49`,
`K50_PLUS`다. 이는 집계 구간이며 입력 cap이 아니다.

## 2. 사용자 역할과 평가 시점 정책

manifest는 사용자를 `TRAIN`, `VALIDATION`, `FINAL_TEST` 중 정확히 한 역할에 배정한다. 동일 사용자가
둘 이상의 역할에 들어가면 전체 실행을 실패시킨다. 사용자 배정 seed와 사용자 목록 또는 목록의
안전한 역할별 digest, partition algorithm/version을 결과에 남긴다. seed만 같고 배정이 달라진
실행은 같은 분할로 취급하지 않는다.

각 실행은 다음 `timePolicy` 중 하나를 선언한다.

```text
PER_USER_ANCHOR_CONTROLLED_PREFIX
GLOBAL_WINDOW_TEMPORAL_SHIFT
```

`PER_USER_ANCHOR_CONTROLLED_PREFIX`는 같은 사용자·미래 target에서 제공 이력 K만 바꾸는 정보량
효과의 주 실험이다. 사용자별 `predictionAt`을 기준으로 이력은 엄격한 과거, target은 고정된 미래로
만든다. 역할 간 전역 달력 구간이 겹칠 수 있으나 사용자는 겹칠 수 없고, 각 episode의 시간 누수는
허용하지 않는다.

`GLOBAL_WINDOW_TEMPORAL_SHIFT`는 시간 이동에 대한 별도 스트레스 테스트다. 이때는 역할별
`targetStartExclusive`, `targetEndInclusive`, `featureCutoffInclusive`를 manifest에 기록하고 창의
순서를 검사한다. 각 역할은 `targetStartExclusive < targetEndInclusive`이고
`featureCutoffInclusive <= targetStartExclusive`여야 한다. 역할 사이는
`TRAIN.targetEndInclusive <= VALIDATION.targetStartExclusive` 및
`VALIDATION.targetEndInclusive <= FINAL_TEST.targetStartExclusive`여야 한다. 실제 날짜는 dataset
snapshot을 고정하는 실행 업무에서 정한다. 두 time policy의 결과를 같은 추정량이나 동일 모집단의
반복 실행으로 합치지 않는다.

모델·특징·하이퍼파라미터와 정책 후보는 TRAIN 내부 재표집에서 선택·동결한다. 지정 VALIDATION은
동결한 후보의 confirmation에만 사용하며 그 결과를 보고 후보를 바꾸면 새 lineage와 별도 confirmation
입력이 필요하다. final-test 결과는 선택에 사용하지 않는다. 선택·확인 환경에는 final-test
episode·label·분포를 물리적으로 넣지 않고
`finalTestAccessMode=PHYSICALLY_OMITTED`와 seal ID·digest만 제공한다. 선택 입력 역할은 `TRAIN`,
confirmation 입력 역할은 `VALIDATION`이어야 한다. S15P21E106-625의 최종 평가에서만 봉인을 열 수
있으며, 그 뒤 선택을 바꾸면 새 sealed 평가로 다시 버전화한다.

### 2.1 비교군 단위 입력 동결과 분포 진단

각 결과는 `comparisonFamilyId`, `estimand`, `timePolicy`, 입력·후보 digest를 선언한다. 같은 성능
주장을 만드는 paired 비교군, 예를 들어 같은 K에서 GBT·FM·대중성 기준선이나 같은 정책 family의
임계값 비교는 동일한 사용자·episode·후보·판단을 공유한다. 비교군 안에서 모델마다 표본을 다시
뽑지 않는다.

반면 통제된 prefix 정보량 실험, 실제 저이력 외적 타당성, 전역 시간 이동 스트레스 테스트는 서로
다른 질문이다. 이들은 질문별로 버전화한 입력과 `splitDistributionReport`를 가질 수 있으며 결과를
한 paired 우위로 합치지 않는다. `supportsArbitraryN=true`인 기능 계약도 통계적으로 평가한 K 집합과
별도다.

같은 comparison family의 TRAIN·VALIDATION과 봉인된 FINAL_TEST의 생성 명세는 다음 **생성 의미**가
같아야 한다. 선택·확인 단계에서는 FINAL_TEST 원본이나 분포를 읽지 않고 seal metadata만 대조한다.

- 사용자 적격 조건, target 선택 규칙, episode 생성 수와 중복 제거
- N 구간 생성 규칙과 `totalHistoryCount`/`providedHistoryCount`/`supportedHistoryCount` 계산
- relevance threshold, 후보 적격 catalog, 관측 정답과 `UNKNOWN_SAMPLED` 처리
- TMDB·KOBIS 연결, 결측/OOV 처리와 feature snapshot 기준

분포 보고서는 접근 가능한 역할과 `NESTED_HISTORY`/`LOW_HISTORY_COHORT`별로 행·사용자·target·고유
영화 수를 기록하고 다음 축을 최소 비교한다. FINAL_TEST 분포는 S15P21E106-625에서 봉인을 연 뒤에만
추가한다.

- 사용자 전체 평가 수, episode 시점의 제공 N, 지원 이력 수와 긍정/부정 이력 비율
- target 별점·relevance 비율과 영화의 TRAIN 지원량(`0`, `1-9`, `10-49`, `50+`)
- target 개봉연도·event time, 장르·국가 등 사용한 콘텐츠 범주
- TMDB·KOBIS 및 모델 feature 연결률·결측률
- episode별 적격 후보 수, 모델 지원 후보 수와 음성 표본 수

수치축은 TRAIN에서 고정한 bin의 역할별 비율·분위수·PSI를, 범주축은 역할별 비율·최대 절대
percentage-point 차이·Jensen-Shannon divergence를 남긴다. bin, 결측 bucket, 비교 쌍과 경고 기준은
final-test를 열기 전에 S15P21E106-621의 실행 manifest에 고정한다. 표본 수가 부족해 계산할 수 없는
셀도 삭제하지 않고 `INSUFFICIENT_SAMPLE`로 남긴다.

사용자 중복, episode 시간 누수, paired 비교군의 candidate identity 불일치, 필수 label·분모·lineage
누락은 계약 위반으로 실행을 실패시킨다. 반면 자연스러운 분포 차이나 보조 구간의 표본 부족은 전체
실행을 자동 실패시키지 않고 각각 `DISTRIBUTION_SHIFT`, `INSUFFICIENT_SAMPLE`로 남긴다. 특히 실제
저이력 cohort의 표본 부족은 그 외적 타당성 주장을 제한하지만 통제된 prefix의 정보량 효과까지
무효화하지 않는다. 반대로 prefix 결과만으로 실제 신규·저이력 사용자 품질을 주장하지 않는다.

통제 가능한 차이는 비교군 안의 동일한 episode·후보 규칙으로 먼저 줄인다. 실제 영화·시점 drift는
억지로 제거하지 않고 별도 temporal-shift 층으로 보고한다. 재분배, 재가중 또는 학습 표본 변경은
TRAIN·VALIDATION만 보고 결정하고 버전화한 뒤 다시 실행한다. FINAL_TEST의 label이나 분포를 본 뒤
표본·가중치·기준을 맞추지 않는다.

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
과거 이력 prefix만 사전 선언한 정확한 `evaluatedKValues` 순서로 늘린다. 운영상 임의 N을 받을 수
있다는 기능 검증과 모든 정수 K를 통계적으로 평가했다는 주장은 다르다. 평가 grid는 K=0과 정책
임계값 후보 및 경계값을 정확히 포함해야 하지만 `0→1→2→…→N`의 모든 정수를 생성할 필요는 없다.
뒤 episode의 이벤트 ID열은 앞 episode의 완전한 prefix를 포함해야 한다. 같은 event ID의 영화,
시각, 지원 여부도 바꿀 수 없고 variant ID는 그룹 안에서 유일해야 한다. 이 평가는 같은 미래
정답에서 제공된 정보량 증가 효과를 측정한다.

### LOW_HISTORY_COHORT

실제 사용자별 `totalHistoryCount` 구간으로 모집단을 나눈다. 사용자가 서로 다르므로 K 구간 차이에
사용자 구성 차이도 포함된다. 이를 같은 사용자의 성능 개선으로 해석하지 않는다. 각 구간에 사용자,
target, 고유 영화 수와 모델 지원률을 함께 기록한다. 한 결과 artifact에 여러 구간을 long-form으로
저장할 수 있지만 `(userRole, historyReportBucket, metricId)` cell은 유일해야 하고 각 cell의 분모와
상태를 독립적으로 기록한다. 실제 저이력 표본이 부족하면 해당 cell만 `INSUFFICIENT_SAMPLE`이다.

모델이 0 이상의 임의 N을 기술적으로 처리한다는 사실은 모든 K에서 그 모델을 서비스에 사용한다는
결론이 아니다. 대중성/콘텐츠 fallback, 전 K 모델, 사전 선언한 K 이상 hard switch, 사전 선언한
K별 blend를 같은 comparison family와 후보에서 비교한다. 임계값과 blend weight는 TRAIN 내부에서
고정하고 분리된 VALIDATION에서 확인한다. `policyEvidenceStatus`가 `VALIDATED`가 되기 전에는
`CANDIDATE`/`VALIDATION_REQUIRED` 임계값을 서비스 라우팅에 사용하지 않는다.

`VALIDATED`는 적어도 `CONFIRM_COMPLETE`, 계약 검사 `PASS`, 선택 완료 전에 고정한
`acceptanceRuleDigest`와 모든 `requiredCellIds`의 `cellAcceptanceStatus=PASS`를 요구한다.
`cellDistributionStatus`와 acceptance는 별도 필드다. `INSUFFICIENT_SAMPLE`은 항상
`cellAcceptanceStatus=INDETERMINATE`로 그 cell이 포함된 `policyScope`의 검증을 막는다.
`DISTRIBUTION_SHIFT`는 자동 실패가 아니라, 해당 drift가 사전 선언한 temporal-shift estimand와
acceptance rule 안에 있을 때만 `cellAcceptanceStatus=PASS`가 될 수 있다. 그렇지 않으면 같은
모집단의 근거로 사용할 수 없다. 한 scope의 부족·drift는 다른 scope의 기술 결과를 지우지 않는다.
서비스에서 말하는 **승인된 K policy**는 여기에 더해 `FINAL_EVALUATED`와 S15P21E106-625의 적용
결정을 기록한 경우만 뜻한다.

`policyScope=INFORMATION_EFFECT`는 NESTED_HISTORY만으로 검증할 수 있지만 서비스 라우팅 근거가
아니다. `policyScope=SERVICE_ROUTING_K_THRESHOLD` 또는 `SERVICE_ROUTING_BLEND`의 required cell은
최소 다음을 포함한다.

- 사전 선언한 전환점의 양쪽 exact K(`K*-1`, `K*`; 경계 밖은 제외)와 정책이 달라지는 추가 경계
- 같은 후보·판단의 fallback-only, model-only, 제안 hard-switch 또는 blend arm
- 위 exact K의 NESTED_HISTORY와 목표 모집단의 해당 LOW_HISTORY_COHORT bucket
- 후보 Recall, `OBSERVED_HIT@L`, `OBSERVED_RECALL@L`, 저평점 노출, UNKNOWN 슬롯 비율, 지원률과 다양성. NDCG@L은
  Top-L 전부 관측 조건을 만족할 때 포함하고, 부적격이면 제외 cell과 사유를 보존한다.
- cell별 사전 표본 하한 또는 사용자 단위 CI 정밀도와 다중 K·정책 비교 규칙

실제 LOW cell을 생략하거나 MSE·MAE만으로 routing scope를 `VALIDATED`로 만들 수 없다. 목표
데이터에서 LOW 표본을 확보할 수 없으면 `INFORMATION_EFFECT` 결과와 routing 후보만 남기고 서비스
정책 검증은 `VALIDATION_REQUIRED`다.

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
fitSourceMaxEventAt, excludedEpisodeDigest, catalogVersion, lineageMode
```

- `asOf`와 `fitSourceMaxEventAt`은 `predictionAt`보다 늦을 수 없고, 역할의
  `featureCutoffInclusive`가 선언된 경우 그 시점보다도 늦을 수 없다.
- target label이나 모델 출력에서 학습한 특징은 TRAIN·VALIDATION episode에서 `OUT_OF_FOLD`여야 한다.
  fold와 fit 역할 집합은 비어 있을 수 없다. TRAIN episode의 fit 역할은 TRAIN만 허용하고,
  VALIDATION episode는 TRAIN 및 다른 VALIDATION fold만 허용한다. episode fold는 `fittedFolds`에
  들어갈 수 없다.
- 후보 인기도처럼 엄격한 과거 TRAIN event만 집계한 통계는 `TRAIN_AS_OF`를 허용한다. fit 역할은
  TRAIN만 허용하고 현재 target event, predictionAt 이후 자료, VALIDATION·FINAL_TEST를 원천에서
  제외해야 한다. source digest·cutoff·제외 규칙을 고정하며 조건을 만족하지 않으면 OOF로 생성한다.
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
| `OBSERVED_HIT@K` | 주 | 관측 relevant truth가 1개 이상인 episode. 실제 Top-K에 관측 positive가 하나라도 있으면 1, 아니면 0 |
| `OBSERVED_RECALL@K` | 주 | 관측 relevant truth 수. 분자는 실제 Top-K에 포함된 관측 positive 수 |
| NDCG | 주 | 평가할 Top-K 슬롯이 모두 관측되고 IDCG@K가 양수인 사용자. UNKNOWN 제거 후 재정렬 금지 |
| 저평점 노출 | 주 | 관측된 노출 슬롯. UNKNOWN 슬롯 비율은 별도 보고 |
| 다양성 | 주 | 요청별 실제 반환한 고유 영화. 장르/국가 등 사용한 정의를 버전화 |
| 지원률 | 주 | 요청한 전체 후보행. 모델 미지원과 정답 UNKNOWN을 분리 |
| MSE·MAE | 보조 | 모델이 지원하고 실제 별점이 관측된 후보만 포함 |

각 결과는 고정 calculator version으로 contribution을 직접 다시 만든 뒤 합산한다. 모든 지표는
`(episodeKey, metricId)` 조합마다 정확히 한 contribution 행이 있어야 한다. 선언한 각 NDCG@L은
`metricId=NDCG@L`로 구분하고 실제 출력의 첫 L 슬롯이 모두 관측되고 IDCG@L이 양수일 때만 계산한다.
후보 집합 어딘가에 관측 판단이 L편 있다는 이유로 Top-L의 UNKNOWN을 0이나 뒤의 관측 영화로
대체하지 않는다. episode contribution을 사용자 안에서 평균한 집계는 `(userId, metricId)`마다
정확히 한 행을 남긴다. 제외된 episode·사용자 지표도 같은 키에 denominator 0과 제외 사유를 남긴다.
후보 Recall은 적격 catalog digest, `targetEligible`, 관측 positive target과 candidate membership에서
재계산한다. `OBSERVED_HIT@K`는 episode마다 관측 relevant truth가 1개 이상일 때 denominator 1,
실제 Top-K에 관측 positive가 하나라도 있으면 numerator 1이다. `OBSERVED_RECALL@K`는 episode의 관측
relevant truth 수를 denominator, 실제 Top-K에 포함된 관측 positive 수를 numerator로 둔다. truth가
없으면 두 지표 모두 denominator 0과 `NO_OBSERVED_POSITIVE`를 남긴다. UNKNOWN은 음성으로 세거나
순서에서 제거하지 않는다. 각 episode 비율을 사용자 안에서 macro 평균해 `(userId, metricId)` 행을
만든다. 저평점 노출은 실제 후보 순서의 관측 슬롯, 지원률은 후보별 지원 flag, MSE·MAE는 지원 후보의
관측 별점과 `[0.5,5]` 예측값, 다양성은 실제 반환 고유 ID에서 재계산한다.
UNKNOWN을 제거한 뒤 NDCG 순서를 당기지 않는다.
최상위 `exclusionCounts`는 모든 metric contribution의 제외 사유별 합계와 정확히 같아야 한다.
각 contribution은 metric ID, episode key, contribution ID, numerator, denominator, 제외 사유별 수를
가지며 중복 contribution ID와 외부 episode를 거부한다. 비율/확률형 지표의 numerator는 denominator를
넘을 수 없다. 각 결과는 metric별 numerator, denominator, 단위, 제외 사유별 수, 사용자 수, target행 수,
고유 영화 수를 함께 저장한다. denominator는 `bool`이 아닌 0 이상의 정수여야 한다. 후보가 부족한
요청과 K=0은 결과에서 제거하지 않고 지원률·후보
Recall·부족 사유에 반영한다.

## 8. 결과 manifest

각 실행은 최소 다음을 기록한다. 실행 생명주기와 정책 판정을 한 상태로 합치지 않는다.

- `PREPARED`: 입력·계약 검사가 끝났지만 선택 결과가 없음
- `SELECTION_COMPLETE`: TRAIN 내부 선택을 동결했으며 FINAL_TEST는 `PHYSICALLY_OMITTED`
- `CONFIRM_COMPLETE`: 동결한 후보를 지정 VALIDATION에서 확인했으며 FINAL_TEST는 계속 물리적 제외
- `FINAL_EVALUATED`: S15P21E106-625에서 일치하는 seal을 한 번 열어 평가함

`PREPARED`에서 만든 `lineageId`는 상태 전이 동안 유지하고 각 새 manifest는 바로 이전
`parentResultId`를 기록한다. 같은 lineage에서 상태는 위 순서로만 전진한다. 입력·후보·설정·정책
또는 acceptance rule을 바꾸면 기존 상태를 덮어쓰지 않고 새 lineage/result ID로 시작한다.
`FINAL_EVALUATED`는 평가 실행 완료이지 정책 채택이나 서비스 승격을 자동 의미하지 않는다.

- 실행 생명주기 `PREPARED`/`SELECTION_COMPLETE`/`CONFIRM_COMPLETE`/`FINAL_EVALUATED`, 계약 검사
  `PASS`/`FAIL`, 별도의 `policyEvidenceStatus`(`CANDIDATE`/`VALIDATION_REQUIRED`/`VALIDATED`/`REJECTED`)와
  이 계약 문서 version·source revision·설정 digest·snapshot
- `lineageId`, nullable `parentResultId`, `policyScope`, `acceptanceRuleDigest`, `requiredCellIds`와 cell별
  `cellDistributionStatus`·`cellAcceptanceStatus`
- catalog, model type·model version과 feature profile ID·version·차원·dtype·ordered names hash·transform·profile digest
- 사용자 partition seed·algorithm, 역할별 사용자 digest, `timePolicy`와 해당 anchor/window metadata
- `comparisonFamilyId`, `estimand`, `supportsArbitraryN`, 정확한 `evaluatedKValues`, 입력·후보 digest
- `splitDistributionReport` version·digest, 생성 의미 대조 결과, 역할·N별 분포 지표와
  `PASS`/`DISTRIBUTION_SHIFT`/`INSUFFICIENT_SAMPLE` 상태
- 선택 입력 역할, `finalTestAccessMode`, 선택 완료 시점의 final-test 봉인 ID·digest
- 후보 모집단과 음성 표본 정책 및 각각의 내용 digest
- metric calculator version과 원시 후보·지원·예측·판단에서 재생성한 canonical contribution의
  numerator·분모·단위·제외 사유별 수
- history 보고 구간별 사용자·episode·지원 수
- `NESTED_HISTORY` 또는 `LOW_HISTORY_COHORT` 평가 종류와 각각 `USER_TARGET` 또는 `USER`
  bootstrap 단위
- 필수 보조 특징 이름, 집계 입력 digest, result ID, 계약 검사 적용 여부·episode/보조 특징/후보 판단/
  nested group/metric contribution 검사 수·오류 수 receipt. 집계 digest는 target rating·catalog snapshot,
  공통·episode별 후보 모집단, 음성 표본 정책, 사용자/anchor·window, `timePolicy`,
  `comparisonFamilyId`, `estimand`, `supportsArbitraryN`, `evaluatedKValues`, profile, 보조 특징 lineage,
  final-test access/seal, lineage·parent, acceptance rule·required cell, 실행·정책 상태, 평가
  mode·nested membership과 metric contribution을 포함한다.
  `resultId`는 이 digest로부터 만든다.

S15P21E106-622·623의 로컬 실험 실행기는 schema 결속, 역할, 선언한 time policy, 모든 episode, 보조 특징,
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
