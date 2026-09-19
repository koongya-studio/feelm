# 재현 범위와 EC2 준비

현재 개발 방향과 legacy 구분은 [CURRENT-DIRECTION](CURRENT-DIRECTION.md)을 먼저 따른다.

Git만 받아도 설계·원본 설정값·작은 결과표·그림·참고 수식·합성 검증을 확인할 수 있다.
모델·사용자별 입력·Parquet은 Git에 없으며, 전체 실험 재학습 또는 서비스 실행 패키지는 아니다.

## Git만으로 확인

저장소 루트에서 Python 표준 라이브러리로 게시 파일의 해시·JSON 값·문서 링크를 검사한다.
PPTX의 XML 본문·발표자 노트·관계 속성도 읽어 Windows 절대 경로, UNC 경로와 `file:/` 참조를 거부한다.
이 검사는 링크의 실제 접근 권한이나 PowerPoint 실행 결과를 확인하지 않는다.

```bash
python pipeline/docs/assets/service-v1/verify_publication.py
```

게시 검사기의 회귀 테스트10개는 Python 표준 라이브러리만 사용한다. 문단 안의 run 분할,
문단·줄바꿈 경계, XML entity·확장 메타데이터·관계 속성에서 정상 HTTPS와 실제 로컬 경로를 구분한다.

```bash
python -B -m unittest discover -s pipeline/docs/assets/service-v1 -p "test_verify_publication.py"
```

별도 연구용 Python 환경에서 아래 명령으로 합성 테스트를 실행한다. 이번 확인 환경은
Python3.12.5, NumPy1.26.4, pandas2.2.3, SciPy1.15.2이다. 생산 프로젝트의 의존성/lock을 변경하지 않는다.

```bash
python -B -m unittest discover -s pipeline/docs/assets/service-v1/reference -p "test_final344_*.py"
```

33개 기존 테스트가 RH230 특징 생성·평가 및 후보 선택 수식의 합성 입력을 검증한다.
테스트 로그의 `SELECTION_CALIBRATION_SEALED` 등은 mock 메시지다. 실제 학습·예측을 새로
만들었다는 뜻이 아니다. 이 테스트는 새245열·FM portable 추론·GBT tree 추론·ALS fold-in・실제 모델 native parity·서비스 추천 품질·EC2를 입증하지 않는다.

## 참고 코드 사용

- [reference](../assets/service-v1/reference/README.md): RH230 특징, ALS 지원 판정/fold-in, GBT·FM 계산과 합성 테스트. 직접 import 가능한10개 파일.
- [과거 실행 소스](../assets/service-v1/source/README.md): 원본 실행기·KOBIS 수집/연결·그룹 수식은 `.py.txt`로 보존한다. 당시 경로와 외부 의존성이 있는 참고 자료다.
- `final344_evaluate.py` CLI도 `final344_common`·원 split/config·모델 seal 없이는 전체 실험을 재실행할 수 없다. 위 unittest가 공개 패키지의 검증 진입점이다.
- 기존 Spark worker는230열·원4,997,069행 전제다.245열/4view의 실행기로 사용하지 않는다. 신규 구현은 [MODELS](MODELS.md)와 [학습 설정](../../configs/service-v1/training-recipe.v1.json)을 따른다.

## External artifacts

[외부 파일 목록](../../artifacts/service-v1.json)에 `source_path`, bytes, SHA256을 기록했다.
`source_path`는 FEELM-standalone 연구 스냅샷 안의 상대 식별자다. 개인 PC 절대 경로나 EC2 위치가 아니다.
`LOCAL_ONLY`, `shared_uri:null`은 아직 공유 저장소에 업로드하지 않았다는 뜻이다.

1. 전달자가 기록된 원본을 별도 저장소/전송 경로에 준비한다. 사용자별 MovieLens 데이터·본문과 모델의 배포 조건을 같이 확인한다.
2. 수신 디렉터리에 `source_path`의 상대 구조를 유지한다. 인증정보는 환경에서 제공하고 문서나 manifest에 넣지 않는다.
3. 다음 명령으로 실제 파일의 바이트/SHA256을 대조한다. 누락·변조는 실패한다.

```bash
python pipeline/docs/assets/service-v1/verify_publication.py --artifact-root /path/to/transferred-artifacts
```

이 목록은 현재 v1에 재사용할 선택 파일 목록이다. 과거 실험 전체의 의존성 폐쇄 목록은 아니다.
원 MovieLens archive·TMDB/Wiki 원문·추가 feature matrix·seal 의존 묶음 없이 과거 학습 수치를
완전히 재생산할 수 있다고 주장하지 않는다. `NOT_BUILT` 파일에는 가짜 해시나 다운로드 주소를 넣지 않았다.

`--artifact-root`는 외부 목록48개만 요구한다. 전달자의 원본 연구 스냅샷이 있을 때는
`--source-root /path/to/research-snapshot`으로 복사 출처104개·원본 설정값과 외부 파일을 모두
검사할 수 있다. 이 옵션은 과거 원본문서도 요구하므로 외부 모델·데이터만 받은 수신자가 사용할 명령이 아니다.

## Historical records

[provenance.json](../assets/service-v1/provenance.json)의 `source`는 원본 해시,
최상위 `sha256`은 이관한 파일 해시다. 텍스트는 LF로 정규화하고 Markdown 링크/게시 상태 문구만
이관에 맞춰 고쳤다. 역사 기록의 개인 PC 절대 경로는 `<research-root>` 또는 `<MOVIELENS_ARCHIVE>`로 치환하고
출처 목록에 변환 사실을 남겼다. 설정6개는 JSON 값을 바꾸지 않았다. 별도 [게시 목록](../assets/service-v1/publication-files.json)은 새 안내문·검증기까지 포함한다.

발표 PPTX는 원본과 게시본의 해시가 다르다. 6장 발표자 노트의 개인 PC 출처를 고정 GitLab
커밋의 자료 링크로 바꿨다. 슬라이드·차트·내장 workbook 등 나머지 ZIP 항목은 바이트가 동일하다.
FM/GBT의 신뢰구간과 첫2편 고유 영화 수는 `final344-primary-contrasts.csv`와
`final344-catalog-summary.csv`를 원본 그대로 추가해 추적할 수 있다. 기존 결과의 게시이며 재학습이 아니다.

과거 review JSON의 내부 경로와 before 해시는 당시 연구 버전의 식별자다. 원본 문서 전체의
과거 revision은 로컬에 보존하며 이번 Git에 전부 복사하지 않았다. 게시 문서 해시가 그 과거
해시와 다르다는 이유로 새 학습 결과가 생긴 것은 아니다. 이전 PASS를 최신 v1 구현 PASS로 쓰지 않는다.
미포함 역사 링크는 이 절로 연결하고 `historical_links_redirected`에 원래 대상을 남겼다.
미포함 그림3곳도 이미지 대신 이 절로 연결하는 일반 안내 링크로 표시한다.

## 로컬 학습과 서버 승격 준비 순서

학습은 데스크톱·노트북에서 수행하고 두 장비를 분산 클러스터로 묶지 않는다. 서버 Spark/HDFS는
정규 입력·산출물과 CPU 추론 호환성 검증에 사용한다. 분산 Spark는 대규모 ETL·후보 생성의 실행
수단이며 GBT/FM 기준 학습의 필수 조건으로 간주하지 않는다.

1. 실제 artifact 전송·해시 검증, 검증된 서비스 ID export 및 유사 영화 인덱스 fixture 준비.
2. 전체 KOBIS 자료에서 검증 crosswalk·동일 누적 기준일과 0~N snapshot 시점 일치 확인.
3. 0~N 계약의 사용자 분리와 전역 시점 창으로 TRAIN/VALIDATION/FINAL_TEST 입력을 만든다. 모든
   실험이 공유할 episode·후보를 동결하고 사용자 활동·N·target 지원량·시점·콘텐츠·결측 분포의
   정합성 report를 만든 뒤 lineage를 검사한다.
4. 새 실행 코드와 모델별 특징의 디스크/spill·메모리를 작은 재현→10%→전체 순으로 검토한 뒤
   GBT/FM을 장비별로 실행한다. legacy245 네 view 약1,999만 행은 비교 후보이며 필수 최종 입력이 아니다.
5. VALIDATION에서 모델·특징을 고정한 뒤 FINAL_TEST는 한 번만 확인하고, S15P21E106-625에서 목표 사용자 평가를 수행한다.
6. 선택된 모델 bundle을 EC2에 연결한 뒤 별도 실제환경 검수한다. 데이터 하한 미달이면 활성화 준비를 중단한다.

원230열 parity와 보정45·선정45·확인180은 legacy245 B0~B3를 재현할 때만 별도 수행한다.
이 고정 집합을 새 0~N 모델의 split이나 승격 기준으로 사용하지 않는다.

Spark reference는4.1.3/Java21, Docker4CPU·12GiB·driver8GiB다. KOBIS 수집 때의
Python3.12.5/pandas2.2.3/pyarrow23.0.1은 Spark Docker의 pyarrow19.0.1과 별도 환경이다.
90분 상한/OOM 때 자동 증설·행 축소·재시도를 하지 않는 새 학습 조건은 MODELS에 있다.
