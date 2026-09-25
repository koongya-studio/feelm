# v26 GBT 추천: 현재 기준과 문서 안내

기준일: 2026-09-25. Jira: [S15P21E106-838](https://ssafy.atlassian.net/browse/S15P21E106-838).
코드 대조 기준: `6892d120`(`origin/develop`). 이 문서는 이미 반영된 구현과 확정 결정을
연결하는 안내다. **v26 GBT는 서버 적용까지 완료된 상태다(2026-09-25 사용자 확인).**
학습·점수 계산·개인/파티 서빙을 다루며, 계약 원문은 아래 기준 문서를 따른다.

## 읽는 순서

| 확인할 내용 | 기준 문서 |
| --- | --- |
| 선택 이유·품질 검산·한계 | [625 연구 마감 인계](../gbt-transition/README.md)와 당시 [publication.json](../gbt-transition/publication.json) |
| 학습 입력·71특징·목표값·재현 경계 | [v26 학습 설명](TRAINING.md), [순서·타입 목록](feature-order.csv), [학습 인계 근거](training-evidence.json) |
| 모델/영화/매핑/런타임의 불변 번들 | [626 모델 계약](../../contracts/gbt-model-artifacts.md), [export·검증 안내](../../consumer/GBT.md) |
| 개인 후보와 입력 스냅샷 | [627 실행 안내](../../consumer/GBT-627.md) |
| 파티 공통 후보 점수화·요청·복구 | [747 실행 안내](../../consumer/GBT-747.md), [스트리밍 계약](../../../docs/api/streaming-contract.md) |
| 이미지·환경 선택·전환 확인 | [644 실행 안내](../../consumer/GBT-644.md), [644 검증 기록](../../consumer/GBT-644-VERIFICATION.md) |
| 사용자에게 노출하는 추천·파티 응답 | [개인 API](../../../docs/api/recommendation.md), [파티 API](../../../docs/api/party.md) |
| 아키텍처 결정의 소급 정리 | [ADR-0025 제안](../../../docs/adr/0025-v26-gbt-personal-party-recommendation.md) |

## 확정 모델과 점수 의미

- 개인 TASTE와 파티 구성원 점수는 같은 `gbt-v26-top2-raw` 모델을 사용한다.
  변환은 `v26-max70-kobis-flag-71-v1`, 입력은 순서가 고정된 71개 `float32` 특징이다.
- XGBoost CPU 회귀 모델이며 80트리·깊이4·seed625다. Spark GBT나 과거 230/245열 모델과 구분한다.
- 출력 의미는 `RECOMMENDATION_POLICY_SCORE_NOT_EXPECTED_STARS`다. 예상 별점이나
  만족 확률로 표시하지 않는다. 별도 보정·clipping·ALS 혼합·공개근거 품질 필터를 붙이지 않는다.
- `MAX(TMDB 유효 평가 수, 0.0007732457348003972 × 검증 KOBIS 관객 수)`의 `log1p`와
  TMDB 원평점, 검증 KOBIS 소속, 사용자와 영화의 관계를 사용한다.
  2,294표와 0.5 억제는 **학습 목표 생성 규칙**이다. 추론 후보를 다시 거르는 기준이 아니다.
- 모델이 지원하는 유효 이력을 전부 사용한다. 최근 10편 절단이나 K≥10 진입 조건은 없다.
  같은 영화는 실제 평가가 우선하고 실제 평가 없는 관심없음만 계산상 1.0점이다.
  이 가상 입력은 DB의 별점으로 저장하지 않는다. 감상-only는 평가 입력이 아니다.

모델 SHA·특징 순서·서빙 revision은 서로 다른 식별자다. 모델 JSON 하나가 같더라도
catalog·매핑·코드·런타임이 다르면 같은 번들로 간주하지 않는다. 실제 pin 값과 재검증 조건은
[626 계약](../../contracts/gbt-model-artifacts.md)과 [동결 검증 기록](../../consumer/GBT-626-VERIFICATION.md)을 따른다.

## 개인·발견·파티 계산

| 경로 | 현재 구현 |
| --- | --- |
| 개인 K≥1 | 지원되는 고정 개봉 전체 카탈로그를 GBT로 계산한다. 평가·관심없음·확정 감상을 제외해 TASTE 최대500편을 게시한다. Consumer 동점은 movieId 오름차순이다 |
| DISCOVERY | 기존 439 그룹·출처별 대중성 정책으로 최대100편을 공급한다. GBT 전체 점수를 전달하며 TASTE500만을 발견 모집단으로 삼지 않는다. 그룹·대중성 번들은 함께 활성화한다 |
| 개인 K=0 | 평가와 관심없음이 모두 없으면 지원 개봉 카탈로그의 `reach_log1p` 내림차순·movieId 오름차순으로 최대500편을 공급한다. GBT 예측이 아니며 DISCOVERY는 비어 있다 |
| 파티 | 구성원별 TASTE500+DISCOVERY100의 합집합(최대4명·2,400편)을 모으고 모든 구성원의 평가·관심없음·감상을 제외한 같은 후보를 구성원별 동일 GBT로 다시 점수화한다 |
| 최종 개인 화면 | Backend가 최신 미존재·평가·관심없음 제외와 점수 정렬·결정적 동점 셔플을 적용한다. 종류별 버퍼 최대50편, 모바일2+1·PC3+3 구성은 API 계약을 따른다 |

제외 위치를 혼동하지 않는다. 독립 `Scorer`는 실제 평가·관심없음과 호출자가 명시한 제외만
적용하고, 개인 K≥1 호출부가 확정 감상을 전달한다. 개인 K0 인기순 공급은 watched-only를
추가 제외하지 않는다. Backend 개인 최종 제외도 감상·OTT 클릭을 제외나 재가중으로 쓰지 않는다.
자세한 분기는 [개인 구현](../../consumer/src/feelm_consumer/gbt_personal.py)과 스트리밍 계약을 따른다.

파티에서는 사용자 간 raw 점수를 직접 평균하지 않는다. 공통 후보 N편에서 구성원별로
`fit = (더 낮은 점수의 후보 수 + 같은 점수의 후보 수 / 2) / N`을 구한다.
K>0 구성원의 fit을 동등 가중 평균하고 평균 내림차순 → 최솟값 내림차순 → 표준편차 오름차순
→ movieId 오름차순으로 정렬한 뒤 최대500편을 남긴다. singleton·전체 동점은 fit=0.5다.
K0 구성원은 평균 분모에서 빼되 그 구성원의 제외 목록은 적용한다.
[현행 구현](../../consumer/src/feelm_consumer/gbt_party.py)은 부동소수점 fit을 집계하며,
625 연구의 doubled-midrank 정수 감사 구현 자체를 운영 코드라고 설명하지 않는다.

## 입력 상태·오류

- Candidate와 `gbt-input-v1` 입력 스냅샷은 같은 generation/model/revision/hash로 게시한다.
  파티는 요청된 세대만 읽고 최신 입력으로 바꿔 끼우지 않는다.
- 현행 파티 요청 최상위는 `partyId`, `inputId`, `members` 3필드다(829).
  전환용 구 6필드 GBT와 legacy vector 요청 처리 경계는 747 안내를 따른다.
- 미지원 이력·모델 오류는 K0로 바꾸지 않는다. 개인 입력이 있으나 후보가 소진되면 EXHAUSTED다.
  파티 전원 K0·후보 소진은 기존 성공 결과를 유지하고 명시 격리하며 임의 후보를 채우지 않는다.

## 구현·검증·운영 상태

아래는 2026-09-25 Jira·코드와 사용자의 서버 적용 확인을 반영한 상태다.
Jira의 `완료`에는 미진행 종료도 포함되므로 서버 적용 상태와 개별 업무의 종료 사유를 구분한다.

| 업무 | 확인한 결과와 경계 |
| --- | --- |
| [625](https://ssafy.atlassian.net/browse/S15P21E106-625) | v26 선택·로컬 연구 종료. 연구 마감 JSON은 당시 상태를 보존한다 |
| [626](https://ssafy.atlassian.net/browse/S15P21E106-626) | 번들·공통 scorer 병합. 644 기록에 HDFS 5파일과 전달 ZIP의 byte/SHA 일치 관찰이 있다 |
| [627](https://ssafy.atlassian.net/browse/S15P21E106-627), [747](https://ssafy.atlassian.net/browse/S15P21E106-747), [742](https://ssafy.atlassian.net/browse/S15P21E106-742), [829](https://ssafy.atlassian.net/browse/S15P21E106-829) | 개인·파티·내부 상태/요청 연결이 develop에 반영됨. 개인 K0 인기순 공급 포함 |
| [644](https://ssafy.atlassian.net/browse/S15P21E106-644) | 표준 이미지·GBT 실행 설정 연결과 로컬 검증 완료. 이후 서버 적용도 완료된 상태로 사용자 확인 |
| [483](https://ssafy.atlassian.net/browse/S15P21E106-483) | 엣지 케이스 범위 제외로 **미진행 종료**. 추가 fallback·파티 전원K0·후보 모집단 확장 완료가 아님 |
| [628](https://ssafy.atlassian.net/browse/S15P21E106-628) | 별도 종단 검증·전체 재게시·롤백 작업을 **미진행 종료**. GBT 서버 적용 완료와 이 업무의 검증 범위는 구분 |
| [668](https://ssafy.atlassian.net/browse/S15P21E106-668) | 한국어 제목 없는 영화의 별도 순위 하향을 **미진행 종료**. 현재 v26에 언어 감점 정책을 추가하지 않음 |

`CONSUMER_RUNTIME`의 코드상 미설정 기본값 `legacy`는 설정을 생략한 환경에 대한 동작이며,
현재 서버의 적용 상태를 뜻하지 않는다. 서버 적용 완료는 사용자의 확인을 근거로 기록했다.
이 문서 작업에서 서버를 다시 조회하거나 설정·모델 포인터를 변경한 것은 아니다.

## 검증 근거와 남은 문서 경계

- 625 연구: 실제 취향322평가·10명·2/3인165조합에서 개인 Top500 합집합의 공동 Top100
  누락0·순서165/165 동일. 당시 연구 모집단의 결과이며 운영의 TASTE+DISCOVERY 합집합이나
  모든 미래 파티 조합의 충분성을 보장하지 않는다.
- 예약 MovieLens NDCG@10은 GBT0.6681, MAX0.6207, TMDB평점0.6753이다.
  모든 기준선 우위·한국 실사용자 만족도·전체 카탈로그 회수율로 해석하지 않는다.
- [644 로컬 검증](../../consumer/GBT-644-VERIFICATION.md): 전체/부분 후보28사례 점수 오차0,
  Top100/500 동일. 개인 전체 후보9.35~24.36초, subset 구성원당0.040~0.052초는
  2CPU/4GiB 로컬 컨테이너 실측이며 운영 p95나 파티 종단 지연이 아니다.
- 이번 838 검증은 학습 인계18파일 해시, 859,363행×71열, label 재계산, 특징 순서와 문서 링크·diff다.
  학습 인계의 과거 전체 점수 일치 기록과 이번 재검증을 [학습 근거](training-evidence.json)에서 구분한다.
  전체 재학습·운영 서버/HDFS 재검증은 실행하지 않았다. 서버 적용 상태는 위 사용자 확인을 따른다.

[service-v1](../service-v1/README.md)의 10개 이력·245열·모델 미선정·배치 재정렬 설명과
[625 publication](../gbt-transition/publication.json)의 미통합 상태는 당시 snapshot이다.
현재 계약은 위 링크를 따른다. ADR-0002와 ADR-0017의 과거 결정은 원문을 보존했으며,
ADR-0025는 소급 정리 제안이다. 채택 상태와 대체 표시는 팀 리뷰에서 확정한다.
