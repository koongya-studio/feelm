# Pipeline Contracts

파이프라인 내부 단계 사이에서 공유하는 HDFS 경로, Parquet 스키마, 모델 산출물 계약을 관리합니다.

## 계약 목록

- [ALS 모델 산출물 계약](./als-model-artifacts.md): ALSModel, User/Item Factor와 학습 메타데이터
- [v26 GBT 전용 서빙 번들](./gbt-model-artifacts.md): 71특징 원점수, 모든 K 입력, 불변 데이터 번들·공통 scorer·검증과 게시 경계
- [서비스 영화 ID 매핑](./movie_id_mapping/service-movie-id-map.md): 118번 전처리의 백엔드 export 입력과 서비스·TMDb·MovieLens ID 매핑
- [콘텐츠 산출물 계약](./content-artifacts.md): 315 콘텐츠 벡터의 오프라인 평가용 v1과 서빙용 v2, TMDb 정규화 스냅샷
- [추천 내부 8×128 그룹 서빙 산출물](./group-serving-artifacts.md): 고정 분류 geometry, 영화 assignment, 안전 번들·HDFS 공개와 Consumer 시작 조건
- [DISCOVERY 그룹 내부 대중성 산출물](./discovery-popularity-artifacts.md): TMDB 투표 수·KOBIS 관객 백분위 max 순위와 Consumer 적용 순서
- [GBT·FM 0~N 학습·평가 계약](./zero-n-learning-evaluation.md): episode, 사용자·시점 분할, 특징 lineage, 후보 정답과 지표 분모
