# S15P21E106-621 실험 사전점검

점검일: 2026-09-19 KST
상태: **GO-LOCAL — 기존 TMDB 자료와 KOBIS 2,176행을 동결 입력으로 사용하고, 데스크톱 10%
자원 계측과 계약 검증을 통과했다. 622·623 로컬 본 학습을 시작할 수 있다. 서비스 KOBIS
crosswalk, 노트북 실행 검증과 서버·HDFS 점검은 해당 환경을 사용할 때 수행하는 후속 조건이다.**

이 문서는 GBT·FM 실험의 입력, 로컬 장비, 서버와 HDFS를 같은 시점에 판정한다. 과거
검증 성공이나 파일 존재를 현재 실행 가능 상태로 바꾸어 적지 않는다.

## 판정

| 영역 | 판정 | 확인한 것 | 다음 게이트 |
| --- | --- | --- | --- |
| 기존 연구 snapshot | GO | `8889887`에서 게시119개·출처104개·외부48개와 연구 원본 해시 검증 PASS | 노트북 전송 후 같은 검증 반복 |
| 최신 E106 게시 검증 | LEGACY ISSUE | 최신 `develop`에서는 후속 변경된 `config.v1.json`과 게시 목록 해시가 달라 검사 중단 | 로컬 실험 시작과 분리하고 게시 전 별도 업무에서 목록 갱신 |
| 기존 GBT/FM/ALS 파일 | GO-LOCAL | `service-v1.json`의 외부48개가 데스크톱에서 bytes·SHA-256 일치 | 공유/HDFS 불변 URI 부여 전까지 `LOCAL_ONLY` 유지 |
| KOBIS 관객 자료 | GO-WEAK | 직접 확인한 KOBIS 코드별 마지막 관측 누적 관객 2,176행과 검산 자료 확인 | 로컬 실험에서는 weak feature·ablation으로 사용하고 미매칭은 missing 처리 |
| KOBIS 서비스 연결 | DEFERRED | 직접 KOBIS 코드2,176개, 출처 간 확정 ID 0개 | 서비스 활성화 전 `verified-kobis-service-crosswalk`, 평가 교집합, q99 prior 생성 |
| MovieLens 32M 원본 | GO-SOURCE | archive·4개 CSV 해시, 평점32,000,204행·영화87,585개와 시점 범위 확인 | 새 고정 표본 manifest와 역할 분포 생성 |
| TMDB 학습 입력 | GO-EXISTING | 새 TMDB 수집은 하지 않고 현재 보유한 필드·snapshot만 동결해 사용 | 결측·연결 coverage를 run별로 보고 |
| 620 데이터·평가 계약 | GO | `bec8dc48`의 `origin/develop`에 병합되고 계약 해시 고정 | 구현 산출물이 계약 digest를 참조하게 함 |
| 0~N 작은 고정 표본 | GO-SAMPLE / DISTRIBUTION_SHIFT | 역할별200명, episode4,773행, 후보60,000행; 계약 검증 PASS·필수 셀 부족0 | 분포 이동을 유지해 10% 입력에서 재확인 |
| 데스크톱 | GO-LOCAL | Ryzen 5 7500F 6C/12T, RAM32GiB; 20,000명 입력을 125.471초, peak RAM 2,174,685,184 bytes로 생성·검증 | GBT·FM run을 한 번에 하나씩 실행하고 같은 자원 지표 기록 |
| 노트북 | GO-LOCAL | Core Ultra 9 185H, RAM 약64GiB, RTX4070 Laptop과 데스크톱 10% 계측을 근거로 비차단 판정 | 첫 run에서 디스크·runtime·VRAM을 실행 기록에 추가 |
| 서버 | NOT_REQUIRED | 현재 PC에서 접근 불가하며 로컬 학습에는 사용하지 않음 | 선택 모델을 서비스에 반영하기 전에 읽기 전용 점검 |
| HDFS·Spark | NOT_REQUIRED | 로컬 학습 입력·산출물에는 HDFS를 사용하지 않음 | 선택 모델 게시·활성화 전에 용량·FSCK·권한·revision 재확인 |

## 데이터 근거

기계 판독 값, 해시, 판정과 blocker는 [READINESS-MANIFEST.json](READINESS-MANIFEST.json)에
고정한다. Markdown 표와 manifest가 다르면 manifest를 다시 생성해 양쪽을 맞춘 뒤 실행한다.

### MovieLens 32M

데스크톱의 원 archive와 압축 해제본을 직접 검사했다. `ratings.csv`는 32,000,204행,
`movies.csv`·`links.csv`는 각각 87,585행, `tags.csv`는 2,000,072행이다. 평점 시각 범위는
1995-01-09T11:46:44Z부터 2023-10-13T02:29:07Z다. archive SHA-256은
`e4a68655d7386b8f95f2f2424b2ff975dfdd15ffd59e0d864a14dca43e99d6ee`다.

MovieLens 32M은 모든 사용자가 최소20편을 평가한 표본이어서 FEELM의 저이력 목표 사용자를
그대로 대표하지 않는다. 연구 목적·출처 표기 조건을 지키고, 상업·수익 목적 사용에는 GroupLens
허가가 필요하다. 이 데이터의 오프라인 결과는 후보 비교 근거로만 사용한다.

### 개인 연구 저장소

- 개인 FEELM의 KOBIS·기존 연구 산출물 기준 revision:
  `e81e84ee3a115af3ab54515a3b6762ecb81a6766`. 현재 GitHub `main`은 이 revision을 포함하는
  후속 문서 커밋까지 전진할 수 있으므로 exact HEAD 대신 ancestor 여부와 origin 동기화를 확인한다.
- 2026-09-18에 로컬 43커밋을 GitHub `origin/main`에 push했다. 노트북은
  `https://github.com/itcksgud/FEELM.git`에서 clone하고 위 revision을 확인한다.
- 검증한 `main` worktree의 HEAD는 `fd638b55`이며 `origin/main`과 일치하고 기준 revision을
  ancestor로 포함한다. `outputs/`는 176,451개 파일, 33,478,491,937 bytes다.
- E106의 `pipeline/artifacts/service-v1.json`에 기록한 외부48개는 데스크톱에서 모두
  존재하고 bytes·SHA-256이 일치한다. manifest 상태는 여전히
  `NOT_READY_FOR_SERVICE_ACTIVATION`이다.

### KOBIS 관객 자료

[KOBIS-HANDOFF.json](KOBIS-HANDOFF.json)은 노트북에 넘길 영화 단위 관객 자료 2,176행과
그 설명·검산 파일의 상대 경로, bytes, SHA-256을 기록한다. 핵심 파일은
`linked-v2/confirmed-movie-comparison.parquet`이며 CSV는 사람이 확인하기 위한 동등본이다.

- 한 행은 원문에서 직접 확인한 `kobis_movie_code` 한 개다.
- 관객 지표는 직접 코드가 있는 관측 중 가장 늦은 기간의
  `last_observed_cumulative_admissions`다.
- 범위는 2004–2025 완전 연도와 2026-01-01~09-12에서 직접 코드가 확인된 영화다.
- 마지막 관측 누적값은 현재 시점 값이나 최초 개봉 이후 완전 누적을 보장하지 않는다.
- TMDB 대응은 제안이며 확정 TMDB·서비스·MovieLens 연결은 0개다.
- 72,700행 `annual-observations`는 이 2,176행을 재생성·감사할 때 쓰는 상위 원관측이다.
  노트북 최소 인계에는 포함하지 않고 전체 재생성이 필요할 때 별도 전송한다.

로컬 실험의 KOBIS 모집단 입력은 검산된
`linked-v2/confirmed-movie-comparison.parquet`다. KOBIS 관객수와 TMDB 투표수를 원값으로
max 비교하지 않고 출처별 모집단 백분위로 바꾼 뒤 max를 쓰는 방식을 ablation으로 비교한다.
2,176행은 weak feature로 사용하고 연결되지 않은 영화는 missing bucket으로 남긴다. 이 자료만으로
서비스 연결이 검증됐다고 보지 않는다. `service_movie_id↔tmdb_id↔kobis_movie_code` crosswalk는
KOBIS Discovery 활성화와 최종 서비스 반영 전에 만든다.

## 장비와 실행 한도

Spark GBT/FM의 현재 기준 경로는 CPU 학습이다. RTX 사용 여부는 별도 후보 구현의 재현성과
서버 CPU 추론 호환성을 비교한 뒤 결정한다. 장비가 GPU를 가진다는 사실만으로 Spark 학습이
GPU를 사용한다고 기록하지 않는다.

1. 작은 고정 fixture로 코드·split·특징·산출물 형식을 확인한다.
2. 10% 표본 한 번으로 wall time, peak RAM, 임시 파일과 최종 파일 크기를 기록한다.
3. 남은 디스크가 `max(예상 입력·spill·산출물 합의 2배, 50GiB)`보다 작거나 RAM 부족,
   OOM, 90분 초과가 예상되면 전체 실행을 시작하지 않는다.
4. GBT와 FM, seed 실행은 장비별로 하나씩 순차 실행한다. 두 PC를 임시 Spark 클러스터로
   묶지 않는다.
5. 각 run은 데이터 hash, split, 코드 commit, runtime, seed, 설정, 모델과 metric을 같은
   run ID로 기록한다.

245열·4view 약1,999만 행의 float32 특징만 약19.6GB다. 단일 배열로 적재하지 않고 기존
계약대로 최대4,096행씩 Parquet에 기록해야 한다. 데스크톱의 현재 여유 약103.6GiB는 전체 실행의
성공 증거가 아니며 10% 계측으로 spill을 추정해야 한다.

2026-09-19에 데스크톱에서 Git 2.47.1, Python 3.12.10, Java 21.0.12.1, Docker
27.5.1과 NVIDIA driver 595.79를 확인했다. Spark GBT/FM은 여전히 CPU 기준선이므로 이 GPU
정보는 GPU 학습이 검증됐다는 뜻이 아니다.

## 모든 실험의 분포 게이트

모든 기준선·GBT·FM·feature ablation은 한 번 만든 사용자 역할·episode·후보 digest를 공유한다.
학습 전 `splitDistributionReport`에서 다음 기준을 적용한다.

- N bucket: `0`, `1`, `2`, `3-4`, `5-9`, `10-19`, `20-29`, `30-49`, `50+`.
- target TRAIN 지원량 bucket: `0`, `1-9`, `10-49`, `50+`.
- 수치 분포: TRAIN 고정 bin 비율·분위수·PSI. PSI 0.10 이상은 경고한다.
- 범주 분포: 비율·최대 percentage-point 차이·base-2 Jensen-Shannon distance. 각각 5%p,
  0.10 이상은 경고한다.
- 필수 역할×N 셀의 사용자 수가 100 미만이면 `INSUFFICIENT_SAMPLE`이다.
- 구성 규칙·digest 불일치, 누수, 필수 셀 누락은 `BLOCKED`; 실제 시간 이동만 기준을 넘으면
  `DISTRIBUTION_SHIFT`로 분리한다.

이 값은 첫 FINAL_TEST를 열기 전의 초기 사전 기준이다. 변경이 필요하면 TRAIN·VALIDATION
근거와 새 manifest version을 남기고 새 run을 만든다. FINAL_TEST 분포나 label을 본 뒤에는
바꾸지 않는다.

### 작은 고정 표본 실행 결과

`ml32m-zero-n-readiness-v1`을 seed621로 생성했다. TRAIN·VALIDATION·FINAL_TEST는 각각200명,
episode는4,773행, 공통 후보는60,000행이다. 역할 간 사용자 중복, target/history 누수,
history timestamp, N별 동일 target·후보 digest, 관측/UNKNOWN label과 산출물 digest 검증은 PASS다.
필수 역할×N 셀은 모두100명 이상이다.

분포 판정은 `DISTRIBUTION_SHIFT`다. TRAIN 대비 VALIDATION의 history PSI는0.198,
target TRAIN 지원량 PSI는2.410이고 TRAIN 대비 FINAL_TEST의 target 지원량 PSI는3.003이다.
서로 다른 시간 창의 target year는 의도대로 완전히 다르므로 시간 이동 층으로만 보고한다.
target 별점 분포는 두 비교 모두 사전 임계값 안이다. 이 결과는 모델 성능이 아니라 입력 계약과
분포 경고가 실제로 작동한다는 증거다.

재생성과 검증 명령은 다음과 같다. 출력 경로는 빈 run 디렉터리여야 하며 기존 run을 덮어쓰지
않는다.

```powershell
python pipeline\docs\assets\service-v1\build_zero_n_readiness_sample.py `
  --movielens-root <MOVIELENS_EXTRACTED_ROOT> `
  --output <EMPTY_RUN_ROOT> `
  --users-per-role 200 --candidate-count 100 --seed 621
python pipeline\docs\assets\service-v1\verify_zero_n_readiness_sample.py `
  --root <RUN_ROOT>
```

### 10% 자원 계측 결과

전체 MovieLens 사용자 200,948명의 약9.95%인 20,000명으로
`ml32m-zero-n-readiness-10pct-v2`를 생성했다. 역할별 사용자는 TRAIN15,000명,
VALIDATION3,000명, FINAL_TEST2,000명이다. episode161,057행과 후보2,000,000행을 만들었고
계약 검증은 PASS, 필수 역할×N 셀 부족은0이다. 분포는 재표본화하지 않고
`DISTRIBUTION_SHIFT`로 유지했다.

데스크톱 실행 시간은125.471초, 최대 working set은2,174,685,184 bytes, 최종 출력은
388,727,592 bytes였고 관측 디스크 증가는432,422,912 bytes였다. OOM·90분 초과·디스크 부족
중단 조건에 해당하지 않아 로컬 본 학습 자원 게이트는 PASS다.

첫 시도 `ml32m-zero-n-readiness-10pct-v1`은 FINAL_TEST3,000명을 요청했으나 해당 시간창의
적격 사용자가2,080명뿐이라 산출물을 재사용하지 않고 중단했다. 총20,000명은 유지하면서 역할
구성을 조정해 새 run ID로 다시 만들었다. 이 실패와 수정 이유도 manifest에 보존한다.

## 서버·HDFS 후속 점검

2026-09-19 재확인에서도 현재 PC에는 `e106` SSH 별칭과 SSH config가 없어 직접 점검하지 못했다.
이번 GBT·FM 학습은 로컬 파일로 실행하므로 서버·HDFS 접근은 시작 조건이 아니다. 선택 모델을
게시하거나 서비스에 활성화하기 전, 접근 가능한 운영자는 공유 서비스 변경·재시작 없이 다음을 기록한다.

- 두 서버의 UTC 점검 시각, checkout revision, CPU·RAM·디스크 여유.
- 관련 컨테이너 상태와 Spark Master의 active applications/drivers.
- Worker2개의 `ALIVE`, core·memory 사용량과 source revision.
- DataNode2개의 live 상태, NameNode safe mode, HDFS 용량과 기본 복제 수2.
- 승인된 검증 전용 경로의 접근 권한과 정확한 경로에 대한 FSCK.
- 기존 snapshot·최종 경로 존재 여부. 삭제·overwrite·권한 변경은 수행하지 않는다.

2026-09-11의 HDFS 검증은 당시 revision `fce92be30b045a09428b3ffe3776bbd049f5c1a5`에서
통과한 과거 증거다. 현재 정상 판정에는 사용할 수 없다.

## 본 학습 시작 조건

다음 두 조건을 충족했으므로 데스크톱에서 622·623을 시작할 수 있다.

- 작은 표본에서 통과한 생성·검증 코드를 10% 고정 입력에 적용하고 자원·분포 결과를 기록했다.
- 노트북 또는 데스크톱의 10% 계측이 사전 중단 기준을 통과했다.

노트북은 제공 사양상 로컬 학습 비차단이며 첫 run에서 디스크·runtime을 기록한다. 서비스
crosswalk와 서버·HDFS 점검은 선택 모델을 서비스에 반영하기 전에 수행한다. 새 TMDB 수집은
이번 실험 범위에 포함하지 않는다.
