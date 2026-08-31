# Documentation

프로젝트의 설계 및 협업 문서를 관리합니다.

## 설계 문서

1. [요구사항 정의서](./requirements.md)
2. [API 명세](./api/README.md)
3. [논리 ERD](./erd.md)
4. [기술 결정 기록(ADR)](./adr/README.md)

요구사항 정의서는 기능·비기능 요구사항 목록과 항목별 인수 조건, 미결 사항의 기준 문서입니다.
API 명세는 공통 규약과 도메인별 REST API 계약(요청·응답·오류·처리 규칙·미정 사항)의 기준 문서입니다.
논리 ERD는 PostgreSQL 엔티티·관계와 물리 스키마 전에 결정할 사항을 정리한 문서입니다.
ADR은 아키텍처와 핵심 기술 선택을 결정 1건당 문서 1개로 기록합니다. 데이터 모델·API 규약·제품 정책 결정은 ERD·API 명세·요구사항 정의서에 남깁니다.

## 협업 문서

1. [Git 컨벤션](./git-convention.md)
2. [Jira-GitLab 개발 흐름](./development-workflow.md)
3. [AI 병렬 작업 및 충돌 방지 규칙](./ai-collaboration.md)
4. [Secret 및 환경변수 관리 규칙](./secret-environment-variables.md)
5. [Jira 업무 생성 자동화](./jira/README.md)

Git 컨벤션은 브랜치, 커밋, Merge Request 및 병합 규칙의 기준입니다.
개발 흐름 문서는 Jira 업무 항목을 시작해 GitLab에 병합하기까지의 실제 절차를 설명합니다.
AI 협업 문서는 AI를 활용한 병렬 작업, 로컬 작업 공간 분리 및 충돌 방지 기준을 설명합니다.
Secret 및 환경변수 관리 규칙은 로컬, CI/CD 및 운영 환경에서 설정과 인증정보를 안전하게
관리하는 기준을 설명합니다.
Jira 자동화 문서는 검토된 YAML 계획으로 Jira 업무를 안전하게 생성하는 방법을 설명합니다.
