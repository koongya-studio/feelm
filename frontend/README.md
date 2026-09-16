# Frontend

React + Vite 기반 반응형 웹 애플리케이션 소스와 관련 문서를 관리합니다.

## 수동 검증 기록

자동 테스트로 덮기 어려운 것은 실제로 돌려 본 절차와 결과를 `verification/`에 남깁니다.
토큰 수명이나 백엔드 장애처럼 조건을 만들어야 드러나는 것들입니다.

2026-09-11의 인증 세션 거동과 가입부터 로그인까지의 흐름은
[S15P21E106-376 검증 기록](verification/S15P21E106-376.md)을 참고합니다. 지금 실제 API를
쓰는 화면이 어디까지인지도 함께 적혀 있으며, **배포 환경 검증 완료를 뜻하지 않습니다.**

2026-09-16에 로컬 백엔드로 확인한 2.4 영화 상세는
[S15P21E106-475 검증 기록](verification/S15P21E106-475.md)에 있습니다. 줄거리·개봉일이
비어 있는 영화와 추천 이유 문구가 없는 응답을 화면이 어떻게 다루는지, 목업 화면에서
들어오면 왜 `404`가 되는지가 적혀 있습니다.

2026-09-16에 로컬 백엔드로 확인한 4.1~4.4 검색은
[S15P21E106-476 검증 기록](verification/S15P21E106-476.md)에 있습니다. 배열 필터가 같은
이름의 반복으로 실리는지, 20편을 넘겼을 때 커서가 이어지는지, 개봉연도가 없는 영화를
화면이 어떻게 그리는지가 적혀 있습니다.

2026-09-16에 로컬 백엔드로 확인한 OTT 클릭과 감상 확인은
[S15P21E106-477 검증 기록](verification/S15P21E106-477.md)에 있습니다. 재클릭이 상태를
어떻게 바꾸는지, 목업 평가와 실제 감상 기록이 어긋나는 자리가 어디인지가 적혀 있습니다.

2026-09-16에 계정 두 개로 확인한 파티 초대·수락·내보내기·나가기·종료는
[S15P21E106-393 검증 기록](verification/S15P21E106-393.md)에 있습니다. 두 사람이 있어야
성립하는 도메인이라 자동 테스트가 고정할 수 없는 자리와, API-057이 맛을 하나도 주지 않는
이유가 적혀 있습니다.

2026-09-15에 배포 환경에서 확인한 1.1 취향 초기 설정의 카드 노출은
[S15P21E106-449 검증 기록](verification/S15P21E106-449.md)에 있습니다. API-023이 `200`을
주면서 `items`가 빈 배열인 이유와, 화면이 그때 무엇을 세우는지가 적혀 있습니다.

백엔드가 단독으로 확인한 로그인 제한·메일 제한·차단은
[backend/verification/S15P21E106-375.md](../backend/verification/S15P21E106-375.md)에
있습니다.

## 디렉터리 구성

- `src/`: React 애플리케이션 소스
- `landing/`: 독립적으로 배포하는 Feelm 정적 HTML 랜딩 페이지. 데스크톱 웹 전용이며 서체 · 이미지 · 영상을
  `index.html` 한 파일에 담았습니다. 시작하기 버튼은 `/app/`으로 이동합니다
- `docs/`: 화면 목업 및 프론트엔드 설계 자료

React 프로젝트는 `frontend/`를 루트로 구성합니다. 랜딩 페이지는 `landing/index.html`에
두어 React 진입 파일과 분리하며, React 빌드와 lint · 포맷 검사 대상에서 제외합니다.

랜딩 페이지는 `landing/` 전체를 배포해야 하며 별도 빌드는 필요하지 않습니다.
Nginx·Compose 및 인증서 운영 절차는 [인프라 문서](../infra/README.md)를 참고합니다.

화면 설계 문서는 [`docs/screens/`](./docs/screens/README.md)에 있습니다.

## 실행 방법

Node 20.19 이상이 필요합니다. 패키지 매니저는 npm을 사용하며 `package-lock.json`을
저장소에 포함합니다.

```bash
cd frontend
npm install
```

| 명령 | 설명 |
| --- | --- |
| `npm run dev` | 개발 서버 실행 (http://localhost:5173) |
| `npm run build` | 타입 검사 후 Production Build. 결과는 `dist/` |
| `npm run preview` | Build 결과를 로컬에서 확인 |
| `npm run lint` | ESLint 검사 |
| `npm run lint:fix` | ESLint 자동 수정 |
| `npm run format` | Prettier 포맷 적용 |
| `npm run format:check` | Prettier 포맷 검사 |

커밋 전에 `npm run lint`와 `npm run format:check`를 통과하는지 확인합니다.

### 백엔드 연동

계정 화면(회원가입 · 로그인 · 내 프로필)과 파티 화면, 2.4 영화 상세, 4.x 검색, 감상
기록(2.4 OTT 클릭 · 2.4-A 감상 확인 · 7.7 안 남긴 영화)은 목업이 아니라 실제 백엔드를
호출합니다(업무 S15P21E106-364 · -393 · -475 · -476 · -477). 개발 서버가 `/api`를
백엔드로 넘기므로 프론트와 백엔드가 같은
출처가 되고, 브라우저가 교차 출처로 보지 않아 CORS 설정 없이 Refresh Token 쿠키까지
그대로 오갑니다. 배포에서 Nginx가 하는 일과 같은 구조입니다.

1. 백엔드를 `local` 프로파일로 띄웁니다(`backend/README.md`). 기본 주소는
   `http://localhost:8080`이며, 다른 주소면 `frontend/.env`에
   `VITE_API_PROXY_TARGET`을 적습니다.
2. `npm run dev`로 개발 서버를 띄우고 http://localhost:5173/app/login 으로 들어갑니다.

백엔드를 띄우지 않으면 계정 · 파티 · 검색 화면, 2.4 영화 상세, 감상 기록 화면과 홈의
프로필 영역이 오류 상태로 표시됩니다. 평가와 추천은 아직 백엔드가 없어 목업 그대로
동작하고, 파티 영역에서는 **파티 영화 추천(5.4 · 5.5)과 팝콘 섞기 수치(5.9)만** 목업으로
남습니다.

감상 기록은 실제지만 **평가는 목업이라 둘이 어긋납니다**(업무 -477). 2.5에서 평가해도
서버 기록은 `WATCHED_UNRATED`로 남아 7.7 `안 남긴 영화`에서 빠지지 않습니다. 평가
생성(API-036)이 연동되면 서버가 상태를 옮깁니다. 같은 이유로 목업 알림(8.1 · 8.2)이
가리키는 감상 기록 번호는 서버에 없어 2.4-A가 `감상 기록을 찾을 수 없어요`를 보여줍니다.

영화는 **상세(API-030)와 검색(API-028 · API-029)이** 실제 호출입니다. 검색 결과는 실제
영화이므로 4.2에서 고른 영화는 상세도 그대로 열립니다. 반면 목업으로 남은 목록
(2.2 오늘의 추천 · 7.5 추천 기록 · **4.1 급상승**)의 영화는 실제 DB에 없으므로 상세로
들어가면 `영화를 찾을 수 없어요`가 나옵니다. 주소로 직접 열 수도 있습니다
(`/app/movies/{movieId}`).

검색은 회원 전용이라 로그인해야 결과가 나오고, 로컬 DB에 영화가 없으면 결과도 비어
있습니다. 4.1 급상승만 목업입니다(API-064는 백엔드에 없습니다).

Access Token은 메모리에만 두고 새로고침하면 쿠키의 Refresh Token으로 세션을 되살립니다.
그래서 브라우저 저장소를 비워도 로그인 상태가 유지되지만, 쿠키를 지우면 로그아웃됩니다.

`frontend/`, `.gitlab-ci.yml` 또는 `.gitattributes` 변경이 `develop`에 Push되면 CI가
일회성 Node 컨테이너에서 의존성을 새로 설치하고 lint, 포맷 검사 및 Production Build를
실행합니다. 검증에 성공하면 같은 커밋의 프론트엔드 이미지를 빌드해
`$DOCKERHUB_NAMESPACE/feelm-frontend:$CI_COMMIT_SHA` 태그로 Docker Hub에 Push합니다.
실패 시 `frontend-test` 로그에서 최초 실패 명령을 확인하고, 이미지 빌드·인증·Push 실패는
`frontend-image` 로그와 보호·마스킹된 Docker Hub CI/CD Variable 상태를 확인합니다.
이미지 Push가 성공하면 `deploy-frontend`가 같은 SHA 이미지를 서비스 서버에 배포하고
`https://j15e106.p.ssafy.io/app/healthz`까지 확인합니다.

MR 코드를 실행하는 전용 Runner는 현재 백엔드 Job만 허용하므로 프론트엔드 검증은 보호된
`develop` Push에서만 자동 실행됩니다. 전용 Runner의 입력·명령 정책을 확장하기 전에는
비보호 MR을 운영 Runner에서 실행하지 않습니다.

`dist/`와 `node_modules/`는 저장소 루트 `.gitignore`에서 제외합니다.

## 컨테이너 이미지

프론트엔드 이미지는 Node 20.19 기반 빌드 단계에서 `npm ci`와 `npm run build`를
실행하고, 운영 단계에는 빌드 결과인 `dist/`와 Nginx만 포함합니다. 컨테이너는 80번
포트의 `/app/` 경로에서 React 앱을 제공하며, 클라이언트 라우트로 직접 접근해도
`/app/index.html`로 연결됩니다. 정적 자원과 Browser Router도 `/app/`을 기준으로
생성되므로 운영 Nginx는 접두사를 제거하지 않고 프론트엔드 컨테이너에 전달합니다.

```bash
docker build --pull -t feelm-frontend:local ./frontend
docker run --rm -p 8081:80 --name feelm-frontend feelm-frontend:local
```

다른 터미널에서 기본 화면과 헬스체크를 확인합니다.

```bash
curl --fail --location http://127.0.0.1:8081/
curl --fail http://127.0.0.1:8081/app/
curl --fail http://127.0.0.1:8081/app/movies/1
curl --fail http://127.0.0.1:8081/healthz
curl --fail http://127.0.0.1:8081/app/healthz
docker inspect --format '{{.State.Health.Status}}' feelm-frontend
```

종료할 때는 실행 중인 터미널에서 `Ctrl+C`를 누릅니다. 운영 이미지는 개발 의존성,
소스 코드, 로컬 `node_modules/`, `dist/`, `.env*`, 문서와 독립 배포 대상인
`landing/`을 포함하지 않습니다.

## 환경 변수

Vite는 `VITE_` 접두사가 붙은 변수만 클라이언트 코드에 노출합니다. 접두사가 없는 변수는
번들에 포함되지 않습니다.

| 변수 | 쓰이는 곳 | 기본값 |
| --- | --- | --- |
| `VITE_API_PROXY_TARGET` | 개발 서버가 `/api`를 넘길 백엔드 주소 (`vite.config.ts`) | `http://localhost:8080` |

`VITE_API_PROXY_TARGET`은 개발 서버 설정에서만 읽으므로 클라이언트 번들에 들어가지
않습니다. 값을 바꾸려면 `frontend/.env`에 적습니다. 키 이름은
`frontend/.env.example`에 기록하고 실제 값은 커밋하지 않습니다. 자세한 기준은
[Secret 및 환경변수 관리 규칙](../docs/secret-environment-variables.md)을 따릅니다.

## 랜딩 메뉴 및 앱 연결

소개·기능 메뉴는 페이지 내 해당 섹션으로 이동합니다. 시작하기는 같은 사이트의 `/app/`으로
이동하며, 문의는 준비 안내만 표시하고 개인정보를 수집하거나 전송하지 않습니다.
React 앱은 현재 실제 API와 인증을 사용하지 않는 프리뷰이므로 화면에 `PREVIEW · 목업 데이터`
표시를 고정해서 운영 기능으로 오인하지 않게 합니다.

내비게이션은 반투명 배경으로 상단에 고정되며, 모바일에서는 시작하기와 메뉴 버튼을 제공합니다.
메뉴 이동은 헤더 높이를 제외한 위치로 스크롤하고 Vibe 진행률과 관계없이 사용할 수 있습니다.
