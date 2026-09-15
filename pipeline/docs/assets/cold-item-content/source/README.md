# 재현용 연구 코드 보관본

Jira S15P21E106-338. 운영 Spark 모듈에서 import하거나 실행하지 않는 연구 증거다.
`scripts/`는 원래 계산·학습·검증 코드이며 추가 로컬 import를 포함했다.
원본과 게시본의 SHA256·크기·변환은 [출처 목록](../provenance.json)에 있다.
팀 LF 규칙을 적용했고 역사 설정의 PC 절대 경로는 `${MOVIELENS_ARCHIVE}`로 표시했다.
따라서 **게시본에 원본 실행 PASS/fingerprint를 그대로 적용할 수 없다.**
원래 Markdown 실행 명세는 `.md.txt`로 보관했다. 역사 문서 안 경로·명령은 당시 연구 루트 기준이다.

## 지금 확인할 수 있는 것

Python과 numpy, pandas, scipy, pyarrow가 있는 별도 연구 환경에서 이 디렉터리를 작업 루트로 사용한다.

```sh
python -B -m unittest discover -s scripts -p "test_*.py"
```

이는 합성 입력의 수식·누수 방지 조건 검증이다. 모델 재학습, 자료 준비, 서버 동작 검증이 아니다.
팀 `uv` 의존성·lock·CI는 바꾸지 않는다. 당시 실제 학습은 Spark 4.1.3 / Python 3.10 / Java 21,
`local[4]`, 12GiB 컨테이너·8GiB JVM과 설정에 고정한 이미지에서 수행했다.
Dockerfile의 기반 이미지가 mutable이므로 지금 다시 빌드해도 기록된 image ID와 같다고 보장하지 않는다.

## 전체 재현에 필요한 것

1. [외부 산출물 목록](../../../../artifacts/cold-item-content.json)의 원자료·메타데이터·역할 분할과 관련 seal에 열거된 파일 전체를 복원한다. 현재 공유 URI는 없다.
2. 원본 상대 구조 `scripts/`, `docs/recommendation/experiments/`, `performance/`, `outputs/`를 별도 연구 루트에 복원한다. `.md.txt`는 당시 `.md` 이름으로 복원한다.
3. 출처 목록의 원본 SHA에 맞는 코드·설정·명세를 복원한다. LF 및 경로 치환을 되돌리는 것만으로 원본 복원이 보장되는 것은 아니며 반드시 해시를 대조한다.
4. Linux/EC2 경로로 바꾸려면 새 설정·fingerprint를 만들고 실행 전 독립 검토를 다시 받는다. 기존 PASS나 seal을 임의로 갱신해 실행문턱을 우회하지 않는다.
5. 원본 명세의 준비 → 학습 → 예측 검증 → 채점 → 결과 검토 순서를 따른다. 평가 정답으로 모델을 다시 고르지 않는다.

REC047은 readiness 산출물, cold는 기존 REC047 ALS 산출물과 사전 영화 분할에도 의존한다.
cold 최종 설정은 `preflight.json`과 `movie-partition.parquet`를 입력으로 고정했으므로 빈 출력 폴더에서 그대로 preflight부터 실행할 수 없다.
그림 코드는 Windows 맑은 고딕 경로를 사용한다. Linux에서는 폰트 대응과 별도 그림 검수가 필요하다.
Git clone만으로 전체 실험 재실행·EC2 배포가 가능한 묶음이라고 표현하지 않는다.
