# 학습·평가 설정 기록

이 폴더에는 실행별로 재사용할 **값**을 JSON으로 보관한다. 기존
`spark/batch/train/settings.py`는 설정을 읽고 검증하는 **코드**이며 그대로 둔다.
모든 settings.py를 여기로 이동하는 구조가 아니다.

- `als-baseline.json`: 유지한 연구 ALS 기준 설정. 현재 팀 Job이 자동으로 읽지 않는다.
- `structured-content.json`: REC030 콘텐츠 계산·가중치·학습 prior 출처.

이 설정은 과거 실험의 명세이며 서비스 기본값이나 EC2 접속 설정이 아니다.
운영 값은 기존 CLI/환경변수 절차를 따른다. Secret은 넣지 않는다.
