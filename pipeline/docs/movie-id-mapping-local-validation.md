# S15P21E106-118 로컬 검증

이 업무의 완료 범위는 **개인 PC에서 MovieLens 전처리·서비스 영화 ID 매핑을 fixture로
검증하는 것**이다. 전체 MovieLens 32M, 실제 PostgreSQL export, 공용 EC2·HDFS에서의
분산 실행은 별도 통합 검증 범위로 남긴다.

## 실행 환경과 결과

2026-09-08, Windows의 Docker Desktop Linux 컨테이너에서 실행했다.

| 항목 | 확인한 값 |
| --- | --- |
| 이미지 | `apache/spark:4.1.3-scala2.13-java21-python3-ubuntu` |
| 이미지 digest | `sha256:bf9d035a7c32a8ca46aa58d6348182ffd7d2dff6409206ecfbb3915ff1fef211` |
| Spark / Python / Java | 4.1.3 / 3.10.12 / Temurin 21.0.11 |
| 실행 방식 | `local[1]`, driver 1GiB, 컨테이너 메모리 4GiB·CPU 2개 |
| 입력 | 저장소에 포함한 합성 CSV fixture 4종 |
| 통합 테스트 | **5개 통과**, 실패·오류 0개 |
| CLI 직접 실행 | `movie_id_mapping.py` 종료 코드 0, 검증 리포트 검사 6개 통과 |
| 정적 검사 | Ruff 통과 |

테스트는 중복 평점의 최신값 보존, 잘못된 평점 제외, TMDb ID 누락·모호 매핑,
필수 백엔드 export·빈 export·중복 export 거부를 확인했다.
실제 Parquet 4종과 검증 JSON을 쓴 뒤 다시 읽었으며, 기존 ALS의
`load_training_inputs()`에도 생성된 평점·서비스 매핑 Parquet을 전달해 통과했다.

| fixture 산출물 | 행 수 |
| --- | ---: |
| ratings | 2 |
| movies | 3 |
| movie-id-map | 2 |
| service-movie-id-map | 3 |

검증 리포트의 행 수·참조 검사 6개가 모두 통과했고, 서비스 매핑 중 `MATCHED`는 1개였다.
이 건수는 소형 fixture 결과이며 32M 전체 데이터 처리 건수가 아니다.
실행 환경과 실제 CLI 집계는 [로컬 결과 JSON](assets/movie-id-mapping-local-validation.json)에 보관했다.

## 로컬 재실행

저장소 루트에서 Docker로 실행할 수 있다. 아래 셸 예시는 Linux/WSL 기준이다.
소스는 읽기 전용으로 연결하고 산출물은 컨테이너 임시 디렉터리에 생성한다.

```bash
docker run --rm --network none \
  --hostname feelm118-local --add-host feelm118-local:127.0.0.1 \
  --cpus 2 --memory 4g \
  --mount "type=bind,source=$PWD/pipeline/spark,target=/workspace,readonly" \
  --workdir /workspace \
  --env PYTHONPATH=/workspace --env PYTHONDONTWRITEBYTECODE=1 \
  --env SPARK_LOCAL_IP=127.0.0.1 \
  --entrypoint /opt/spark/bin/spark-submit \
  apache/spark:4.1.3-scala2.13-java21-python3-ubuntu \
  --master 'local[1]' --driver-memory 1g \
  --conf spark.sql.warehouse.dir=/tmp/feelm118-warehouse \
  tests/batch/movie_id_mapping/test_pipeline.py -v
```

기존 팀 `uv` 런타임에서도 [전처리 README](../spark/batch/prepare/movie_id_mapping/README.md)의
명령으로 검증할 수 있다. Spark Master는 CLI 또는 기존
`RECOMMENDATION_SPARK_MASTER`를 사용하며 새로운 환경변수는 추가하지 않았다.

## 기존 브랜치에서 통합한 범위

기존 `feat/S15P21E106-118-movielens-tmdb-preprocessing`의 구현을 최신 `develop`의
`pipeline/spark/batch/prepare/movie_id_mapping/`에 배치했다. 옛 `data/` 최상위 폴더와
별도 런타임 설치 스크립트는 다시 만들지 않는다.

서비스 영화 export·Parquet 스키마와 상태는 기존 118 설계 및 ALS 입력 계약을 유지한다.
TMDb 수집·정제는 132번, ALS 학습·저장은 해당 후속 업무가 담당한다.
서버 배포나 운영 데이터 검증을 완료했다는 의미로 이 로컬 결과를 사용하지 않는다.
