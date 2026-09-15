# Pipeline Contracts

파이프라인 내부 단계 사이에서 공유하는 HDFS 경로, Parquet 스키마, 모델 산출물 계약을 관리합니다.

## 계약 목록

- [ALS 모델 산출물 계약](./als-model-artifacts.md): ALSModel, User/Item Factor와 학습 메타데이터
- [서비스 영화 ID 매핑](./movie_id_mapping/service-movie-id-map.md): 118번 전처리의 백엔드 export 입력과 서비스·TMDb·MovieLens ID 매핑
- [콘텐츠 산출물 계약](./content-artifacts.md): 315 콘텐츠 벡터의 오프라인 평가용 v1과 서빙용 v2, TMDb 정규화 스냅샷
