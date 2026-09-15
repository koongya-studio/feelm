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

## content-artifacts-v2 서빙 산출물

`serving.py`는 같은 315 표현을 서비스 영화 전체에 대해 서빙용 Parquet 번들로 만든다. 경로·스키마·
소비 조건은 [콘텐츠 산출물 계약 Part B](../../../contracts/content-artifacts.md#part-b-content-artifacts-v2-서빙)를
따른다. 벡터는 Spark SQL로 계산하고 vocabulary 정렬과 prior는 315 `build_vocabulary`·
`user_equal_prior`를 직접 호출한다. `batch/prepare/tmdb_snapshot.py`는 TMDb 영화·출연진·키워드 원본
JSON을 서비스 카탈로그 범위의 정규화 스냅샷으로 만든다.

아래 순서로 불변 경로에 공개한다. 앞 단계의 revision을 다음 단계에 명시적으로 전달한다. URI는 모두
`file:///절대/경로` 또는 `hdfs://authority/절대/경로`로 쓰고 `latest` 같은 별칭을 쓰지 않는다.

```bash
cd pipeline/spark
# 1. 영화 ID 매핑 스냅샷(118). service-movie-id-map의 mapping_revision을 확인한다.
uv run python -m jobs.batch.movie_id_mapping --spark-master "$MASTER" \
  --ratings "$ML/ratings.csv" --movies "$ML/movies.csv" --links "$ML/links.csv" \
  --service-movies "$SERVICE_MOVIES_CSV" --output "$ROOT/snapshots/movie-mapping/$MAPPING_ID"
# 2. TMDb 정규화 스냅샷
uv run python -m jobs.batch.tmdb_snapshot --spark-master "$MASTER" \
  --movies-raw-uri "$TMDB_RAW/raw-tmdb" --keywords-raw-uri "$TMDB_RAW/raw-keywords" \
  --mapping-uri "$MAPPING/service-movie-id-map" --mapping-revision "$MAPPING_REVISION" \
  --output-uri "$ROOT/snapshots/tmdb/$TMDB_ID"
# 3. 영화 메타데이터 스냅샷(서비스 매핑 전체)
uv run python -m jobs.batch.movie_metadata --spark-master "$MASTER" \
  --mapping-uri "$MAPPING/service-movie-id-map" --tmdb-movies-uri "$ROOT/snapshots/tmdb/$TMDB_ID" \
  --input-snapshot "$INPUT_SNAPSHOT" --output-uri "$ROOT/snapshots/movie-metadata/$METADATA_ID"
# 4. ALS SERVING(als-artifacts-v2)
uv run python -m jobs.batch.als_serving --spark-master "$MASTER" \
  --ratings-uri "$MAPPING/ratings" --mapping-uri "$MAPPING/service-movie-id-map" \
  --movie-metadata-uri "$ROOT/snapshots/movie-metadata/$METADATA_ID" \
  --mapping-revision "$MAPPING_REVISION" --movie-metadata-revision "$METADATA_REVISION" \
  --input-snapshot "$INPUT_SNAPSHOT" --output-uri "$ROOT" --model-version "$MODEL_VERSION" \
  --rank 32 --reg-param 0.1 --max-iter 10 --seed 42
# 5. content-artifacts-v2 (ratings와 매핑은 --mapping-snapshot-uri 한 루트에서만 읽는다)
uv run python -m jobs.batch.content_serving --spark-master "$MASTER" \
  --mapping-snapshot-uri "$MAPPING" --mapping-revision "$MAPPING_REVISION" \
  --tmdb-snapshot-uri "$ROOT/snapshots/tmdb/$TMDB_ID" \
  --tmdb-snapshot-revision "$TMDB_SNAPSHOT_REVISION" \
  --movie-metadata-uri "$ROOT/snapshots/movie-metadata/$METADATA_ID" \
  --movie-metadata-revision "$METADATA_REVISION" --input-snapshot "$INPUT_SNAPSHOT" \
  --output-uri "$ROOT" --content-version "$CONTENT_VERSION" --implementation-revision "$REVISION"
```

`$MAPPING`은 `$ROOT/snapshots/movie-mapping/$MAPPING_ID`다. 각 revision은 앞 단계의 산출물에서 읽는다.
`$MAPPING_REVISION`은 `service-movie-id-map/`의 `mapping_revision`, `$TMDB_SNAPSHOT_REVISION`은 2단계
Job이 출력하는 JSON의 `tmdb_snapshot_revision`, `$METADATA_REVISION`은 공개된 메타데이터 스냅샷의
`movie_metadata_revision`이다. Consumer에는 같은 `$ROOT` 아래
`als/$MODEL_VERSION`, 매핑 `service-movie-id-map`, 영화 메타데이터, `content/$CONTENT_VERSION`을 함께 전달한다.

### 로컬에서 만든 번들을 HDFS에 올리기

로컬 번들은 루트 아래 상대 경로를 그대로 유지해 올린다. 공개 원칙은 생성 Job과 같다. 대조와 공개는
`jobs.batch.artifact_transfer`가 수행한다. **셸 `hdfs dfs -mv`로 공개하지 않는다.** 최종 경로가 이미
있으면 그 디렉터리 *안으로* 옮기고 성공으로 끝나서 버전을 조용히 망가뜨린다.

```bash
cd pipeline/spark
# 1. 로컬 번들의 파일 목록·바이트·SHA-256 manifest (_SUCCESS 포함, .crc 사이드카만 제외)
uv run python -m jobs.batch.artifact_transfer manifest --root "$LOCAL_BUNDLE" \
  --artifact "mapping=snapshots/movie-mapping/$MAPPING_ID" \
  --artifact "tmdb=snapshots/tmdb/$TMDB_ID" \
  --artifact "movie-metadata=snapshots/movie-metadata/$METADATA_ID" \
  --artifact "als=als/$MODEL_VERSION" --artifact "content=content/$CONTENT_VERSION" \
  --output "$LOCAL_BUNDLE/upload-manifest.json"
# 2. 최종 경로와 같은 부모 아래 숨김 스테이징 경로로 올린다(복제 수는 승인값을 명시)
hdfs dfs -D dfs.replication="$REPLICATION" -put "$LOCAL_BUNDLE/content/$CONTENT_VERSION" \
  "$HDFS_ROOT/content/.$CONTENT_VERSION.staging"
# 3. 파일 목록·바이트·SHA-256을 manifest와 대조하고 덮어쓰기 없이 공개한다
uv run python -m jobs.batch.artifact_transfer publish --spark-master "$MASTER" \
  --manifest "$LOCAL_BUNDLE/upload-manifest.json" --artifact content \
  --staged-uri "$HDFS_ROOT/content/.$CONTENT_VERSION.staging" \
  --final-uri "$HDFS_ROOT/content/$CONTENT_VERSION"
```

`publish`는 로컬은 부모 디렉터리 잠금, HDFS는 `Rename.NONE`으로 rename하며, 최종 경로가 이미 있거나
스테이징 파일 목록·바이트·SHA-256이 manifest와 다르면 아무것도 공개하지 않는다. 대조 대상은 `_SUCCESS`를
포함한 모든 파일이고 로컬 전용 `.crc` 사이드카만 제외한다. SHA-256 대조는 기본 동작이며 전 파일을 다시
읽으므로 느리다. `--skip-sha256-verify`는 계약이 요구하는 대조를 건너뛰므로 이유를 남길 수 있을 때만 쓴다.

산출물마다 2~3단계를 매핑 → TMDb → 영화 메타데이터 → ALS → content 순서로 반복하고, 마지막에 Spark
reader(`load_als_artifacts`, `load_content_artifacts`)와 Consumer 로더로 다시 읽어 revision 일치를
확인한다. 실패해 남은 스테이징 경로는 숨김 이름이라 reader가 무시하므로 다음 시도 전에 지운다.

서비스 DB를 다시 적재해 `movies.id`가 바뀌면 매핑 revision이 달라지므로 1단계부터 새 버전으로 다시 만든다.

로컬 검증:

```bash
cd pipeline/spark
uv run pytest -q tests/batch/test_tmdb_snapshot.py tests/batch/test_content_serving_artifacts.py \
  tests/batch/test_content_serving_local_e2e.py tests/batch/test_movie_metadata.py \
  tests/batch/test_artifact_transfer.py
```

로컬 검증은 HDFS 권한·분산 실행·Consumer 점수 결합(S15P21E106-417)을 입증하지 않는다.
