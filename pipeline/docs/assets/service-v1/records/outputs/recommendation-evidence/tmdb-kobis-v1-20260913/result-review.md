# TMDB–KOBIS v1 독립 결과 검토

상태: **PASS**<br>
검토 시각: 2026-09-13T04:39:38.1852956Z<br>
검토자: `/root/als_final_comparison_audit`

## 판정

차단 발견사항은 없다. TK371, B337, B-SD334의 분모와 국적별 수, MovieLens 원별점의 표본 표준편차, 여섯 개 수치 CSV, 보고서 표, 네 개 그림이 고정 입력과 일치한다.

이 판정은 **기존 자료의 기술통계**에만 적용한다. 현재 한국 서비스 사용자의 선호, 인과관계, 추천 성능, GBT 우위를 증명하는 결과가 아니다. 보고서에도 같은 한계가 명시되어 있다.

## 독립 재검산 결과

| 항목 | 독립 확인값 | 판정 |
| --- | ---: | --- |
| KOBIS 선택 원행 | 한국 Top200 + 외국 Top200 = 400편 | PASS |
| TK | 371편 = 한국181 + 외국190 | PASS |
| TK 중 MovieLens 양수 평가 없음 | 33편 | PASS |
| B | 337편 = 한국163 + 외국174 | PASS |
| B 중 MovieLens 평가 2개 이상 | 334편 | PASS |
| 평가 1개라 SD를 계산하지 않은 영화 | 3편, `0`이 아니라 결측 | PASS |
| MovieLens32M 원행 | 32,000,204행 | PASS |
| B337에 해당하는 원별점 | 1,660,456행 | PASS |

TK는 MovieLens 유무와 관계없이 TMDB–KOBIS 양수 유효 연결을 사용한다. B는 그중 MovieLens 평가가 있고 2023-10-13 시점 조건을 통과한 동일 영화 집합이다. 원본에서 서비스 영화 ID, TMDB ID, B의 MovieLens 영화 ID 고유성도 확인했다.

MovieLens 원본에서는 `movieId`, `rating`만 읽었다. 각 영화별로 반별점 정수 `2r`의 개수·합·제곱합을 다시 누적해 다음 표본분산을 계산했다.

`(n × Σ(2r)² − (Σ2r)²) / (4 × n × (n−1))`

337편의 개수·합·제곱합은 산출 CSV와 정확히 같았고, 기존 영화 집계의 개수·평점합과도 정확히 같았다. 334편의 SD 최대 절대차는 부동소수점 오차 수준인 `2.22e-16`이다. 사용자 ID는 읽지 않았고 개별 평점 행도 저장하지 않았다.

## 대표 수치 대조

| 수치 | 전체 | 한국영화 | 외국영화 |
| --- | ---: | ---: | ---: |
| 관객–TMDB 투표 수 Spearman | -0.0459 | 0.4030 | 0.2269 |
| TMDB 평균–관객 Spearman | 0.2321 | 0.1633 | 0.3325 |
| TMDB 평균–투표 수 Spearman | 0.2542 | 0.4494 | 0.3555 |
| 관객/TMDB 투표 중앙값 | 1,435.1 | 35,378.3 | 304.7 |
| 관객100만당 TMDB 투표 중앙값 | 696.80 | 28.27 | 3,281.66 |

영화 간 평균의 표본 SD도 보고서와 일치한다.

| 동일 가중 영화 평균 집합 | 전체 | 한국영화 | 외국영화 |
| --- | ---: | ---: | ---: |
| TK371 TMDB 10점 | 0.639 | 0.626 | 0.651 |
| B337 MovieLens 5점 | 0.445 | 0.499 | 0.388 |
| B337 TMDB÷2 5점 | 0.314 | 0.314 | 0.315 |

TMDB÷2는 10점 눈금을 5점 눈금으로 표시한 것뿐이며 사용자 집단을 보정한 값이 아니다. 영화 평균 사이의 SD는 같은 영화 안 개인별점 SD와도 다르다. B-SD334에서 영화별 개인별점 SD의 중앙값은 전체 0.956, 한국 0.863, 외국 0.998로 재현됐다.

## CSV·manifest·그림

다음 여섯 파일을 원자료와 독립 계산으로 대조했다.

- `coverage.csv`: 3×10, PASS
- `count-and-rating-relations.csv`: 19×24, PASS
- `between-film-rating-distributions.csv`: 15×14, PASS
- `within-film-ml-sd-distributions.csv`: 6×15, PASS
- `tmdb-kobis-pairs.csv`: TK371 선택·파생값, PASS
- `movielens-same-film-rating-sd.csv`: B337 충분통계·SD, PASS

CSV 저장 자릿수에 따른 최대 절대차는 `1.46e-11` 이하이며 큰 관객 수·비율의 문자열 변환에서만 나타났다. 정수 충분통계와 결측 패턴은 정확히 일치한다.

현재 manifest `352946bddffe687c58f3e026bdb6ec12d9cf1c58490ab6e5910fefa858fba302`가 가진 코드·설계·입력·출력·렌더 이력 핀 28개를 다시 해시했고 28개 모두 일치했다. 그림01 렌더 마진 수정 전후 수치 CSV 해시도 동일하다.

네 개 PNG는 제목, 한글 글꼴, 축·단위, 범례, 표본 수, 주석, 여백을 원본 크기로 확인했다. 잘림이나 수치 불일치는 없다.

## 해석 경계

보고서는 다음을 분명히 밝히므로 PASS다.

- 국적별 Top200 선택표본이며 낮은 관객 영화나 전체 카탈로그로 일반화할 수 없다.
- 연결되지 않은 29편과 MovieLens가 없는 33편을 0으로 처리하지 않는다.
- KOBIS 2026-09-13, TMDB 2026-09-09 스냅샷, MovieLens 2023-10-13까지로 시점과 참여 집단이 다르다.
- 투표 수나 관객 수를 만족도 또는 인과효과로 읽지 않는다.
- TMDB 개별 투표 분산과 KOBIS 만족도 분산은 자료가 없다고 표시한다.
- 추천 신호의 가치는 별도의 고정 조건 실험으로 확인해야 하며, 이 분석은 GBT 우위를 증명하지 않는다.

## 정확한 주요 핀

| 파일 | SHA-256 |
| --- | --- |
| `scripts/report_tmdb_kobis_v1.py` | `70b68473201c31147d8865a658b8093f4a82b77748f37e441682798dfe7ab420` |
| `DESIGN.md` | `2f50f606b5d546bd09142deaa0107371fdbcf29267fd09e9a68ba8013ae33ac1` |
| `execution-review.json` | `680b92b43a7ff81a241518e5baad0ddeb805f83390ad9b68ad7b3c803ce50a82` |
| `movie-comparison.parquet` | `9969d47da9298b736211caccefc946b131c311b1c11290eff1d3dad08c6f3c68` |
| `movie-rating-aggregates.parquet` | `b552b85ddad2a73a315ce2f176e79f6410807cbb4fa8ee241bfd8acbdca8524e` |
| `ml-32m.zip` | `e4a68655d7386b8f95f2f2424b2ff975dfdd15ffd59e0d864a14dca43e99d6ee` |
| `coverage.csv` | `55f8b56a663de25327e51492a4ee7bdc3ffcb3f4a94f5bb9f5d4ee04d3a8a3b5` |
| `count-and-rating-relations.csv` | `48fce8b412ca4bd65f53794bd1973d65c9b97711cb0a54ccd058bf0ae1ae02cd` |
| `between-film-rating-distributions.csv` | `0ae00d35a4c1c037af3aee4545e8dd8ce057e731007be0cb93574067fd14389d` |
| `within-film-ml-sd-distributions.csv` | `80276b22a4ff00b10c2de60b45538839d449650c5217e7e7a889fb0e6ebff4b4` |
| `tmdb-kobis-pairs.csv` | `ceb0bae58844ae020a61a0ae4fbca4242b041296630c73684c5f019b39d68f7f` |
| `movielens-same-film-rating-sd.csv` | `066e2b429f8141df28cf47892c8ddc5b6ea717bd9cb3b640e877f30d97cd99cb` |
| `raw-ml-sd-scan.json` | `fb8d051b8d306a39b424fa2e5f1a79337a90a2cb5c48117424600a5b1d62bc6e` |

세부 핀과 소수점 원값은 [기계 판독 검토 기록](result-review.json)에 있다.

## 비차단 후속

검토 당시 `REPORT.md`와 manifest 상태는 아직 독립 검토 대기였다. 이 PASS 영수증을 연결하는 상태 문구만 바꾼 뒤 새 보고서 본문 해시를 manifest 새 revision에 기록하고 문서 전용 최종 검토를 하면 된다. 기존 수치 CSV·코드·입력 핀은 이 영수증의 판정 기준으로 고정한다.

요청 범위의 발표 자료 `final-r4/feelm-recommendation-v1.pptx`도 6장을 확인했다. 렌더링과 숫자는 PASS이며 SHA-256은 `39d0f0140193235554447d1945733b7f3ade6a69256634ddbd887301ee77ffd0`이다. `final-r5`, `final-r6`도 존재하므로 실제 공유본은 사용할 revision을 명시하고 그 파일을 별도로 고정해야 한다.
