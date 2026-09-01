# 백엔드 개발 컨벤션

백엔드(Spring Boot, Java) 코드의 스타일과 품질 기준을 정의한다.
자동화 가능한 규칙은 문서가 아니라 도구로 강제하고, 이 문서에는 채택 근거와
도구가 잡지 못하는 규칙만 남긴다.

## 1. 코드 스타일

- 기준: [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)의
  **AOSP 변형**을 따른다.
  - 들여쓰기 4칸(공백), 탭 미사용
  - 한 줄 최대 100자
  - 나머지 규칙(중괄호 위치, import 순서, 공백 등)은 Google Java Style과 동일
- 스타일은 리뷰에서 사람이 지적하지 않는다. 아래 Spotless가 전부 자동으로 강제한다.

## 2. Spotless 자동 포맷팅

`backend/build.gradle`에 [Spotless](https://github.com/diffplug/spotless) +
google-java-format(AOSP 모드)이 설정되어 있다.

| 명령 | 용도 |
| --- | --- |
| `./gradlew spotlessApply` | 전체 Java 소스를 규칙에 맞게 자동 수정 |
| `./gradlew spotlessCheck` | 위반 여부만 검사(수정 없음). CI 검증용 |

- 커밋 전에 `spotlessApply`를 실행한다. IDE 포맷터 설정은 자유이며 임포트가 필요 없다.
  최종 기준은 항상 Spotless다.
- `spotlessCheck`는 `check` 태스크에 포함되므로 `./gradlew build` 시 위반이 있으면
  빌드가 실패한다.
- 포맷터 버전은 `build.gradle`의 Spotless 플러그인 버전으로 고정된다. 플러그인 버전
  변경은 공통 빌드 파일 변경이므로 사전 공유 후 별도 작업으로 처리한다.

## 3. 도구가 잡지 못하는 규칙

포맷터가 강제하지 못하는 규칙은 팀 합의 후 이 문서에 추가한다. 현재 미정 항목:

- 패키지 구조(레이어드 vs 도메인 기준)
- Entity/DTO 분리 원칙과 DTO 네이밍
- 예외 처리와 공통 에러 응답 규약
- Lombok 허용 범위
- 테스트 작성 기준과 TDD 절차(에이전트 지시 문서 위치 포함)

미정 항목은 확정 전까지 임의로 결정하지 않으며, 관련 구현이 먼저 필요한 경우
Jira 업무에서 범위를 합의한다.
