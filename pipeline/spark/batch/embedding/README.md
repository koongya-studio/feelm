# 영화 E5 임베딩 배치

이 패키지는 TMDb 원본 JSON과 확정된 backend 영화 ID 매핑으로부터 `intfloat/multilingual-e5-base`
768차원 벡터를 만들고, 불변 Parquet 산출물로 저장한다. 모델과 tokenizer는 Spark Python
worker마다 한 번만 로드하며, 원본 영화 전체나 임베딩 전체를 driver로 모으지 않는다.
산출물 스키마·manifest·재적재 계약은
[`pipeline/contracts/movie-embedding-artifacts.md`](../../../contracts/movie-embedding-artifacts.md)를 따른다.

현재 `tmdb-e5-v1` 입력 레시피는 필드 양끝의 일반 공백을 제거하고 탭·줄바꿈을 일반
공백 한 칸으로 바꾸지만, 필드 내부의 연속 일반 공백은 유지한다. 이 입력으로 만든
기존 임베딩과 SHA-256을 보존하기 위해 정제 규칙을 변경하지 않는다. 영향 측정과
유지 결정은 [S15P21E106-518 검증 기록](../../../docs/e5-whitespace-518-verification.md)에 있다.

`query: ` 접두사는 모델 카드가 대칭 의미 유사도에서 권장하는 방식이다. `--model-revision`에는
40자리 소문자 Hugging Face commit SHA를 반드시 입력한다. Spark 이미지의 `HF_HOME`은 쓰기 가능한
`/opt/spark/.cache/huggingface`로 지정되어 있으며, 운영 환경이 외부 다운로드를 막으면 해당
commit의 모델을 이미지 빌드 단계 또는 승인된 내부 mirror로 미리 제공해야 한다.

## 생성

```bash
cd pipeline/spark
uv run python -m jobs.batch.movie_embeddings \
  --movies-raw-uri '<TMDB_MOVIES_RAW_URI>' \
  --keywords-raw-uri '<TMDB_KEYWORDS_RAW_URI>' \
  --mapping-uri '<SERVICE_MOVIE_MAPPING_URI>' \
  --mapping-revision 'sha256:<MAPPING_MANIFEST_SHA256>' \
  --output-uri '<IMMUTABLE_ARTIFACT_ROOT>' \
  --embedding-version 'e5-base-<UTC_TIMESTAMP>' \
  --model-revision '<HF_COMMIT_SHA>'
```

출력은 `<output-uri>/movie-embeddings/<embedding-version>/`이며 기존 version은 절대
덮어쓰지 않는다. `metadata/`에는 모델 revision, 차원, 접두사, 매핑 revision 및 행 수가
남는다. `manifest/`에는 모델·텍스트 템플릿·입력 snapshot과 Parquet 파일 SHA-256이 남는다.
생성 전 raw 파일명/본문 TMDb ID, mapping의 일대일성, 모든 mapping 대상의 raw 영화
존재 여부를 검증한다.

## 로컬 CUDA 실행

로컬 NVIDIA GPU에서는 서버 CPU Spark 이미지와 별개로 CUDA PyTorch 환경을 사용한다. 원본
JSON과 mapping Parquet을 노트북의 절대 경로에 stage한 뒤, `local[1]`과 단일 partition으로
실행해 하나의 GPU에 모델을 한 번만 올린다. GPU가 없는 서버 Spark 이미지는 `--device cpu`를
명시하거나 기본 `auto`를 사용한다.

```bash
cd pipeline/spark
uv run spark-submit --master 'local[1]' jobs/batch/movie_embeddings.py \
  --movies-raw-uri 'file:///absolute/path/raw-tmdb' \
  --keywords-raw-uri 'file:///absolute/path/raw-keywords' \
  --mapping-uri 'file:///absolute/path/service-movie-id-map' \
  --mapping-revision 'sha256:<MAPPING_MANIFEST_SHA256>' \
  --output-uri 'file:///absolute/path/artifacts' \
  --embedding-version 'e5-base-<UTC_TIMESTAMP>' \
  --model-revision '<HF_COMMIT_SHA>' \
  --device cuda --partitions 1 --batch-size 32
```

RTX 4070 Laptop(8 GiB)에서는 `--batch-size 32`로 시작하고 CUDA out-of-memory가 나면 16으로
낮춘다. 생성이 끝난 artifact만 HDFS의 같은 불변 version 경로로 올린 뒤 서버에서 dry-run과
적재를 수행한다.

## PostgreSQL dry-run 및 적용

`S15P21E106-419`의 `movie_embeddings` migration이 먼저 적용되어야 한다. DSN은 환경변수로만
전달하며 CLI, 로그, 산출물에 기록하지 않는다.

```bash
export EMBEDDING_DATABASE_DSN='<POSTGRES_DSN>'

# schema, FK 대상, model_version 충돌만 검사하고 롤백
uv run python -m jobs.batch.load_movie_embeddings \
  --output-uri '<IMMUTABLE_ARTIFACT_ROOT>' \
  --embedding-version 'e5-base-<UTC_TIMESTAMP>' \
  --model-version 'e5-base-<HF_COMMIT_SHORT_SHA>'

# dry-run 결과를 확인한 뒤에만 transactionally upsert
uv run python -m jobs.batch.load_movie_embeddings \
  --output-uri '<IMMUTABLE_ARTIFACT_ROOT>' \
  --embedding-version 'e5-base-<UTC_TIMESTAMP>' \
  --model-version 'e5-base-<HF_COMMIT_SHORT_SHA>' \
  --apply
```

적재는 manifest와 파일 해시를 다시 확인하고, 임시 staging table에 1,000건씩 넣어 대상
`movie_embeddings`를 잠근 한 transaction에서 반영한다. `movies.id`와 `movies.tmdb_id`의
연결이 다르거나, 기존 DB에 다른 버전 또는 같은 버전의 다른 벡터·영화 범위가 있으면 본
테이블 변경 없이 rollback한다. manifest가 없는 기존 산출물은 아래 명령으로 새 불변
버전에 복사·검증한 후 dry-run과 적재에 사용한다. 이 과정은 모델 추론을 다시 수행하지 않으며
기존 산출물은 수정하지 않는다.

```bash
uv run python -m jobs.batch.seal_legacy_movie_embeddings \
  --output-uri '<IMMUTABLE_ARTIFACT_ROOT>' \
  --legacy-version '<OLD_EMBEDDING_VERSION>' \
  --new-version '<NEW_EMBEDDING_VERSION>'
```
