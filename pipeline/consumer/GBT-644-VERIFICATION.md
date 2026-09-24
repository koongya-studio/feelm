# 644 로컬 이미지·실행 연결 검증 — 2026-09-24

상태: **로컬 구현·이미지·동결 점수 대조 통과. 운영 이미지/설정/포인터 변경 없음.**
범위와 인프라 적용 순서는 [GBT-644.md](GBT-644.md)를 따른다.

## 대상과 결과

기준 develop: `21a8edead686a17014df91e669c1ec7ba6d1ec3d`.
모델은 `gbt-v26-top2-raw`, servingRevision은
`sha256:45fdd90e9ab9346645c3f063526d46d6ef42ccdda2f13df6b34cb673a84b0271`.
원본 GBT 코드와 데이터 번들은 수정하지 않았다.

- 기존 Dockerfile의 test와 runtime target 빌드 성공. 최종 이미지에서 Java/libhdfs와 CPU XGBoost import 성공.
- Consumer 전체 Ruff 통과, pytest **501 passed, 68 skipped**.
- 새 실행 설정·표준 런타임·양쪽 entrypoint/합성 추론·626 bundle·개인/파티 계산을
  묶은 검사 **131 passed, 0 skipped**.
- 관련 infra/CI boundary/adapter/fixture/격리 선택 검사 **96 passed**.
- 실제 Compose rendering에서 개인/파티 GBT mode·bundle·revision·byte 예산 일치,
  파티에 개인 DB 자격증명이 전달되지 않음을 확인.
- 최종 runtime: UID10001, `/app`에는 `.venv`만 존재. package 안에 모델/parquet/npz/pickle 없음.
  pandas/pyspark 미설치. 테스트 도구와 fixture는 최종 이미지에 복사하지 않음.
- 독립 A/B 리뷰에서 CI 고정 identity 갱신 누락을 발견해 수정. 새 identity 검증 통과,
  package source·archive·Secret 경계 검사를 완화하지 않음.

전체 검사에서 건너뛴 68건: 개인 GBT Docker Redis27, 파티 GBT Redis21,
파티742 통합10, redis-server8, PostgreSQL1, Kafka/Redis 파티 통합1.
이 외부 의존 검사는 성공으로 계산하지 않았다. 이번 변경은 저장/재시도 알고리즘을 바꾸지 않는다.

## 실제 번들과 동결 기대값

배포용 runtime 이미지를 **2CPU/4GiB, swap 추가 없음, network none, read-only, 비root**로 실행했다.
설치된 package 코드에 읽기 전용 완성 bundle·기존 private 기대값·검증 도구만 마운트했다.
학습 소스·ALS 자료·운영 DB/Kafka/Redis는 제공하지 않았다.

- 개인 전체 후보19사례 + 파티 구성원 subset9사례, **28사례 모두 점수 bit-exact, 최대오차0**.
- 모든 사례의 Top100/500 ID·순서 동일. 전체 이력 K1~8,518 포함.
- 영화 정적 정보·모델 최초 로드: 22.90초.
- 개인 전체 후보: 9.35~24.36초. 파티 구성원 subset: 0.040~0.052초.
- 최대 process RSS: 1,195.46MiB. cgroup memory failcnt0, oom_kill0.
- 새 프로세스로 재실행한 개인1·파티4사례도 점수 bit-exact·Top100/500 동일.
  재로드 17.09초, 최대 process RSS 1,218.85MiB, memory failcnt0·oom_kill0.

단일 로컬 실행의 실측이다. 실서버 p95/동시 처리량·Kafka 지연 SLO를 입증하지 않는다.
파티 수치는 구성원별 후보 점수화이며 Kafka/집계/Backend까지 포함한 종단 지연이 아니다.

## 전달 ZIP과 서버 관찰

사용자가 이미 전달한 `S15P21E106-626-gbt-v26-serving-20260923.zip`을 확인했다.
HDFS의 해당 v26 bundle 5개 파일은 ZIP의 파일과 **byte 수·SHA256 모두 일치**했다.
파일 전달·저장은 되어 있었고 재전송할 필요가 없다. 이 읽기 검사는 재시작·replication·
실제 Consumer의 HDFS 로딩을 검증한 것은 아니다.

같은 시점 운영 개인·파티 컨테이너는 기존 실행 명령과 GBT 환경 미설정 상태였다.
이 이미지·설정 변경은 아직 운영에 적용하지 않았다. 원격 운영 URI·주소·자격증명은 기록하지 않는다.

## 남은 인계

설치된 CI boundary의 검토·적용, 승인된 GBT 환경값 설정, 개인/파티 전체 릴리즈 전환,
model pointer·입력/Candidate/Backend의 새 generation 종단 검증과735 재게시/복구는 남아 있다.
이 결과를 Jira644 전체 운영 완료나 서비스 GBT 활성화 완료로 해석하지 않는다.

## MR CI 후속 확인

첫 MR pipeline에서 infra 격리 archive가 교차 서비스 fixture를 포함하지 않아 2개 subtest가
실패했다. 공유 fixture 검사를 별도 TestCase로 나누고 infra MR은 기존 검증 클래스만 선택한다.
main Consumer CI는 모듈 전체를 실행하여 fixture 동일성 검사를 필수로 유지한다.
Backend fixture만 변경될 때도 main 검사가 생성되도록 두 경로를 changes에 포함한다.
실행 계층 선택과 Backend fixture 변경 감지 누락은 독립 리뷰의 MR 사후 누락 2건으로 기록한다.
수정 후 실제 허용 범위(`.gitlab-ci.yml`, `.gitlab/`, `infra/`)만 복사한 Linux 컨테이너에서
infra 전체 **312 tests 통과**, 네트워크 차단 상태로 후속 관측 검사도 통과했다.
전체 checkout의 fixture 정상/변조/누락 공격은 각각 성공/실패/오류이고 skip은 모두 0이었다.
로컬 첫 재현은 검증용 이미지의 PyYAML 누락으로 1건 오류였으며, 도구 의존성을 설치한 뒤
같은 전체 검사를 재실행했다. 서비스 의존성이나 CI 테스트 조건을 바꾸지 않았다.

Consumer MR job은 서버에 설치된 기존 CI identity와 새 runtime이 달라 테스트 시작 전에
거부됐다. 새 검토 identity의 인프라 적용이 필요하며 보호 검사를 우회하지 않는다.
