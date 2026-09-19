# 노트북에서 이어갈 프롬프트

아래 블록을 새 Codex 작업의 첫 메시지로 사용한다.

```text
S15P21E106-621 인계를 이어서 수행해라.

목표는 GBT/FM 본 학습 전에 노트북의 코드·데이터·자원 readiness를 재검증하고,
S15P21E106-622·623을 시작해도 되는지 GO/BLOCKED/NOT_REQUIRED로 판정하는 것이다.

1. C:\higher\projects\S15P21E106의 AGENTS.md와 pipeline/AGENTS.md를 먼저 읽어라.
2. E106은 GitLab clone이어야 한다. 현재 branch, origin/develop, commit, dirty files와 621/620
   변경의 병합 여부를 확인하고 사용자 변경을 덮어쓰지 마라.
3. C:\higher\projects\FEELM은 https://github.com/itcksgud/FEELM.git 의 main을 clone한
   저장소여야 한다. origin/main과 동기화됐는지 확인하고,
   `git merge-base --is-ancestor e81e84ee3a115af3ab54515a3b6762ecb81a6766 HEAD`가
   성공하는지 확인하라. e81e84e는 KOBIS·기존 연구 산출물의 기준 revision이고 HEAD를 정확히
   그 값으로 고정하라는 뜻이 아니다. bundle은 GitHub clone이 불가능할 때만 사용하라.
4. C:\higher\data\feelm-research 아래에서 pipeline/artifacts/service-v1.json의 외부48개와
   pipeline/docs/service-v1/readiness/KOBIS-HANDOFF.json의 모든 파일을 bytes·SHA-256으로
   검증하라. READINESS-MANIFEST.json의 MovieLens 32M archive와 4개 CSV도 같은 방식으로
   검증하고 이용 조건을 확인하라. 경로를 추측하거나 누락을 0값으로 대체하지 마라.
5. 새 TMDB 수집은 하지 않고 기존 snapshot과 필드만 동결해 사용한다. KOBIS 최소 인계의 핵심
   입력은 linked-v2/confirmed-movie-comparison.parquet의 2,176행이다.
   각 행은 직접 확인한 KOBIS 영화코드와 마지막 관측 누적 관객값을 담는다. CSV 동등본과
   summary/report/result review도 manifest대로 확인하라. 72,700행 원관측은 재생성용 상위
   자료이며 최소 인계 대상이 아니다. 로컬 GBT/FM에서는 weak feature·ablation으로 사용하고
   미매칭은 missing bucket으로 처리하라. 확정 출처 간 ID는0개이므로 verified crosswalk 전에는
   Discovery KOBIS 채널이나 최종 서비스 연결을 준비 완료로 표시하지 마라.
6. CPU, physical/logical core, RAM, GPU·VRAM, 모든 로컬 디스크 여유, OS, Git, Python,
   Java, Docker, NVIDIA driver를 기록하라. 사용자 화면의 사양을 현재 실행 결과로 대신하지 마라.
7. 620 계약이 develop에 병합됐는지 확인하라. 계약 파일 SHA-256이
   READINESS-MANIFEST.json과 다르면 이유를 확인하기 전까지 BLOCKED로 남겨라.
8. 조건이 충족되면 작은 고정 fixture의 TRAIN/VALIDATION/FINAL_TEST 사용자, 0~N episode,
   candidate/label과 splitDistributionReport를 먼저 생성하라. 모든 모델이 동일 digest를
   사용해야 하고 READINESS-MANIFEST.json의 사전 분포 기준을 적용한다. 먼저 전송된
   ml32m-zero-n-readiness-v1을 verify_zero_n_readiness_sample.py로 검증하며, 기대 결과는
   계약 PASS와 DISTRIBUTION_SHIFT다. 그다음 10% 표본 한 번으로 wall time,
   peak RAM, 입력·spill·checkpoint·최종 산출물 bytes를 측정하라. OOM/90분 초과/디스크 부족을
   행 축소나 자동 재시도로 숨기지 마라.
9. Spark GBT/FM 기준선은 CPU다. GPU 후보는 새 의존성 합의, 재현성, 서버 CPU 추론 parity가
   확인된 별도 run으로만 다뤄라. 데스크톱과 노트북을 하나의 임시 클러스터로 묶지 마라.
10. 서버/HDFS 접근이 없으면 과거 2026-09-11 PASS를 현재 상태로 쓰지 말고 로컬 학습에는
    NOT_REQUIRED, 서비스 게시에는 DEFERRED로 남겨라.
    접근이 있으면 읽기 전용으로 revision, 자원, Worker2 ALIVE, DataNode2, replication2,
    safe mode, 용량, 권한과 정확한 검증 경로 FSCK를 확인하라. 서비스 재시작·삭제·권한 변경은
    하지 마라.
11. 결과는 데이터·노트북·데스크톱·서버·HDFS별 evidence, checked_at, revision,
    GO/BLOCKED/NOT_REQUIRED/DEFERRED, blocker와 다음 행동으로 작성하라. 현재 실행 환경의
    데이터 계약 또는 10% 자원 게이트가 BLOCKED면 622·623 본 학습을 시작하지 마라. 노트북
    점검은 노트북 실행 전에, crosswalk와 서버/HDFS 점검은 서비스 반영 전에 완료하라.
```
