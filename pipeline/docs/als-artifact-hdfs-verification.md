# ALS 모델 산출물 HDFS 실환경 검증

Jira: `S15P21E106-299`

검증일: 2026-09-11 UTC
상태: 검증 통과 및 생성 경로 정리 완료

- 안전 게이트 시작: 2026-09-11T00:49:42Z
- 검증·정리 종료: 2026-09-11T02:04:11Z

## 범위와 기준선

- 실행 revision: `fce92be30b045a09428b3ffe3776bbd049f5c1a5`
- 선행 revision: S15P21E106-290 `d59bdb2`, S15P21E106-293 `3295c0e`
- 두 서버의 저장소가 같은 승인된 `develop` revision임을 확인했다.
- Spark Master와 Worker 2개는 `ALIVE`, 총 4 Core / 8 GiB가 유휴 상태였고 활성
  Application과 Driver는 없었다.
- HDFS DataNode 2개는 live 상태였고 검증 전 누락·손상·복제 부족 블록은 없었다.
- NameNode와 advertised DataNode 2개의 RPC/data transfer endpoint에 실행 컨테이너가
  접근할 수 있음을 확인했다.

작은 합성 평점으로 ALS 산출물 저장·재로딩, 기존 버전 보호, 실패 정리와 HDFS 동시 공개만
검증했다. 테스트 Spark master는 fixture가 지정한 `local[1]`이다.

## 격리된 실행 환경

실행 호스트에는 `uv`, Java와 기존 `.venv`가 없고 운영 Spark Master 컨테이너에는
pytest와 저장소 마운트가 없었다. 운영 호스트나 운영 컨테이너를 변경하지 않기 위해 사람이
승인한 일회용 검증 컨테이너를 사용했다.

- 기반 이미지: `feelm-spark-ml:4.1.3-numpy2.2.6`
- 저장소: 컨테이너의 `/workspace`에 read-only 마운트
- 임시 환경: 컨테이너 `/tmp`에 `uv 0.12.8`과 `uv sync --frozen`으로 생성
- 런타임: Python 3.10.12, Java 21.0.11, PySpark 4.1.3, NumPy 2.2.6,
  pytest 8.4.2
- 실행 OS/HDFS 사용자: 모두 `spark`
- Run 경로: HDFS 관리자 계정이 정확한 Run 루트만 만든 뒤 `spark`, mode `700`으로 설정
- 운영 Spark Master·Worker, HDFS 컨테이너와 Compose는 변경·재생성하지 않았다.

pytest cache 경로는 read-only 소스에 있어 `PytestCacheWarning` 1건이 발생했다. 테스트
수행과 HDFS 산출물에는 영향을 주지 않았다.

## 첫 Run과 복제 설정 실패

- 논리 경로: `/verification/S15P21E106-299/run-20260911t012224z`
- 테스트: 2026-09-11T01:37:47Z ~ 01:39:04Z
- 결과: `6 passed, 24 deselected`, exit 0
- 기능 관측: 저장·재로딩, overwrite 거부, 실패 정리와 30회 동시 공개는 모두 통과
- FSCK: 168 files, 144 blocks, average actual replication 2.0, missing/corrupt block 0,
  최종 상태 `HEALTHY`
- 실패 조건: 144/144 block이 under-replicated이고 missing replica 144

첫 Run의 테스트별 하위 출력 UUID는 이 문서에 전사하지 않았으며, 고유 Run 루트와 FSCK
집계만 지속 증빙으로 기록했다.

NameNode의 `dfs.replication`은 2이지만 격리 컨테이너의 Hadoop client에는 이 설정이
없었다. Hadoop 내장 기본값 3으로 파일이 생성되어 DataNode가 2개인 클러스터에서 모든
non-empty block이 목표 복제 수보다 하나 부족했다. 첫 Run에 `setrep`를 적용하거나 결과를
삭제한 뒤 성공으로 바꾸지 않고 실패 증빙으로 보존했다.

## 재시도 Run

클러스터 계약과 맞게 아래 client 설정을 명시하고 새 Run ID에서 probe와 테스트를 처음부터
다시 실행했다.

```bash
PYSPARK_SUBMIT_ARGS='--conf spark.hadoop.dfs.replication=2 pyspark-shell'
```

쓰기 전에 Spark Hadoop configuration의 유효 `dfs.replication` 값이 2임을 확인했다.

- 논리 경로: `/verification/S15P21E106-299/run-20260911t014749z-retry1`
- 테스트: 2026-09-11T01:50:01Z ~ 01:51:10Z
- 결과: `6 passed, 24 deselected`, exit 0
- FSCK: 168 files, 144 blocks, default/average replication 2/2.0
- FSCK 결함: under-replicated 0, missing replica 0, missing/corrupt block 0
- 최종 FSCK 상태: `HEALTHY`

실행한 테스트 선택 조건은 다음과 같다.

```bash
ALS_ARTIFACT_TEST_ROOT='hdfs://<namenode-authority>:8020/verification/S15P21E106-299/<RUN_ID>' \
PYSPARK_SUBMIT_ARGS='--conf spark.hadoop.dfs.replication=2 pyspark-shell' \
uv run --frozen pytest tests/batch/test_als_artifacts.py -v -s \
  -k 'complete_artifact_version or overwrite_existing or temporary_version_after_write_failure or concurrent_publication'
```

## 완료 조건 관측

- `SERVING`, `MODEL_SELECTION`, `FINAL_EVALUATION`과 overwrite 보존 모델을 실제 HDFS에서
  다시 로딩했다.
- 네 산출물 모두 모델 UID와 metadata UID가 일치했다.
- 네 산출물 모두 rank 3, Factor dimension 3, User Factor 6개, Item Factor 14개였다.
- 세 stage와 overwrite 보존 최종 버전에는 `model/`, `user-factors/`, `item-factors/`,
  `metadata/`만 존재했다.
- 테스트가 재로딩 전후 같은 합성 입력의 예측값을 허용 오차 내에서 직접 대조했다.
- 같은 버전의 두 번째 저장은 `FileExistsError`였고 첫 버전을 다시 정상 로딩했다.
- metadata 실패 주입 결과에는 빈 `als/` 부모만 남고 최종 `als-failed-v1`과 `.tmp-*`가
  남지 않았다. 불완전 버전 로딩도 거부됐다.
- 동시 공개 30회 모두 성공 1개·실패 1개였다. 각 `published/`에는 winner 항목 하나만
  있고 loser source는 별도 형제로 보존됐다.
- probe가 만든 파일·디렉터리는 읽기와 rename 확인 후 정확한 `probe/`만 삭제했다.

재시도에서 생성된 여섯 출력 UUID는 다음과 같다.

- 저장·재로딩: `26727fedfaf94cd89e73eb295cea4ca8`,
  `a8f5944c36154934af1fb07a4dc0924e`, `b9d85ed04f4c4b7eaed40ce5ceb5f71`
- overwrite: `92738d5ebcc64d60816eff28fc2e1e4c`
- 실패 주입: `130cc5bd8ee34e29a7a647232eebef37`
- 동시 공개: `3d6464dedb6e4b9dbe3ed63700f1dc2c`

## 보존·정리와 미검증 범위

첫 Run과 재시도 Run은 증빙 확인 전 보존했다. 사용자 승인 후 강제 영구 삭제 옵션 없이
정확한 두 Run 경로에만 HDFS 기본 삭제 명령을 실행했으며 상위
`/verification/S15P21E106-299` 경로는 유지했다.
삭제 후 두 Run이 존재하지 않고 전역 under-replicated, missing, corrupt block과 pending
deletion block이 모두 0으로 복구됐음을 확인했다. 호스트 `/tmp`의 두 FSCK 상세 로그는
삭제하지 않았고 Git에도 추가하지 않는다. 첫 Run 로그는 126 KiB, 재시도 Run 로그는
102 KiB이며 파일명에 각 Run ID가 포함돼 있다.

마지막 상태 확인에서 운영 Spark Worker 2개는 `ALIVE`, 사용 Core와 memory는 0이었고 활성
Application과 Driver는 없었다. 검증용 컨테이너는 종료 후 자동 삭제됐다.

이번 검증은 32M 전체 학습, Spark Worker 분산 executor 실행, 추천 품질 평가,
Redis/Consumer 게시, 클러스터 장애·복구를 검증하지 않았다. `local[1]`에서 저장 경계만
검증했으며 290의 분산 ALS 검증을 반복하지 않았다.
