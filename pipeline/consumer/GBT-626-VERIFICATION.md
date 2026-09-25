# S15P21E106-626 로컬 인계 검증 — 2026-09-23

아래는 해당 로컬 검증 시점의 기록이다. 이후 Consumer·이미지 연결과 HDFS 파일 관찰은
[현재 v26 안내](../docs/gbt-v26/README.md)를 따른다. 당시 상태·측정값은 그대로 보존한다.

상태: **로컬 구현·동결 parity·독립 리뷰 통과. 운영 게시/활성화는 미실시.**
v26 모델·추천 기준은 바꾸지 않았다. 학습 PC/연구 디렉터리/ALS/FM 없이 실행하는 데이터 번들과
Consumer 내부 공통 scorer를 추가했다. 기존 event loop·Redis·Kafka·Backend·운영 이미지는 그대로다.

## 고정 식별자

| 대상 | 값 |
| --- | --- |
| modelVersion | `gbt-v26-top2-raw` |
| servingRevision | `sha256:45fdd90e9ab9346645c3f063526d46d6ef42ccdda2f13df6b34cb673a84b0271` |
| 실행 코드 revision | `sha256:e4556e40a81e6b6c89f55b8d9056a597d41b0f3bb600683b04b8d2a938d109d4` |
| 모델 SHA256 | `4d035f96f362458f0ce9b749bad4decbd4626cf279a072b6c1acd3b3acf92c25` |
| mapping revision | `sha256:91bcac47ec9f50cbea916319d04ee4e4d57ef48c48fe7e5c2742d77f52287f9e` |
| 변환 | `v26-max70-kobis-flag-71-v1`, 71열 |
| runtime | Python3.12.14, NumPy2.4.6, PyArrow19.0.1, SciPy1.15.2, XGBoost CPU2.1.4 |
| 로컬 runtime image ID | `sha256:5bf4b0461609dcecb6f951e917660d4fb44884d8fa0e9a85fa7ed8d7ee6de540` |
| 데이터 | 영화237,817행, 검증KOBIS1,741개/고유코드1,741개, 고정개봉기준2026-09-21 |
| bundle | 정확5파일, 7,765,851 bytes(약7.41MiB) |

## 검증 결과

- Consumer 전체: **361 passed, 10 skipped**. GBT만 **49 passed, 0 skipped**(Linux).
  skip은 기존 PostgreSQL 실연결1, Kafka/Redis 파티 통합1, redis-server 의존8이다.
  테스트용 운영 시스템 연결/설치로 임의 해소하지 않았다. GBT skip을 통과로 센 것이 아니다.
- Consumer Ruff 전체 및 Spark 신규 테스트 Ruff, `git diff --check`: 통과.
- Spark4.1.3/Python3.10의 기존 전송+새 GBT inventory 검사: **18 passed**.
- 독립 A/B 리뷰 후 안전 처리 보완. A 관련2회귀·CLI 비노출 통과,
  B 독립 공격23개 전부 통과. 두 세션은 서로 리뷰를 읽지 않았다.
- 실제 서비스 DB에서 영화 ID–TMDB ID만 읽기 전용 감사:
  **237,817쌍 모두 일치**, 추가0·삭제0·재결합0, snapshot 지원률100%.
  이 시점의 ID 감사이며 이후 신규 영화 메타데이터 자동 지원을 뜻하지 않는다.

### 원본 결과와 대조

기대값은 새 scorer가 아닌 기존 동결 원점수와 원본 연구 함수에서 가져왔다.
실제 사용자 원본 입력·후보 목록은 private fixture로 분리했으며 Git/서빙 번들에 넣지 않았다.

- 전체 후보19사례 + 파티 subset9사례 = **28사례 모두 점수 bit-exact, 오차0**.
- 모든 사례의 Top100/500 ID와 순서가 원본과 정확히 같다.
- 19개 전체 후보 사례에서 약1,500타겟씩 따로 대조한 **71특징 최대오차1.1920928955078125e-7**.
  승인 기준1e-6 이내이며 점수·순위에 차이 없음.
- K1/3/10/17/37/40/43/52/53/73/100/101/**8,518**,
  실제 평가 우선, 관심없음1점·취소, 신규 실제 평가, 입력 역순, 명시 감상 제외 포함.
  K0은 합성/번들 로드 검사에서 `NEEDS_COLD_START`를 확인했다.
- 파티2/3/4명 개인 Top500 합집합의 538/601/642개 지정 후보에서 각 구성원의 점수가
  개인 전체 후보 점수와 같다. 파티 집계 구현 자체는747의 범위다.

### Linux 자원·격리

로컬 Linux 컨테이너 각각 **2CPU/4GiB**, swap 추가 없음, network none, read-only, 비root 실행.
코드·완성 데이터 bundle·private 기대값만 마운트했다. 연구 폴더는 없고 pandas/pyspark도 미설치였다.

| 측정 | 결과 |
| --- | --- |
| 최초 모델/영화 자산 로드 | 15.05초, 프로세스당1회 |
| 개인 전체 후보 K1~101 | 8.68~11.24초 |
| 개인 전체 후보 K8,518 | 19.43초, 입력8,518개 전부 사용 |
| 단독 실행 최대 RSS | 1,198.01MiB(약1.17GiB) |
| 파티 구성원 subset 점수화 | 구성원당0.041~0.049초; 개인 Top500 신규생성 시간은 미포함 |
| 2프로세스 동시 K52 전체 | 각각10.27/10.35초, 점수·Top100/500 일치 |
| 2프로세스 각각 최대 RSS | 1,201.18/1,197.70MiB |
| 종료/OOM | 세 컨테이너 exit0, OOM false, cgroup failcnt0 |

이는 단일 실험의 실측 범위이며 p95/SLO나 실서버 CPU 처리량 보장이 아니다.
4GiB는 재현 조건이고 실제 서버 Consumer에 그 메모리가 예약됐다는 뜻이 아니다.

## 인수 조건 대응

| 조건 | 결과·범위 |
| --- | --- |
| T1 inventory/별도process | 5파일·71열·revision 새process load |
| T2 변조/부분/shape/runtime | 시작 거부. single-output gbtree·probe·canonical/dict/date 포함 |
| T3 path/symlink/size | 외부접근·mkdir 선행 부작용 거부, Linux symlink 직접 확인 |
| T4 ID·지원률 | 합성 오류 경계 + 실제237,817쌍 감사; 빈live export 실패 |
| T5 모든K | 유효 전체 입력 보존, K8,518 실원본 대조 |
| T6 실제 우선/dismiss | 실제 값 우선, 미평가 dismiss1, 취소 baseline복원 |
| T7 잘못된입력 | ID/별점/type/중복/NaN/큰정수/미지원 typed 오류 |
| T8 명시 제외 | 실제/dismiss/호출자제외. TASTE 감상-only 자동제외 추가없음 |
| T9 full/subset | 순서·청크·교차요청 동일, 부분추론실패 뒤 재시도 동일 |
| T10 frozen parity | 28사례 원점수·순위 일치. NaN/shape 불량은 검증실패 |
| T11 export재시도 | 빈/완성final 보존, 실제2process 성공1·충돌1, 부분쓰기/검증실패 정리 |
| T12 복원 | 손상신규거부 뒤 명시 이전bundle reload, 자동ALS fallback없음 |
| T13 Linux격리 | 전체/부분·두process 확인. 실서버 inference 미실시 |
| T14 전송 | 기존 Spark 전송도구의 로컬18검사. 실제HDFS 게시/replication은 미실시 |
| T15 개인정보 | allowlist 데이터/코드만, 평가·닉네임·HTML·DSN·Secret·ALS/FM 없음, 실패ID로그 차단 |

## 수정·실패 이력과 남은 범위

독립 리뷰에서 빈live 감사, 다중출력 모델, symlink검사 순서, 큰정수 별점, 비객체manifest,
잘못된달력날짜, parity NaN 검증·실패ID로그를 보완했다. 특징·모델·제외 기준은 그대로다.
수정 뒤 새 revision으로 전체 대조·동시 실행을 다시 수행했다. 이전 실험 결과는 덮어쓰지 않았다.

Windows 공유 bind mount의 Linux `renameat2(RENAME_NOREPLACE)`가 EINVAL을 반환한 시도는
안전하게 실패했다. 검사를 풀지 않고 native Linux volume에서 export/게시 검증했다.
운영 exporter도 해당 원자 연산을 지원하는 파일시스템을 사용해야 한다.

**아직626 전체 완료/서버 배포 완료는 아니다.** 승인된 HDFS 목적지·권한·replication·실제 게시,
그 URI로 새process 로드/이전버전복원 증거가 남았다. 그 뒤에도627/747 Consumer 연결,
644 이미지 통합과 실서버 측정,628/735 활성화·재게시가 필요하다.
커밋·Push·MR·Jira 상태 변경·운영 배포는 이번 로컬 구현에서 수행하지 않았다.

시작base `f2bd2645`; 검증시 최신develop `d0d45876`의 추가 변경은749 Backend 인물명 처리이며
이번 변경 파일과 겹치지 않는다. uncommitted 작업을 무단 rebase/덮어쓰기하지 않았다.
재현 절차는 [GBT.md](./GBT.md), 내부 계약은 [gbt-model-artifacts.md](../contracts/gbt-model-artifacts.md).
