# 학습·평가 설정 기록

- [service-v1](service-v1/config.v1.json): 사용자 검토를 거친 로컬 v1의 정책·특징·학습·그룹·비교·유사 영화 설정6개.
  [설계 인계](../docs/service-v1/HANDOFF.md)와 함께 읽는다. 팀 Job의 자동 입력이나 새 모델 학습 완료 표시가 아니다.

이 폴더에는 실행별로 재사용할 **값**을 JSON으로 보관한다. 기존
`spark/batch/train/settings.py`는 설정을 읽고 검증하는 **코드**이며 그대로 둔다.
모든 settings.py를 여기로 이동하는 구조가 아니다.

- `als-baseline.json`: 유지한 연구 ALS 기준 설정. 현재 팀 Job이 자동으로 읽지 않는다.
- `structured-content.json`: REC030 콘텐츠 계산·가중치·학습 prior 출처.

이 설정은 과거 실험의 명세이며 서비스 기본값이나 EC2 접속 설정이 아니다.
운영 값은 기존 CLI/환경변수 절차를 따른다. Secret은 넣지 않는다.
