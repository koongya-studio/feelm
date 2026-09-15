# 과거 실행기·수식 원문

`.py.txt`와 `.Dockerfile.txt`는 읽기용이다. 완전 실행 패키지가 아니며 생산 경로에 자동 연결하지 않는다.
원본 스크립트의 고정 날짜·외부 파일 요구를 참고할 수 있도록 보존했다. 개인 PC 절대 경로는
`<research-root>`/`<MOVIELENS_ARCHIVE>`로 치환했으며 원본 해시는 출처 목록에 별도로 남겼다.
새 서비스 실행에 필요한 조건은 [재현 안내](../../../service-v1/REPRODUCTION.md)와 MODELS/GROUPS를 따른다.

| 원문 | 재사용할 부분 / 제한 |
| --- | --- |
| collect_kobis_expanded | 2026-09-12 종료의 역사 수집. CLI는 네트워크 접근·추가 파일 생성이 있으므로 이번에 실행하지 않음 |
| link_kobis_expanded | 제안/직접 ID 분리. TMDB·MovieLens 파일 경로가 고정되어 있어 이식 필요 |
| audit_kobis_expanded | 원문/연도별 Parquet/검토 기록이 있어야 전수 검산 가능 |
| kobis_movie_statistics | 영화 상세 관객 통계 parser. CLI와 순수 parser 구분 |
| report_tmdb_kobis_v1 | 역사 통계 생성기. 과거 다른 환경의 경로를 그대로 실행하지 않음 |
| foundation340_prepare / final344_worker | RH230 준비·학습. 원 split/seal 및 미포함 모듈/마운트 의존. 245열 실행기 아님 |
| k8_common / dv2_common / dv2_cluster | GKT 전처리·그룹 배정 수식. dv2_common은 원 설정을 import 때 읽으므로 실행 reference와 분리 |
| dv2_predictor / rec047_run / rec_ev_032_basic | 과거 결합/ALS 점수 수식. 당시 정책을 v1 기본으로 이어받지 않음 |
| rec046.Dockerfile | 당시 Spark4.1.3/Java21 및 Python 패키지 환경 참고. 팀 lock 변경 없음 |

모든 `.txt`를 `.py`로 바꾸어 실행하면 재현된다는 뜻이 아니다. 고정 source pin은
[provenance.json](../provenance.json)의 원본 해시로 추적한다. 새245 학습 실행기와 수집 경로 정규화는 후속 검토 대상이다.
