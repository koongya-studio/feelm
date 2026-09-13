# 추천 v1 팀 인계

Jira: [387](https://ssafy.atlassian.net/browse/S15P21E106-387). 기준: `origin/develop`의 `ae9de07be180027d90e4c809d168fab1e7dad88b`.

**이 자료는 구현할 추천 v1과 그 판단 근거다. 모델 학습·API 구현·배포 완료 보고서가 아니다.**
사용자가 검토한 로컬 설계를 팀 리뷰에 넘긴다. 문서의 APPROVED는 그 로컬 결정 상태이며,
팀의 현행 API·DB·ADR·pipeline/contracts를 자동으로 덮어쓰지 않는다.

## 빠르게 읽기

1. [전체 구성](README.md) → [정책](POLICY.md): GBT 기본, 맞춤2+발견1, K별 처리와 후보500 재정렬.
2. [ALS와 비교](MODEL-COMPARISON.md) → [과거 연구 결론](RESEARCH-HISTORY.md): 무엇을 확인했고 무엇은 미검증인지.
3. [데이터 분석](data-analysis/tmdb-kobis-v1-20260913/REPORT.md) → [KOBIS 확대 자료](data-analysis/kobis-expanded-20260913/REPORT.md): 표본·시점·식별 한계.
4. [모델](MODELS.md)·[고정 그룹](GROUPS.md)·[비슷한 영화](SIMILAR-MOVIES.md) → [계약 이행 목록](CONTRACTS.md).
5. [운영](OPERATIONS.md) → [인수 기준](ACCEPTANCE.md)·[평가 명세](EVALUATION.md) → [재현/자료 준비](REPRODUCTION.md).

## 저장 위치

| 위치 | 내용 |
| --- | --- |
| `pipeline/docs/service-v1/` | 구현 기준, 근거 요약, 데이터 분석, 발표 설명, 인수/평가 명세 |
| `pipeline/configs/service-v1/` | 정책·245열·학습·그룹·비교·유사 영화 설정 6개. 팀 Job이 자동으로 읽지 않음 |
| `pipeline/docs/assets/service-v1/` | 작은 결과·그림·역사 검토·참고 소스·출처 해시 |
| `pipeline/artifacts/service-v1.json` | 큰 입력·모델의 바이트/SHA256·LOCAL_ONLY 상태, 아직 없는 파일 목록 |

`settings.py`와 `artifacts.py`를 옮기거나 대체하지 않는다. 빈 폴더용 파일 없이 실제 README·설정·자료가 들어 있다.

## 구현 순서와 담당 영역

| 순서 | 할 일 | 완료 조건/연결 업무 |
| --- | --- | --- |
| 1 | 현행 계약과 v1 차이 리뷰 | CONTRACTS의 API·DB·이벤트·score 의미·세트 generation·ID 이행 목록을 해당 담당자와 코드 변경에 반영 |
| 2 | 입력/카탈로그·후보 자격·버전부터 구현 | 0.5별점, K와 최신 원본 이력 분리, TMDB/KOBIS OR, 결측·미연결 상태, 동일 snapshot |
| 3 | scorer·배치500·현재 입력 재정렬·세트2+1 | fixture로 빈 입력·미지원·중복·소진·동시성 검증. ALS119/Consumer199의 기존 구현 경계를 확인 |
| 4 | KOBIS 연결·기존230열 재구성·신규 모델 준비 | 검증 crosswalk·누적 기준·row-lineage·표본 하한을 통과한 후 B1/B2/B3·FM245 학습 |
| 5 | 발견8×128·10×10 및 상세 유사 영화 | 고정 그룹/서비스ID export 후 검수. 유사 영화는 pgvector167·API216과 연결하며245 학습과 독립적으로 진행 가능 |
| 6 | 활성화 전 기술·품질 검수 | ACCEPTANCE/EVALUATION과 새 모델 native/portable parity, 버전 교체·복구 검증. EC2 결과는 별도로 기록 |

기존 Jira의 책임을 이 문서만으로 변경하거나 완료 처리하지 않는다. 339·340·341은 과거 연구
결과 기록이고, 387은 선별 자료 인계다. 구현 업무를 중복 생성하지 않는다.

## 현재 준비 상태

| 항목 | 상태 |
| --- | --- |
| 기존 FM/GBT230·ALS 모델 및 과거 개발 평가 | 로컬 파일 존재, 선별 근거 인계. 전체 재학습은 이번에 실행하지 않음 |
| GKT131·8×128 연구 산출물 | 로컬 파일 존재. 서비스 ID export·실제 인덱스·조회 품질은 미검증 |
| KOBIS72,700 영화·연도 행 | 수집·원문 검산 완료. 확대 자료의 확정 TMDB/서비스 연결은0개 |
| KOBIS245 GBT/FM·q99·새 보정·row-lineage | NOT_BUILT. 옛230 모델을245 모델로 사용하지 않음 |
| v1 API/DB/Redis/Kafka·EC2/HDFS 인수 | 미실행. 설계 검토 PASS와 구분 |
| 실제2026 한국 사용자 추천 만족도 | 미검증 |

장시간 작업은 이 인계 다음 단계다. 이미 있는 파일을 확인·export하는 작업과 새 학습이 필요한
작업을 [재현 안내](REPRODUCTION.md)에서 구분했다.
