# GBT 모델 서빙 산출물 v1 (S15P21E106-626)

## 1. 경계와 점수

`gbt-model-artifacts-v1`은 625에서 채택한 v26의 **원점수**를 재현하는 내부 데이터 계약이다.
Backend API·DB·Redis·Kafka 형식은 바꾸지 않는다. 개인 전체 후보와 파티 지정 후보가 동일한
71특징·트리를 사용한다. 파티 집계, 발견 공급, K0 추천은 호출부 업무다.

- 점수 의미: `RECOMMENDATION_POLICY_SCORE_NOT_EXPECTED_STARS`. 예상 별점·확률이 아니다.
- 정렬: 점수 내림차순, 동점은 `service_movie_id` 오름차순.
- 공개 근거: `log1p(max(valid_tmdb_votes, 0.0007732457348003972 * verified_kobis_audience))`.
  TMDB 원평균/10과 유효 여부, 검증 KOBIS 목록 소속은 유지한다. TMDB 개수 독립 특징은 없다.
- 2,294표/학습 정답 억제는 학습 출처다. **추론 품질 필터·투표 수 컷·언어 컷은 없다.**
- 원본의 고정 개봉 후보 `eligible_movie`만 보존한다. 매일 날짜를 바꾸거나 subset에서 정규화하지 않는다.

## 2. 불변 버전과 파일

버전 디렉터리에는 정확히 다음 5개 파일만 둔다. 하위 디렉터리·링크·실행 파일은 허용하지 않는다.
운영 파일시스템 권한은 게시 후 읽기 전용으로 제한한다. loader는 신뢰된 제어 경로로 전달한
`expected_revision`을 필수로 받는다. 같은 폴더에서 계산해 무조건 믿는 방식은 운영 pinning이 아니다.

| 파일 | 내용 | byte 상한 |
| --- | --- | --- |
| gbt.json | XGBoost CPU JSON, 71열 single-target gbtree reg:squarederror | 8 MiB |
| feature-schema.json | features 순서, coefficient, policy_version, score_semantics | 64 KiB |
| catalog.parquet | §3의 정적 정보·매핑·개봉 후보 mask | 256 MiB |
| manifest.json | 아래 식별/출처/inventory | 1 MiB |
| _SUCCESS | `{"manifest_sha256":"<hex>"}` 완료 표지 | 1 KiB |

제어 JSON은 UTF-8(BOM 없음), key 정렬, 공백 없는 구분자, ensure_ascii=False,
NaN/Infinity 금지, 끝 LF 1개로 직렬화한다. 중복 key·비정규 직렬화를 거부한다.
모델 JSON은 원본 바이트를 그대로 유지한다.

`servingRevision = sha256:<manifest.json 실제 바이트 SHA256>`.
manifest는 자기 해시를 포함하지 않고 `_SUCCESS`가 해당 해시를 가리킨다.

manifest 필드:

- `contract`, `model_version`(소문자 영숫자로 시작, 영숫자/점/밑줄/하이픈, 최대50자),
  `transform=v26-max70-kobis-flag-71-v1`, `score_semantics`.
- `runtime`: Python major.minor, numpy/pyarrow/scipy/xgboost 정확 버전. SciPy는 XGBoost의 전이 의존성이다.
- `code_revision`: 설치된 `feelm_consumer/gbt/*.py` 이름→내용 SHA 사전의 canonical JSON SHA.
  소스 CRLF만 LF로 정규화한다. 번들에 코드/pickle을 넣거나 실행하지 않는다.
- `mapping_revision`: service ID 오름차순 `(service_movie_id,tmdb_id)` little-endian int64 Nx2 바이트 SHA.
- `catalog_rows`, `files`: 데이터3파일 이름→`bytes,sha256`. 임의 상대경로/절대경로를 받지 않는다.
- `input_contract=all-k-actual-first-dismiss-1-explicit-exclusions-v1`.
- `provenance`: `input_snapshot`, `candidate_cutoff`(YYYY-MM-DD), `training_rule`, `source_sha256`.
  출처는 경로나 사용자 정보 대신 짧은 식별자·SHA만 기록한다.

manifest가 묶은 코드/런타임/매핑/파일/71특징이 하나라도 다르면 시작을 거부한다.
파일 재배치만으로 새 revision이 되지 않으며, 하나라도 바꾸면 새 버전 디렉터리에서 재검증한다.

## 3. 최소 영화 스키마와 ID

정확 타입·순서는 Consumer `gbt/schema.py::CATALOG_SCHEMA`와 대응한다.

| 필드 | Arrow 타입 / 의미 |
| --- | --- |
| service_movie_id, tmdb_id | non-null positive int64, 각각 유일, service ID 오름차순 |
| collection_ids, director_ids, top5_cast_ids, genre_ids, keyword_ids | nullable list<int64>; null=미확인, []=알려진 빈 집합 |
| origin_country_codes, production_country_codes | nullable list<string>, 2자 대문자 국가 코드 |
| tmdb_vote_count, tmdb_vote_average | nullable float64, 원본 유효성 mask를 계산 |
| kobis_movie_code, kobis_link_status | nullable string, VERIFIED* 행은 8자리 확정 코드 필수 |
| kobis_value_valid | nullable bool |
| kobis_audience_cumulative | nullable float64 |
| eligible_movie | non-null bool, 고정 개봉 후보 mask |

최대500,000행·Parquet 비압축512MiB·한 목록1024개. 목록 내 null/중복·비양수 ID는 거부한다.
공개 수치의 null/비유한/잘못된 값은 frozen v26 유효성 규칙으로 결측 처리하며 임의로 고치지 않는다.
검증 KOBIS: VERIFIED* + value_valid + 양의 유한 관객 수. 이 목록을 다시 관객 최소치로 걸러내지 않는다.

현재 서비스 export의 **정확한 ID쌍**을 비교한다. 제목·연도 매칭, ID namespace 동일시 금지.
순수 추가·삭제는 지원률로 별도 보고, 기존 service ID의 다른 TMDB 재결합·중복 매핑은 활성화 실패다.
빈 live export도 감사 실패이며 활성화 가능으로 반환하지 않는다.
신규 미지원 입력/후보는 다음 검증 번들까지 typed 오류이며 0특징이나 일부 입력으로 대체하지 않는다.
삭제된 live 영화의 제외/최종 DB 제공 가능성 확인은 호출부가 담당한다.

## 4. 모든 입력 K와 동일 점수

`Scorer.score(ratings, dismissed_movie_ids=(), excluded_movie_ids=(), candidate_ids=None)`:

- ratings: `(service_movie_id, score)` 쌍. 양의 signed64 ID, 0.5~5.0 반점만 허용.
  bool/문자열/NaN/중복 실제 평점은 오류. 입력 순서·시간 가중·최근 N개/K 상한 없음.
- 실제 평가가 있으면 항상 실제 값. 실제 평가 없는 관심없음만 계산상1.0점, DB에 쓰지 않는다.
- 실제 평가·관심없음 영화 자체와 `excluded_movie_ids`를 후보에서 제외한다.
  TASTE 감상-only 자동 제외를 도입하지 않는다. PARTY/DISCOVERY 공통 감상 제외는 호출자가 전달한다.
- K0은 `NEEDS_COLD_START`, 빈 후보/점수. 미지원 이력은 `UNSUPPORTED_HISTORY`와 지원 건수 오류이며
  K0과 다르다. 미지원 후보는 `UNSUPPORTED_CANDIDATE`, 개봉 미적격 지정 후보는 `INELIGIBLE_CANDIDATE`.
- country×genre 공동 관계와 감독·시리즈·출연진·키워드 관계를 사용한다. 3점은 약한 긍정,
  2.5점 이하는 부정. 배우 직접 근거는 국가·장르·애니 여부 일치, 중복 시리즈/감독 근거를 제외한다.
- 반환: status, model_version, serving_revision, input/actual/dismiss_count, movie_ids, scores.
  제외 후 빈 후보는 정상 `OK` 빈 배열. 오류 시 부분 결과를 반환하지 않는다.
- 각 요청의 특징 상태는 독립이다. 한 번 로드한 모델/영화 정적 정보로 반복 계산한다.
  전체/부분 후보 점수 오차≤1e-6, frozen Top100/500은 ID·순서 완전 일치해야 한다.

### 4.1 GBT local attribution 근거 v1 (S15P21E106-824)

K≥1의 개인 GBT TASTE 상위 500편은 원점수를 계산한 같은 `Booster`와 같은 71열 행렬로 exact
TreeSHAP(`pred_contribs`, `approx_contribs=false`)을 계산한다. bias를 포함한 72개 기여 합이 방금 게시할
원점수와 `rtol=1e-6`, `atol=1e-6` 안에서 일치해야 하며 shape·유한성·점수 결속 중 하나라도 실패하면
해당 세대 전체 게시를 실패시킨다. attribution 때문에 후보 점수·정렬·제외를 바꾸지 않는다.

근거 계약 버전은 다음 식별자를 함께 고정한다.

- `explanationVersion=gbt-feature-attribution-v1`
- `featureVersion=v26-max70-kobis-flag-71-v1`(bundle manifest의 `transform`)
- `rankingVersion=gbt-personal-raw-score-v1`
- 같은 bundle의 `modelVersion`·`servingRevision`
- 같은 불변 사용자 입력의 `inputVersion`(`gbt-input-v1` snapshot `inputHash`)
- Candidate v6의 같은 `generationId`, 후보 `movieId`와 원점수

raw feature 이름·token·배우·감독 ID는 게시하지 않는다. bias와 결측/내부 보조 feature인
`*_known`, `*_known_share`, `*_present`, `*_anchor`, `verified_kobis_member`도 공개 근거에서 제외한다.
나머지 양의 기여만 다음 사용자 설명 그룹으로 합산한다.

| 그룹 | 포함 신호 |
| --- | --- |
| `GENRE_PATTERN` | 국가×장르 평가 관계와 그 도달도 교차 |
| `RATING_PATTERN` | 이력 규모·평균·표준편차·양/음 평가 비율 |
| `CONTENT_RELATION` | 시리즈·키워드와 비식별화한 감독·출연진 관계 집계 |
| `POPULARITY_SIGNAL` | 공개 도달도와 사용자 이력 도달도 관계 |
| `AUDIENCE_RATING_SIGNAL` | 유효 TMDB 원평점 |

현재 71특징에 없는 개봉 시기·상영시간은 모델 기여도로 만들지 않는다. 해당 근거가 필요하면 특징 계약과
모델을 별도 버전으로 재학습·검증한 뒤 새 serving revision에서만 추가한다.

그룹 양의 기여 내림차순, 동률이면 그룹 이름 오름차순으로 최대 3개를 게시한다. 각 coverage는
`그룹 양의 기여 / bias를 제외한 71개 raw feature 전체 양의 기여`이고 후보 coverage는 게시한 그룹
coverage의 합이다. 따라서 설명 불가 양의 기여가 있으면 coverage가 1보다 작을 수 있다. evidence ID는
explanation/model/serving/feature/ranking/input version, movie ID와 그룹의 length-prefix UTF-8 SHA-256이다.
같은 모델·입력·후보 재실행에서는 generation이 달라도 같고, 결속 식별자 하나라도 바뀌면 달라진다.

Candidate v6와 기존 `reasons[]`는 변경하지 않는다. 결과는
`feelm:user:{userId}:gbt-evidence:{generationId}`의 별도 strict JSON snapshot으로 입력·상태·Candidate와
같은 fenced Lua에서 원자 게시한다. canonical JSON은 최대 2 MiB이며 초과 시 해당 세대 전체 게시를
실패시킨다. attribution builder가 만든 깊은 불변 snapshot만 프로세스 내부 seal 검증을 통과해 게시할 수
있으며, builder 뒤 contribution·coverage가 바뀐 객체는 Redis 호출 전에 거부한다. 내부
`gbt-evidence-current` identity 포인터는 evidence만 남은 부분 유실에서도 직전 generation의 보존 TTL을
복구하며 Backend 공개 계약에는 포함되지 않는다. K0 인기순, GBT 오류·미지원, 빈 결과, DISCOVERY, ALS·fallback에는
후보별 GBT attribution을 만들지 않고 빈 `items`를 게시하거나 결과 세대 자체를 게시하지 않는다.
Backend 소비와 문구 생성은 S15P21E106-825, 실제 shadow·활성화 승인은 S15P21E106-628 범위다.

### 4.2 GBT 구체 표시 근거 v2 (S15P21E106-840)

`gbt-evidence-v2`와 `gbt-feature-attribution-v2`는 v1의 그룹 기여·coverage·최대 3개·2 MiB·원자 게시
규칙을 유지하고, 해당 그룹의 양의 모델 기여를 사용자 입력까지 직접 추적할 수 있을 때만 다음 선택 필드를
evidence 항목에 추가한다.

| 필드 | 허용 그룹 | 의미·생성 조건 |
| --- | --- | --- |
| `genreCode` | `GENRE_PATTERN` | 후보와 실제 4.0 이상 평가 영화가 공유하는 양의 TMDb 장르 ID를 DB ingestion과 같은 `TMDB_<genre_id>`로 표현한다. 평점 내림차순·영화 ID·장르 ID 오름차순으로 하나를 고른다. |
| `sourceMovieId` | `CONTENT_RELATION` | 후보와 collection/director/cast/keyword 중 모델 입력에 사용한 직접 관계를 공유하는 실제 4.0 이상 평가 서비스 영화다. 평점 내림차순·영화 ID 오름차순으로 하나를 고른다. cast는 모델과 같은 국가·장르·애니메이션 일치 조건도 만족해야 한다. |

관심없음의 계산상 1.0점, 4.0 미만 실제 평가, 후보와 관계없는 영화는 구체 출처가 아니다. 장르·원천 영화가
없어도 v2 그룹 근거 자체는 유지하지만 소비자는 일반 그룹 문구로 fallback한다. raw feature token과 장르
숫자 외 배우·감독·키워드·시리즈 ID는 게시하지 않는다.

evidence ID의 length-prefix SHA-256 입력에는 v1 식별자에 `genreCode`(없으면 빈 문자열)와
`sourceMovieId`(없으면 빈 문자열)를 추가한다. 따라서 구체 출처를 추가·삭제·변조하면 ID가 달라진다.
Backend는 strict v1과 strict v2를 함께 읽되 버전 쌍을 섞지 않는다. v2 장르는 `genres.code/name`, 원천 영화는
추천 항목 소유 사용자의 실제 `ratings.score >= 4.0`과 `movies.title`을 다시 검증한 뒤 Gemini 입력에 넣는다.
검증 실패 시 구체 주장을 버리고 일반 그룹 문구를 사용한다.

전환은 Backend의 v1/v2 동시 수용을 먼저 배포하고 Pipeline v2 게시를 나중에 활성화한다. 롤백하거나 기존
snapshot TTL이 남아 있는 동안 Backend는 v1을 계속 소비한다. Pipeline v2 활성화 뒤 v1-only Backend로
되돌리지 않는다.

## 5. 게시·복원과 검증 계층

로컬 exporter는 전용 임시 디렉터리에 쓰고 전체 검증 뒤 atomic no-replace rename한다.
동시 게시·기존 빈 디렉터리도 덮어쓰지 않는다. Windows와 Linux만 지원하며 그 외는 명시 실패다.
실패 시 자기 staging만 정리하고 기존 final은 보존한다.

HDFS는 기존 Spark `jobs.batch.artifact_transfer`의 manifest→staging 검증→no-overwrite 게시를 재사용한다.
전송 manifest는 완료 표지와 bundle manifest까지 **5개 전체**를 포함해야 한다.
`--skip-sha256-verify`는 사용하지 않는다. 실제 목적지/권한·replication·게시 승인은 별도다.
Consumer는 Spark 코드를 import하지 않는다. HDFS loader는 기존 이미지의 libhdfs 구성이 필요하다.

잘못된 새 bundle은 새 scorer 객체를 반환하지 않는다. 호출자는 이전 검증 URI/revision을 명시해서
다시 로드하고 검증한다. 자동 ALS fallback·모델/catalog 혼합·활성 포인터 자동 수정은 없다.
로컬/Linux fixture 성공은 실제 HDFS 권한·네트워크·클러스터·서버 처리량을 입증하지 않는다.


### S15P21E106-959 추천 방식 보존

개인 후보와 상태, 파티 성공 결과는 `recommendationMode` (`PERSONALIZED` 또는 `POPULARITY`)를 게시한다. 개인은 지원 입력 수 0인 cold start, 파티는 모든 참여자의 유효 입력이 없을 때만 `POPULARITY`다. 파티는 기존 후보 합집합과 제외 조건을 유지하고 검증된 `reach_log1p` 내림차순·영화 ID 오름차순으로 정렬한다. 빈 후보는 `EXHAUSTED`, 잘못된 feature와 게시 실패는 기존 실패 계약을 유지한다. 게시 스냅샷 재생은 추천 방식까지 그대로 복원한다. Backend의 두 방식 읽기 배포 후 새 Consumer 게시를 활성화한다. 구 payload의 필드 부재는 레거시 처리이며 이미 노출된 개인 reason을 재해석하지 않는다.
