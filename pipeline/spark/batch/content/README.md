# Content/ALS Hybrid 오프라인 평가

`features.py`는 TMDB 정규화 행을 네 그룹(genre, context, person, keyword)의 희소
표현으로 바꾸고, 사용자 동일 가중 prior와 supported-history profile을 계산한다.
`artifacts.py`는 이를 `content-artifacts-v1` immutable bundle로 저장·재검증한다.
로컬 실행은 `--output-uri` 아래 계약 경로 `content-artifacts/<content-version>/`에 bundle을,
`content-evaluations/<content-version>/<als-model-version>/<mapping-snapshot>/selection/`에 선택
결과를 각각 공개한다. 세 version 식별자는 공백·`.`·`..`·path separator·NUL을 허용하지 않아
각 canonical filesystem path가 한 tuple에만 대응한다. `--reuse-existing-artifact`는 같은 content/ALS/mapping lineage의 공개
artifact만 재사용해 evaluation을 재시작하며, ALS·mapping만 바꾼 재사용은 거부한다. 입력 score
rows의 `content_score`는 사용하지 않으며, content score는 재로딩한 artifact와 stage-known
이력의 prior/profile로만 계산한다.

`batch.evaluation.content_hybrid`의 입력 경계는 다음과 같다.

- 품질: MATCHED만, known 제외, holdout 양성(별점 4 이상)은 지원 불가여도 분모에 남긴다.
- 커버리지: MATCHED와 SERVICE_ONLY_CONTENT_BASED를 따로 집계하며 ambiguous는 제외한다.
- 선택: validation에서 fixed alpha 0.25/0.5/0.75만 비교한다. test는 고정한 3개 정책만
  한 번 평가한다.

로컬 검증은 `cd pipeline/spark && uv run pytest -q tests/batch/test_content_*.py`로 한다.
이는 HDFS 권한·분산 재시도·Redis 게시를 입증하지 않는다. 경로와 reader 계약은
[`pipeline/contracts/content-artifacts.md`](../../../contracts/content-artifacts.md)를 따른다.
