# v26 GBT 독립 서빙 (626)

이번 동결 번들의 식별자·실측 검증은 [GBT-626-VERIFICATION.md](./GBT-626-VERIFICATION.md)에 기록한다.

[artifact 계약](../contracts/gbt-model-artifacts.md)이 기준이다. 이 모듈은 아직 기존 ALS 개인/파티
event loop에 연결하지 않았다. 코드·번들 검증 성공과 운영 교체 완료는 다르다.

## 설치와 합성 테스트

Python3.12 별도 환경에서 `gbt-runtime-requirements.txt`를 설치한다. 현재 Consumer lock/이미지는
수정하지 않았다. NumPy2.4.6, PyArrow19.0.1, XGBoost CPU2.1.4, SciPy1.15.2를 고정한다.
PyArrow는 기존 Consumer의 `<20` 범위를 보존한다. pandas/Spark·연구 폴더·ALS/FM 모델은 불필요하다.

```bash
python -m pip install -r pipeline/consumer/gbt-runtime-requirements.txt
PYTHONPATH=pipeline/consumer/src python -m pytest pipeline/consumer/tests/test_gbt_bundle.py -q
```

개발 테스트에는 Consumer dev/test 의존성도 필요하다. 기존 ALS 환경에서는 GBT 테스트가 skip되므로
아래 독립 환경 테스트를 별도로 반드시 실행한다. skip 결과만으로 GBT 검증을 통과 처리하지 않는다.

```bash
docker build --target test -f tools/Dockerfile.gbt-verify -t feelm-gbt-verify .
docker run --rm --network none --read-only --tmpfs /tmp:rw,size=256m \
  -e PYTHONPATH=/work/src -v "$PWD:/work:ro" -w /work \
  feelm-gbt-verify -m pytest -p no:cacheprovider tests -q
```

## 데이터 전용 번들 export

다음 명령은 원본 모델 SHA를 검증하고 whitelist만 옮긴다. 실제 사용자 평가·닉네임·추천 HTML은
필요하지 않다. `catalog`, `references`, `model`, `schema`는625의 동일 동결 snapshot을 전달한다.
원본 references ID축/개봉 mask를 그대로 보존한다. 모델은 다시 학습하지 않는다.

```bash
PYTHONPATH=pipeline/consumer/src python pipeline/consumer/tools/export_gbt_bundle.py \
  --catalog /private/frozen/catalog.parquet \
  --references /private/frozen/public_references.parquet \
  --model /private/frozen/gbt.json --schema /private/frozen/feature-schema.json \
  --snapshot verified-full-catalog-v1-20260920 --candidate-cutoff 2026-09-21 \
  --output /private/gbt/gbt-v26-top2-raw --model-version gbt-v26-top2-raw
```

출력 `serving_revision`을 독립 인계 기록에 pin한다. code/runtime이 달라지면 loader는 실패한다.
수정 후 기존 manifest만 덮어쓰지 말고 새 버전 디렉터리로 export→전체 parity를 다시 수행한다.
원본 학습 schema와 새 canonical schema의 SHA가 다른 것은 직렬화 차이다. 특징 순서·의미는 같다.

```bash
PYTHONPATH=pipeline/consumer/src python pipeline/consumer/tools/verify_gbt_bundle.py \
  --bundle /private/gbt/gbt-v26-top2-raw --expected-revision sha256:<pinned-manifest-sha> \
  --live-mapping /private/current-service-id-tmdb-pairs.csv
```

live CSV에는 `service_movie_id,tmdb_id` 두 열만 담는다. 신규/삭제/재결합/지원률만 출력한다.
실제 서비스 export 없이 snapshot만 확인했다면 운영 매핑 감사를 완료했다고 쓰지 않는다.

## 호출 예시와 후속 업무

```python
from feelm_consumer.gbt.artifacts import load_bundle
from feelm_consumer.gbt.scorer import Scorer

bundle = load_bundle(bundle_uri, expected_revision=pinned_revision, threads=2)
scorer = Scorer(bundle)  # 프로세스 기동 때 한 번
result = scorer.score(
    [(service_id, score) for service_id, score in actual_ratings],
    dismissed_movie_ids=dismissed_ids,
    excluded_movie_ids=caller_mode_exclusions,
)
if result.status == "NEEDS_COLD_START":
    pass  # 483/627에서 처리. 0점 개인 후보를 만들지 않는다.
else:
    personal_top500 = result.ranked_ids(500)
# 파티 구성원별 동일 점수. 집계 자체는747에서 수행한다.
party_member = scorer.score(actual_ratings, candidate_ids=party_pool,
                            dismissed_movie_ids=dismissed_ids,
                            excluded_movie_ids=party_common_exclusions)
```

| 후속 | 경계 |
| --- | --- |
| 742 | modelVersion/servingRevision 등 상태 계약 확정; 여기서 Redis 포맷을 확정하지 않음 |
| 627 | 개인 Consumer 입력/후보 호출, 지원 불가·K0 분기, 기존 최종 제외 유지 |
| 747 | 파티 개인 후보 합집합·구성원 subset 점수·집계, 공통 감상/관심없음 제외 |
| 644 | 운영 이미지 의존성 통합·실서버 자원/네트워크/재시작 검증 |
| 628/735 | 승인 후 전환·전체 재게시/복원 검증 |

## 동결 parity와 리소스

`verify_gbt_frozen_raw_parity.py --help`의 private fixture 형식으로 실제 동결 결과와 비교한다.
기대값은 새 구현으로 생성하면 안 된다. 원본 scorer/저장된 원점수에서 가져온 독립 기준이어야 한다.
전체 점수≤1e-6, Top100/500 완전 일치, 71특징≤1e-6을 요구한다. 입력 상한/부분 평가로 타협하지 않는다.
K0/1/3/17/37/101/대규모, 실제 우선·관심없음 취소·명시 제외·full/subset을 포함한다.

리소스 검증은 연구 경로 없이 코드+완성 bundle+읽기 전용 private fixture만 mount한다.
`--cpus 2 --memory 4g --memory-swap 4g --network none --read-only`로 단독 및2프로세스를 실행한다.
이 제한은 실험의 재현 조건이지 현재 서버에 실제로 예약된 Consumer 메모리라는 뜻이 아니다.
report에 RSS/cgroup/OOM/시간/runtime을 남긴다. 같은 CPU quota도 실제 서버 CPU 지연을 보장하지 않는다.

## 전송과 이전 버전 복원

승인된 목적지가 정해진 뒤 Spark `jobs.batch.artifact_transfer manifest/publish`를 사용한다.
bundle5파일 전체를 포함한 별도 전송 manifest를 만들고 sibling staging으로 업로드한 뒤
SHA·크기·전체 목록을 검증해서 no-overwrite 게시한다. `--skip-sha256-verify` 금지.
실제 HDFS URI, replication, loader 재시작과 이전 버전 복원 증거 없이는626 게시 완료가 아니다.
이 작업의 로컬 도구는 서버 파일·활성 포인터·DB·Jira를 변경하지 않는다.
