# 계산 검증과 EC2 인계

## 현재 Git에 있는 것

설정 JSON, 원본과 대조한 계산 참고 코드, 합성 fixture와 테스트, 소형 집계 결과·그림,
독립 감사 기록, 외부 산출물 해시가 있다. 기존 연구의 전체 실행기·사용자별 데이터·
대용량 모델·Parquet은 포함하지 않는다. 복사한 감사 JSON 내부 경로는 당시 연구의
식별자이며 이 저장소에서 열리는 링크가 아니다. 대응표는 `assets/provenance.json`이다.

아래 명령은 저장소 루트에서 시작한다. 팀의 기존 Python 3.10.12 / PySpark 4.1.3 /
Java 21 및 `uv` 환경을 사용한다. 의존성과 lock 파일을 추가 변경하지 않았다.

```bash
python pipeline/spark/jobs/batch/verify_research_handoff.py
cd pipeline/spark
uv sync --frozen
uv run pytest tests/batch/test_research_reference.py tests/batch/test_research_handoff_verification.py
uv run ruff check batch/evaluation/reference.py tests/batch/test_research*.py jobs/batch/verify_research_handoff.py
```

첫 명령은 Python 표준 라이브러리만으로 이관한 근거의 SHA256을 확인한다.
JSON은 팀 Git 규칙에 맞게 개행만 LF로 변환했다. 출처 목록의 `source`에는 원본 해시,
최상위 `sha256`에는 이관본 해시를 기록했으며 JSON 값은 바꾸지 않았다.
계산 테스트는 NumPy만 필요하며 Spark 클러스터를 띄우지 않는다. 합성 fixture 정답은
원본 함수로 계산했다. `assets/source-excerpts.txt`에 해당 함수 원문이 있다.
Fold-in·가중치는 1e-12, float32 CSR에서 float64 토큰 계산으로 옮긴 유사도는
rtol=1e-6/atol=1e-7로 대조한다. 이 검증은 새 실험이나 전체 품질 재평가가 아니다.

## 재현 수준

| 구분 | 이번 인계 상태 |
| --- | --- |
| 결과·판단 추적 | Git의 집계 JSON·감사·해시로 가능 |
| 핵심 수식 검증 | 포함한 합성 fixture와 코드로 가능 |
| 전체 REC032/REC030 결과 재실행 | 원본 실행기 의존 묶음·분할·입력/레이블 seals·외부 파일 추가 필요 |
| 팀 Spark 학습 실행 | 기존 업무 267/268/269/293/119가 담당. 연구 결과 재현과 다른 경로 |
| EC2 배포·서비스 활성화 | 이번 작업에서 수행하지 않음 |

연구 ALS는 Spark 4.2.0 / Python 3.12.5 / NumPy 1.26.4 / pandas 2.2.3에서 실행했다.
팀 런타임과 다르므로 새 학습 결과의 동일성·호환성을 별도 검증해야 한다.
팀의 현재 ALS Job은 입력 검증 진입점이며 설정 JSON을 읽어 전체 학습을 자동 수행하지 않는다.
원본의 사용자 분리, 입력 영화 선정, E 후보, 동점 SHA256 salt와 seed를 바꾸면
같은 역사 실험이라고 부를 수 없다. 새 실행의 결과를 기존 결과 JSON에 덮어쓰지 않는다.

## 외부 산출물 전달

현재 `artifacts/*.json`의 실제 파일은 `LOCAL_ONLY`, `shared_uri`는 null이다.
따라서 Git을 clone하는 것만으로 모델이나 메타데이터가 다운로드되지는 않는다.
공유 목적지가 정해지면 기록된 원본 상대 경로 구조를 보존하여 업로드하고, 수신 위치에서
다음처럼 바이트 수와 해시를 확인한다.

```bash
python pipeline/spark/jobs/batch/verify_research_handoff.py --source-root /path/to/research-snapshot
```

manifest에는 식별에 필요한 핵심 파일을 담았으며 전체 실험 의존성 목록은 아니다.
원평점 archive와 사용자별 입력·레이블은 이용 조건 및 보존된 split/seal과 함께
별도 재현 묶음으로 준비해야 한다. 아직 정해지지 않은 공유 URL을 예시 운영 값으로 만들지 않는다.

## 서비스에 연결하기 전에

1. 118번 전처리의 MovieLens→TMDB→서비스 영화 ID 매핑과 학습 snapshot을 연결한다.
2. 293번 모델 저장 구현에서 공식 `als-artifacts-v1`을 검증한다. 연구 item-only NPZ를
   그대로 네이티브 Spark 모델이나 운영 v1로 표시하지 않는다.
3. Consumer는 Factor가 존재하는 입력만 ID로 맞춘 뒤 Fold-in한다. 영화 순서를 보존하고
   실제 별점과 콘텐츠 상대 가중치, 평가용 Q를 분리한다.
4. 콘텐츠 신규 영화 변환에는 vocabulary·column 순서·변환 버전·미등록 토큰 규칙을 추가한다.
5. 별도 EC2 검증으로 모델 로딩·계산·운영 계약 호환을 확인한 뒤 게시 절차를 따른다.

군집·최종 K·취향/발견 정책을 이 인계 작업의 기본값으로 추가하지 않는다.
