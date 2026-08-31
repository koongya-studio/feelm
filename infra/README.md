# Infrastructure

E106 프로젝트의 서비스 및 분산 처리 인프라 설정을 관리합니다.

## Server Roles

### J15E106 - Service Node

- Nginx
- Frontend
- Spring Boot
- PostgreSQL
- Redis
- Spark Worker
- HDFS DataNode

### J15E106A - Distributed Node

- Spark Master
- Spark Worker
- Kafka
- Python Fold-in Consumer
- HDFS NameNode
- HDFS DataNode

## Recommendation Processing

추천 모델은 별도의 FastAPI Serving 서버를 두지 않고 Batch 및 Consumer 기반으로 처리합니다.

- Spark Batch
  - ALS 학습
  - 콘텐츠 기반 후보 계산
  - Hybrid 추천 후보 생성
  - 사용자별 추천 후보 Top-K 사전 계산

- Python Fold-in Consumer
  - Kafka의 명시적 평가 이벤트 소비
  - 사용자 ALS Vector 갱신
  - 해당 사용자의 추천 후보 Top-K 재계산

- Spark Streaming
  - 조회, 클릭 등 실시간 행동 이벤트 처리
  - 실시간 취향 Feature 계산

- Redis
  - 추천 후보
  - 사용자 Vector
  - 실시간 Feature 저장

- Spring Boot
  - Redis의 추천 후보 조회
  - 실시간 Feature 기반 Re-Ranking
  - 최종 추천 API 제공

## Compose

- `compose/service.yml`
  - J15E106 서비스 구성

- `compose/distributed.yml`
  - J15E106A 분산 처리 구성

## Deployment

인프라 설정 파일은 GitLab에서 관리하며, 서버에서 직접 수정하지 않는 것을 원칙으로 합니다.