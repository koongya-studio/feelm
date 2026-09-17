# 영화 E5 임베딩 산출물 계약

`movie-embeddings-v1`은 `<output-uri>/movie-embeddings/<embedding-version>/`에 게시되는 불변
디렉터리다. `<embedding-version>`은 영문자·숫자로 시작하고 이후 영문자·숫자·점·밑줄·하이픈만
허용한다. 동일 버전 경로를 덮어쓰지 않는다.

```text
<embedding-version>/
├── embeddings/   # Parquet
├── metadata/     # 단일 행 Parquet
└── manifest/     # 단일 JSON 문자열 행 Parquet
```

`embeddings`는 `movie_id:long`, `tmdb_id:long`, `mapping_revision:string`,
`input_text_sha256:string`, `embedding:array<float>`를 가진다. `movie_id`는 양의 고유 서비스
영화 ID이고, `tmdb_id`는 같은 영화의 TMDb ID다. 벡터는 유한한 768차원 L2 단위 벡터다.

`metadata`는 embedding version, 모델 ID와 40자리 commit revision, 768차원, `query: `
접두사, 최대 토큰 수, mapping revision, 행 수, 텍스트 템플릿 버전
`tmdb-e5-v1`, `input_snapshot_sha256`을 기록한다. 입력 snapshot 해시는 `movie_id` 오름차순으로
각 행의 `movie_id:tmdb_id:input_text_sha256\n`을 이어 SHA-256 계산한다.

`manifest` JSON은 `contract_version=movie-embeddings-v1`, 위 metadata 필드와 `files`를
담는다. `files`는 `embeddings/`와 `metadata/` 아래 보이는 일반 파일의 상대 경로에서 파일
SHA-256으로 가는 매핑이다. 읽는 쪽은 실제 파일 집합·해시와 metadata·행 수·입력 snapshot
해시를 다시 계산해 일치할 때만 적재할 수 있다. 기존 manifest 없는 산출물은 이 계약의
입력으로 취급하지 않는다. `seal_legacy_movie_embeddings`는 기존 Parquet의 스키마·행 수·
모델·매핑 정보를 검증하고 새 버전으로 복사하면서 v1 manifest를 생성한다. 기존 버전을
수정하지 않으며 원본 입력의 과거 출처까지 새로 입증하는 절차는 아니다.

DB `model_version`은 `e5-base-<모델 commit SHA 앞 12자리>`이다. 로더는 metadata와 이 값이
맞는지 확인한다. 기존 DB에 같은 버전이 있으면 영화 ID 집합과 벡터가 산출물과 정확히
같은 재시도만 허용한다. DB `movies.id`·`movies.tmdb_id`도 staging의 두 ID와 대조한다.
