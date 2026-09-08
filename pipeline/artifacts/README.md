# 외부 산출물 목록

이 폴더에는 README와 JSON 목록만 커밋한다. `artifacts.py` 같은 Python 모듈과는
역할이 다르므로 기존 모듈 이름을 바꾸지 않는다.

각 목록은 실제 연구 파일의 크기·SHA256·상대 출처·필수 동반 입력을 기록한다.
`shared_uri: null`, `availability: LOCAL_ONLY`는 공유 업로드가 아직 안 됐다는 뜻이다.
대용량 파일을 Git에 올렸거나 EC2에서 읽을 수 있다는 뜻이 아니다.

- `als-baseline.json`: 기존 연구용 Factor NPZ. 공식 `als-artifacts-v1` 모델이 아니다.
- `structured-content.json`: 콘텐츠 특징 재구축용 메타데이터·행 ID·학습 prior.

공유 저장소가 정해지면 원본 그대로 업로드하고 바이트 수·해시를 대조한 뒤 불변 URI와
`availability`를 갱신한다. 인증정보나 개인 PC 절대 경로를 URI로 쓰지 않는다.
모델·Parquet·CSR 자체는 `pipeline/data/` 등 무시되는 로컬 디렉터리에 내려받는다.
각 파일의 재배포 범위와 원천 데이터 이용 조건은 전달 전에 확인한다.
