# 노트북 인계와 파일 배치

## 결론

노트북에는 **E106을 별도로 clone한다.** E106은 팀 계약·현재 Spark/Consumer 코드·Jira 작업의
기준이다. 개인 FEELM 저장소와 대용량 연구 자료는 역할이 다르므로 E106 안에 복사하지 않는다.

개인 FEELM의 로컬 43커밋은 2026-09-18에 GitHub `main`으로 push했다. 노트북에서는 GitHub에서
clone하고 revision을 확인한다. `FEELM-all.bundle`은 네트워크 장애나 원격 접근 문제 때만 쓰는
예비본이다.

`FEELM-standalone`은 연결 worktree라 `.git` 파일이 데스크톱의
`C:/higher/projects/FEELM/.git/worktrees/...`를 가리킨다. 폴더 전체를 Git 저장소처럼 복사하면
노트북에서 깨진다. 코드는 GitHub에서 clone하고, Git에 없는 데이터만 상대 경로를 보존해 별도
data root로 전송한다.

이번 실험은 새 TMDB 데이터를 수집하지 않고 기존 snapshot만 사용한다. KOBIS 2,176행은 로컬
weak feature·ablation에 사용할 수 있으며 미매칭 영화는 missing bucket으로 남긴다. 노트북
사양은 로컬 학습에 충분한 것으로 판정한다. 첫 run에서 디스크 여유와 runtime을 기록하되
데스크톱이나 노트북의 실험 시작 blocker로 미리 두지 않는다.

## 권장 배치

```text
C:\higher\
├── projects\
│   ├── S15P21E106\              # GitLab에서 clone한 팀 저장소
│   └── FEELM\                    # GitHub에서 clone한 개인 연구 코드
├── data\
│   ├── feelm-research\           # Git에 없는 outputs/.codex-tmp 자료
│   └── movielens\                # ml-32m.zip과 압축 해제본
├── runs\
│   ├── S15P21E106-622\           # GBT run별 불변 산출물
│   └── S15P21E106-623\           # FM run별 불변 산출물
└── transfer\
    └── S15P21E106-621-20260918\  # bundle, manifest, prompt 원본
```

경로는 예시이며 각 root는 환경변수나 실행 인자로 전달한다. 저장소 문서·manifest·모델 설정에
개인 PC 절대 경로를 기록하지 않는다.

## 인계 순서

### 1. E106

```powershell
git clone https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21E106 C:\higher\projects\S15P21E106
git -C C:\higher\projects\S15P21E106 switch develop
git -C C:\higher\projects\S15P21E106 pull --ff-only origin develop
git -C C:\higher\projects\S15P21E106 status --short --branch
```

621이 병합되기 전 인계라면 작업 브랜치를 fetch해서 확인하되 develop에 임의 병합하지 않는다.
작업은 노트북에서도 Jira별 branch/worktree로 분리한다.

### 2. 개인 FEELM 코드

GitHub에서 clone한다.

```powershell
git clone --branch main https://github.com/itcksgud/FEELM.git C:\higher\projects\FEELM
git -C C:\higher\projects\FEELM switch main
git -C C:\higher\projects\FEELM pull --ff-only origin main
git -C C:\higher\projects\FEELM rev-parse HEAD
git -C C:\higher\projects\FEELM merge-base --is-ancestor `
  e81e84ee3a115af3ab54515a3b6762ecb81a6766 HEAD
```

KOBIS·기존 연구 산출물의 코드 기준은 `e81e84ee3a115af3ab54515a3b6762ecb81a6766`이다.
GitHub HEAD는 이 revision을 포함하는 최신 `origin/main`이면 된다. Git에는 `outputs/`, 대용량
모델과 로컬 runtime이 포함되지 않는다. clone이 불가능할 때만 전송 키트의
`FEELM-all.bundle`을 사용하고 `git bundle verify`를 실행한다.

### 3. 연구 데이터

최소 인계에는 다음을 포함한다.

- `pipeline/artifacts/service-v1.json`의 기존 GBT/FM/ALS·그룹·특징 외부48개.
- [READINESS-MANIFEST.json](READINESS-MANIFEST.json)의 MovieLens 32M archive·CSV 해시,
  데이터 판정, 장비 정보와 blocker.
- [KOBIS-HANDOFF.json](KOBIS-HANDOFF.json)의 2,176행
  `linked-v2/confirmed-movie-comparison.parquet`, 동등 CSV와 설명·검산 자료.
- MovieLens 32M 원 archive와 4개 CSV. 원 archive SHA-256
  `e4a68655d7386b8f95f2f2424b2ff975dfdd15ffd59e0d864a14dca43e99d6ee` 및 GroupLens
  연구 목적·출처 표기·상업 이용 제한을 함께 확인한다.
- 620이 확정한 입력 manifest·split·episode·candidate/label 산출물.
- `data/zero-n-readiness/ml32m-zero-n-readiness-v1`의 작은 고정 표본과 분포 보고서.

72,700행 원관측, `raw/`, 연도별 파일은 영화 단위 파일을 처음부터 재생성할 때만 별도로
전송한다. `linked/`, `linked-v3/`, history, probes, 임시 Python runtime은 최소 인계와 활성
입력에서 제외한다.

전송 후 manifest의 모든 상대 경로를 `C:\higher\data\feelm-research` 아래에 재현하고 bytes와
SHA-256을 다시 계산한다. 파일을 찾기 쉽게 이름을 바꾸거나 한 디렉터리에 평탄화하지 않는다.
현재 전송 키트에는 최소 KOBIS 파일이 이미 아래 구조로 들어 있다.

```powershell
New-Item -ItemType Directory -Path C:\higher\data\feelm-research -Force
Copy-Item -Recurse -Force `
  C:\higher\transfer\S15P21E106-621-20260918\data\feelm-research\* `
  C:\higher\data\feelm-research\
```

MovieLens 32M archive는 전송 키트의 `data/movielens/ml-32m.zip`에 있다. 노트북에서는 별도
MovieLens data root로 복사한 뒤 [READINESS-MANIFEST.json](READINESS-MANIFEST.json)의 SHA-256을
확인하고 압축을 푼다. `data/movielens/README.txt`의 이용 조건도 함께 보존한다.

작은 고정 표본은 전송 키트의 `data/zero-n-readiness/ml32m-zero-n-readiness-v1`에 있다. 621이
병합된 E106에서 다음 검증을 실행한다.

```powershell
python pipeline\docs\assets\service-v1\verify_zero_n_readiness_sample.py `
  --root C:\higher\data\zero-n-readiness\ml32m-zero-n-readiness-v1
```

기대 결과는 사용자600명, episode4,773행, 후보60,000행의 계약 검증 PASS와
`DISTRIBUTION_SHIFT`다. 분포 이동을 PASS로 바꾸기 위해 표본을 다시 뽑지 않는다.

### 4. 실행 환경

```powershell
git --version
python --version
java -version
docker version
nvidia-smi
Get-PSDrive -PSProvider FileSystem
```

E106의 Spark lock과 개인 연구 환경은 합치지 않는다. 새 의존성을 추가하지 말고 각 저장소의
고정 환경을 사용한다. Spark GBT/FM 기준선은 CPU 경로다. GPU 후보를 쓰면 별도 run ID와
dependency lock, CPU 추론 parity를 기록한다.

## 전송 후 검증

서비스 v1 당시 고정 snapshot 검증은 E106 commit `8889887`에서 실행한다.

```powershell
git -C C:\higher\projects\S15P21E106 worktree add --detach `
  C:\higher\projects\S15P21E106-service-v1-snapshot 8889887
python C:\higher\projects\S15P21E106-service-v1-snapshot\pipeline\docs\assets\service-v1\verify_publication.py `
  --source-root C:\higher\data\feelm-research
```

기대 결과는 published119, source104, external48 PASS다. 최신 develop의 게시 검증 실패를
snapshot PASS로 덮어쓰지 않는다. 최신 계약은 최신 E106 clone에서 따로 읽는다.

KOBIS 검증은 `KOBIS-HANDOFF.json`의 각 path에 대해 존재·bytes·SHA-256을 검사한다. 전부
일치하면 로컬 weak feature·ablation 입력으로 사용할 수 있다. 서비스 crosswalk가 없으므로
Discovery KOBIS 채널과 최종 서비스 연결은 아직 활성화하지 않는다.

## 저장 공간

- 전체 개인 연구 작업 공간: 약38.3GiB.
- 검증한 `FEELM-standalone`의 `outputs/`: 33,478,491,937 bytes.
- 최소 KOBIS 인계: 영화 단위 2,176행 Parquet/CSV와 설명·검산 파일 6개, 1,262,885 bytes.
- 245열·4view 특징 자체의 예상치: 약19.6GB이며 Spark spill·checkpoint·모델 산출물은 별도다.

노트북 디스크를 확인하기 전 전체 snapshot을 복사하지 않는다. 최소 manifest부터 옮겨 검증하고,
10% 실행의 실제 spill을 측정한 뒤 전체 run 공간을 예약한다.

## 실험 시작 전 분포 검사

노트북에서도 [READINESS-MANIFEST.json](READINESS-MANIFEST.json)의 `distribution_gate`를 그대로
사용한다. 데스크톱과 노트북이 서로 표본을 다시 뽑지 않고 동일한 split·episode·candidate
digest를 사용해야 한다. 역할×N 필수 셀 부족, 구성 규칙 불일치 또는 누수는 실행 실패로 처리한다.
