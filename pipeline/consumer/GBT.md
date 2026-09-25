# v26 GBT 독립 서빙 (626)

이번 동결 번들의 식별자·실측 검증은 [GBT-626-VERIFICATION.md](./GBT-626-VERIFICATION.md)에 기록한다.

[artifact 계약](../contracts/gbt-model-artifacts.md)이 기준이다. 개인·파티 event loop 연결은
[627](GBT-627.md)·[747](GBT-747.md), 표준 이미지 실행은 [644](GBT-644.md)에 반영됐다.
서버 적용도 완료된 상태이며, 확인 기준과 검증 기록은 [현재 안내](../docs/gbt-v26/README.md)를 따른다.

## 설치와 합성 테스트

표준 Consumer lock/이미지는644에서 GBT CPU 런타임을 포함하도록 갱신됐다.
626 독립 재현 시에는 Python3.12 별도 환경에서 `gbt-runtime-requirements.txt`를 설치한다.
NumPy2.4.6, PyArrow19.0.1, XGBoost CPU2.1.4, SciPy1.15.2를 고정한다.
PyArrow는 기존 Consumer의 `<20` 범위를 보존한다. pandas/Spark·연구 폴더·ALS/FM 모델은 불필요하다.

```bash
python -m pip install -r pipeline/consumer/gbt-runtime-requirements.txt
PYTHONPATH=pipeline/consumer/src python -m pytest pipeline/consumer/tests/test_gbt_bundle.py -q
```

개발 테스트에는 Consumer dev/test 의존성도 필요하다. 기존 ALS 환경에서는 GBT 테스트가 skip되므로
아래 독립 환경 테스트를 별도로 반드시 실행한다. skip 결과만으로 GBT 검증을 통과 처리하지 않는다.

```bash
docker build --target test -f pipeline/consumer/tools/Dockerfile.gbt-verify -t feelm-gbt-verify pipeline/consumer
docker run --rm --network none --read-only --tmpfs /tmp:rw,size=256m \
  -e PYTHONPATH=/work/src -v "$PWD/pipeline/consumer:/work:ro" -w /work \
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

## 호출 예시와 연결 문서

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
    pass  # 개인627은 reach 인기순으로 처리. 파티 전원K0는 별도 공급 없이 격리.
else:
    personal_top500 = result.ranked_ids(500)
# 파티 구성원별 동일 점수. 집계 자체는747에서 수행한다.
party_member = scorer.score(actual_ratings, candidate_ids=party_pool,
                            dismissed_movie_ids=dismissed_ids,
                            excluded_movie_ids=party_common_exclusions)
```

| 연결 업무 | 경계 |
| --- | --- |
| 742/829 | 상태·불변 입력 참조와 현행 파티3필드 요청 계약. develop 반영 |
| 627 | 개인 입력/후보·지원 불가 격리·K0 인기순 공급. develop 반영 |
| 747 | 파티 TASTE+DISCOVERY 합집합·구성원 subset 점수·집계·복구. develop 반영 |
| 644 | 표준 이미지·환경 연결 반영. GBT 서버 적용 완료(2026-09-25 사용자 확인) |
| 483/628 | 추가 fallback/별도 종단 검증은 미진행 종료. 현재의 미완료 구현 지시로 사용하지 않음 |

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
644 검증 기록에는 HDFS의5파일과 전달 ZIP 해시가 일치한 관찰이 있다. 실제 loader 재시작·
replication·이전 버전 복원 성공까지 의미하지 않는다.
이 작업의 로컬 도구는 서버 파일·활성 포인터·DB·Jira를 변경하지 않는다.
