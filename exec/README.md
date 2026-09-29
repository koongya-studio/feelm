# exec — 포팅 매뉴얼

SSAFY 포팅 매뉴얼 제출물이다. 소스코드는 저장소의 다른 폴더에 있으며 여기에는 다시 올리지 않는다.

| 번호 | 문서 | 내용 |
| --- | --- | --- |
| 1 | [빌드 및 배포](./1-build-and-deploy.md) | 사용 JVM·웹서버·WAS·DB·IDE 버전, 빌드 명령, 빌드·실행 환경 변수, 배포 절차와 특이사항, DB 접속 정보 등 계정·프로퍼티 정의 파일 목록 |
| 2 | [외부 서비스](./2-external-services.md) | 소셜 로그인, 메일, LLM, TMDb·MovieLens, Hugging Face, Docker Hub, Let's Encrypt 등 가입·설정 정보 |
| 3 | [DB 덤프](./3-db-dump.md) | 배포 DB 최신 덤프(`db/`), 복원 방법, 포함 범위와 비식별화 내용 |
| 4 | [시연 시나리오](./4-demo-scenario.md) | 시연 순서에 따른 화면별 조작 위치와 설명 |

- 서비스 주소: <https://j15e106.p.ssafy.io/app/>
- 기준 커밋: `develop` `3b820c26` (2026-09-28)
- 관련 업무: S15P21E106-980
