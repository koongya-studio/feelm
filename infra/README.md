# Infrastructure

E106 프로젝트의 서비스 및 분산 처리 인프라 설정을 관리합니다.

## Distributed Runtime Baseline

분산 처리 환경은 아래 버전과 실행 방식을 공통 기준으로 사용합니다. 구성요소의 버전을
변경할 때는 호환성을 검증하고 이 표를 먼저 갱신합니다.

| 구성요소 | 확정 버전·방식 |
| --- | --- |
| Python | 3.12.x |
| Spark | 4.1.3 |
| PySpark | 4.1.3 |
| Spark JVM | Eclipse Temurin 21 |
| Scala | 2.13 (Spark Runtime) |
| Hadoop / HDFS | 3.4.3 |
| Kafka | 4.3.1 |
| Kafka Mode | KRaft |
| Python Dependencies | uv |
| Local Development | WSL2 Ubuntu 24.04 |
| Runtime | Docker / Docker Compose |

포트, 네트워크, 영속 볼륨과 서비스별 실행 설정은 이 기준을 바탕으로 HDFS, Spark,
Kafka 구성 업무에서 구체화합니다.

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
