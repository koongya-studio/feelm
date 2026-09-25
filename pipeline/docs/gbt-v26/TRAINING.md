# v26 학습 입력·특징·목표값

이 문서는 이미 학습한 `gbt-v26-top2-raw`의 재현 설명이다. 새 학습 계약이나 서빙 정책을
정하지 않는다. 현재 실행은 [추천 안내](README.md), 산출물은 [626 계약](../../contracts/gbt-model-artifacts.md)을 따른다.
근거는 2026-09-24 로컬 학습 인계 묶음의 README·원본 runner·label 규칙·manifest다.
공유 가능한 파일 식별자와 검산 결과만 [training-evidence.json](training-evidence.json)에 옮겼다.

## 학습 행과 누수 경계

| 항목 | 고정 내용 |
| --- | --- |
| 입력 X | 859,363행×71열, `float32`, C 연속 ndarray |
| 목표 y | 같은 행 순서의 `policy_target`, `float32` |
| 관측 원본 | `observed_rating`을 별도로 보존한다 |
| 학습 행 선택 | v13 자료의 `split == train`, 원본 행 순서 유지. validation 60,286행 제외 |
| 사용자 입력 | 각 target timestamp보다 **엄격히 이전**인 전체 이력. target 자신·동시각 평가는 제외 |
| K 범위 | 이 학습 자료에서 1~8,518. 서비스 입력 상한을 뜻하지 않는다 |
| 사용자 공간 | MovieLens 사용자이며 실제 FEELM 사용자 학습 행은 0 |
| 음성 표본 | 미평가 후보를 부정 예시로 추가하지 않는다 |

MovieLens 영화 ID·TMDB ID·서비스 movieId는 서로 다른 공간이다. 사용자 ID도 연결하지 않는다.
현재 TMDB/KOBIS metadata를 과거 MovieLens 관측에 결합한 회고 자료이므로,
과거 시점에 이미 공개된 정보만 사용한 온라인 예측 성능으로 주장하지 않는다.

## 71개 특징

정확한 0/1-based 열 번호·이름·타입·binary 여부는 [feature-order.csv](feature-order.csv)에 있다.
현행 [schema.py](../../consumer/src/feelm_consumer/gbt/schema.py)의 `FEATURES`와 같은 순서다.
수식은 [features.py](../../consumer/src/feelm_consumer/gbt/features.py)를 따른다.
모든 열은 `float32`이며 flag도 bool dtype 대신 0.0/1.0으로 전달한다. NaN/Inf를 전달하지 않고
0과 known/present 표시로 결측을 표현한다. 학습 직전 별도 표준화·열 재정렬은 없다.

| 0-based 열 | 내용 |
| --- | --- |
| 0 | `history_log1p`: log1p(K) |
| 1~25 | collection, director, cast, country_genre, keyword 순으로 known·pos_log1p·strong_pos_log1p·weak_pos_log1p·neg_log1p |
| 26~29 | 원별점 평균·모집단 표준편차·긍정 비율·부정 비율 |
| 30~43 | 긍정/부정 각각 series·director·cast_sources·cast_people·cast_units·units·anchor |
| 44~47 | reach_log1p·reach_known·TMDB 원평균/10·TMDB 평점 known |
| 48~55 | 긍정/부정 이력 reach의 mean_log1p·known_share·count_log1p·present |
| 56~59 | 후보 reach와 긍정/부정 이력 reach의 곱·절대차 |
| 60~69 | 5개 관계 축의 pos/neg_log1p와 후보 reach_log1p의 곱 |
| 70 | 검증 KOBIS 목록 소속 |

일반 관계는 pos≥3, strong_pos≥4, weak_pos=3~3.5, neg≤2.5점이다.
공유 token 수 대신 중복 제거한 이력 영화 수를 센다. known은 후보와 해당 축의 전체 이력이
알려져 있는지를 뜻하며, 알려진 빈 목록과 미확인(null)을 구분한다.
country_genre는 원산국(없으면 제작국)×장르 공동 관계다.

직접 근거(strict)는 ≥4점/≤2.5점 이력을 사용한다. series와 겹친 감독·배우 출처를 빼고
감독과 겹친 배우 출처도 뺀다. 배우는 원산국 교집합·장르 교집합·애니 여부 일치를 요구한다.
직접 근거의 수치 열에는 log1p를 적용하지만 anchor는 그대로 0/1이다.

```text
cast_units = min(2, cast_source_count, cast_people_count)
units = series_count + director_source_count + cast_units
anchor = series_count > 0 OR director_source_count > 0
         OR (cast_source_count >= 2 AND cast_people_count >= 2)
```

국가·장르·키워드 일반 관계만으로 strict anchor를 만들지 않는다.
학습 목표 규칙에 넣는 units는 로그 변환 전 정수 개수다.

```text
reach = max(valid_tmdb_votes, 0.0007732457348003972 * verified_kobis_audience)
reach_log1p = log1p(reach)
```

TMDB 개수는 양의 유한 정수여야 한다. TMDB 평점은 개수가 유효하고 원평균이 (0,10]일 때
알려진 값이다. KOBIS는 VERIFIED로 시작하는 연결 상태·value_valid=true·양의 유한 관객 수를
함께 요구한다. 고정 목록1,741편에 추가 관객 하한을 적용하지 않는다. 독립 TMDB 개수·precision
열은 없다. log1p는 자연로그다.

reach 이력 문맥의 별점 utility는 5→1.0, 4.5→0.8, 4→0.6, 3.5→0.35, 3→0.15,
2.5→-0.9, 2 이하→-1.1이다. 부호별 known 이력의 utility 절댓값으로 reach_log1p 평균을
가중한다. known_share는 해당 부호 전체 utility 질량 중 known 질량 비율이고 count는
known인 해당 부호 이력 영화 수다. 후보 곱/절대차는 후보 reach가 known이고 해당 부호의
known 이력이 있을 때만 계산한다.

## 학습 목표 생성

정책 식별자: `tmdb-top2pct-2294-verified-kobis-preserve-v25`.
공개 근거 또는 충분한 강한 개인 연결이 있으면 원별점을 유지하고, 둘 다 부족한 관측행의
**추천 정책 목표**만 0.5로 바꾼다. 원별점·사용자 입력·DB 평점은 바꾸지 않는다.

```python
supported = verified_kobis_member | (safe_votes >= 2294)
gap = np.where(
    supported, 0.0,
    np.maximum(d_tmdb, np.log10(2294 / np.maximum(1.0, safe_votes))),
)
required = np.where(supported, 0, 2 + np.floor(gap)).astype(np.int32)
keep = (required == 0) | (
    (positive_units >= required + 2 * negative_units) & (positive_anchor > 0)
)
policy_target = np.where(keep, observed_rating, 0.5).astype(np.float32)
```

`safe_votes`는 양의 유한 정수 개수와 (0,10] 원평균을 **모두** 요구한다. reach의
`valid_tmdb_votes`는 원평균 유효성을 요구하지 않으므로 두 값을 혼용하면 안 된다.
2,294는 포함 경계이며 실행 시 분위수를 다시 추정하지 않는다.

`d_tmdb = max(0, log10((tmdb_reference + 1) / (safe_votes + 1)))`는 고정 v12 공개근거 표의 값이다.
reference는 2026-09-21까지 개봉·Released·비성인인 영화에서 후보 자신을 제외하고,
국가×애니 여부×장르 → 국가×애니 여부 → 국가 → 전체 순으로 유효 표본30개 이상인
첫 그룹을 사용한다. 고정 tmdb_public_score 내림차순/TMDB ID 오름차순 상위10편의
safe_votes 중앙값을 구하며 후보의 여러 국가·장르 조합에서는 최댓값을 사용한다.
인계된 label 입력에 원래 d_tmdb가 있으므로 재학습을 위해 이 표를 다시 fit하지 않는다.

| 집계 | 행 수 |
| --- | ---: |
| keep=false 억제 행 | 202,103 |
| 그중 원별점부터 0.5인 행 | 2,850 |
| 목표값이 원별점과 실제로 달라진 행 | 199,253 |
| 학습에 사용한 전체 행 | 859,363 |

억제 행을 삭제하지 않고 모두 동일 sample weight로 학습한다. 이 규칙은 v26 raw 서빙의
추가 필터가 아니다. 한국어 제목 감점이나 미관측 영화의 가짜 싫어요 생성도 아니다.

## 학습 파라미터와 실행 자료

원 학습은 Python3.12.10/XGBoost2.1.4/CPU의 `XGBRegressor.fit(X, y)`다.

```python
params = {
    "objective": "reg:squarederror", "n_estimators": 80,
    "learning_rate": 0.05, "max_depth": 4, "min_child_weight": 10,
    "subsample": 0.8, "colsample_bytree": 0.8, "reg_lambda": 2,
    "tree_method": "hist", "random_state": 625, "n_jobs": 6,
}
```

나머지는 당시 XGBoost2.1.4 기본값이다. 별도 sample_weight·eval_set·early stopping·ranking
group·calibration·예측 clipping은 없다. 이름을 붙이지 않은 ndarray와 원본 행 순서를 유지한다.
원 학습의 모든 전이 의존성이 봉인된 것은 아니므로 다른 OS/환경의 재학습 바이트 동일성을
단정하지 않는다. 표준 **서빙** 환경의 정확한 고정 버전은 [644 안내](../../consumer/GBT-644.md)를 따른다.

[발표용 GBT 분산 학습 벤치마크](../../spark/batch/benchmark/GBT-README.md)는 합성71열과 label로
`SparkXGBRegressor.fit()` 시간을 측정하는 별도 실험이다. 이 v26의 학습 데이터·모델 재현이나
운영 모델 재학습 결과로 해석하지 않는다.

| 자료 | 목적·포함 범위 |
| --- | --- |
| 로컬 `v26-training-20260924` 학습 인계 | training-data.npz(X/y/observed_rating/selector_row), label-inputs.parquet, 원본 schema/model/params, verify.py·train.py·참고 소스 |
| 626 서빙 번들 | gbt.json, feature-schema.json, catalog.parquet, manifest.json, _SUCCESS의 정확5파일. 서비스 실행에는 학습 행렬이 필요하지 않음 |
| 공개 연구 저장소 | 결정·집계·해시. clone만으로 전체 v26 학습·추론 재현 불가 |

학습 인계 폴더를 별도로 받은 환경에서만 다음을 실행한다. 아래 파일은 이 저장소에 포함하지 않는다.

```bash
python verify.py
python train.py --output /new/run/gbt.json
```

verify는 읽기 전용으로 해시·shape/type·입력 시간 경계·label 계산·모델 재로드/유한 예측을 검사한다.
train은 새 출력 경로에서만 재학습하고 기존 모델의 전체 학습행 점수와 비교한다.
이번 838에서는 verify만 실행했다. 과거 인계의 원 점수 대조859,363행 오차0 기록은
새로운 재학습 성공이나 이번 실행의 독립 parity 검사로 해석하지 않는다.
원본 runner 일부가 미커밋이었으므로 Git revision만으로 연구를 복원할 수 없으며 파일 SHA와
별도 원자료가 필요하다. 원자료·사용자 입력·개인별 결과·모델은 Git/Jira 문서에 복사하지 않는다.
