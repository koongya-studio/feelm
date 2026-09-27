# Frontend

React + Vite 기반 반응형 웹 애플리케이션 소스와 관련 문서를 관리합니다.

## 시연 전 사용자 의견

개인 추천과 파티 추천 결과에 카드가 실제로 있을 때 목록 끝에서 추천 의견을 묻습니다.
파티 의견은 결과 묶음과 참여자별로 따로 기록합니다. 글 입력을 접어 두면
선택지 하나를 누르는 즉시 제출됩니다. 펼치면 평가를 고르고 글을 함께 보내거나 글만 보낼 수
있습니다. 같은 추천 세트에서는 브라우저에 제출 상태를 기억해 다시
묻지 않습니다. 서비스 전체 의견은 추천 의견과 분리해, 이용 기록이 생긴 뒤 MY 하단에서
한 번만 가볍게 안내합니다. 안내를 닫거나 누르면 다시 띄우지 않으며, 설정의 `서비스 의견
보내기`에서는 언제든 자유롭게 남길 수 있습니다.
추천 의견에는 추천 화면 이름을 보여주고, 일반 의견에는 서비스 전반을 묻습니다. 두 경우 모두
출발 경로는 서버에 함께 보냅니다.
저장·팀 채널 알림 계약은
[`docs/api/feedback.md`](../docs/api/feedback.md)를 따릅니다.

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

2026-09-17에 대표 맛 배정 이후 같은 화면을 다시 확인한 기록은
[S15P21E106-467 검증 기록](verification/S15P21E106-467.md)에 있습니다. 배포 DB에 대표 맛이
아직 없어 재확인이 끝나지 않은 이유와, 실제 포스터가 붙은 카드 10장의 배치·제출 이동을
배포본에서 어떻게 확인했는지가 적혀 있습니다.

2026-09-18에 평가 결과 화면의 프레임·팝콘 수치를 확인한 기록은
[S15P21E106-599 검증 기록](verification/S15P21E106-599.md)에 있습니다. 평가 한 건이 팝콘
몇 알에 대응하는지, 2026-09-17 여정 QA가 본 `+2알`이 왜 화면 결함이 아니었는지, 그런데도
`저장 전 값 + 1`이라는 전제가 어떤 경로로 깨지는지가 적혀 있습니다.

2026-09-21에 데스크톱 셸을 붙이고 폭 네 단계에서 확인한 기록은
[S15P21E106-782 검증 기록](verification/S15P21E106-782.md)에 있습니다. 412·768·1280·1440px
에서 가로 넘침과 콘텐츠 겹침이 없는지, 1024px 미만이 기존 화면 그대로인지, 좌측 내비가
생기면서 본문까지 `Tab`이 열한 번이 된 것을 어떻게 다뤘는지가 적혀 있습니다. 확인하면서
드러난 상단 바 검색·빈 보조 패널·검색어 잔류 셋의 원인과 고친 방법도 함께 있습니다.

백엔드가 단독으로 확인한 로그인 제한·메일 제한·차단은
[backend/verification/S15P21E106-375.md](../backend/verification/S15P21E106-375.md)에
있습니다.

## 리포트 이미지의 TMDB 사진 캡처

리포트 저장·공유는 화면 사진을 데이터 URL로 읽어 PNG에 포함합니다. TMDB 사진은
`<img>`에서 보여도 직접 `fetch`하면 CORS에 막힐 수 있으므로 캡처할 때만
`/tmdb-images/{size}/{file}` 같은 출처 경로로 읽습니다(S15P21E106-935).
Vite 개발 서버와 서비스 Nginx가 이 경로를 HTTPS `image.tmdb.org/t/p/`로 전달합니다.
기존 화면 이미지 URL과 API-061 계약은 유지합니다.

**배포는 Nginx 프록시 적용 → 프론트 이미지 배포 순서입니다.** 프론트만 배포하면
프록시가 없어 저장·공유가 실패합니다. `npm run preview`는 이 개발 프록시를 제공하지
않으므로 프록시 뒤에서 검증하거나 `npm run dev`를 사용합니다.
서버 적용 및 모바일 확인은 [검증 기록](verification/S15P21E106-935.md)을 참고합니다.

## 디렉터리 구성

- `src/`: React 애플리케이션 소스
- `landing/`: 독립적으로 배포하는 Feelm 정적 HTML 랜딩 페이지. 데스크톱 웹 전용이며 이미지를
  `index.html` 한 파일에 담았고 서체만 Google Fonts에서 불러옵니다. 상단의 `시작하기`는 0.2 회원가입
  (`/app/signup`), 자물쇠(`로그인`)는 0.6 로그인(`/app/login`)으로 이동합니다(업무 -704)
- `docs/`: 화면 목업 및 프론트엔드 설계 자료
- `scripts/`: 빌드 도구 스크립트. 지금은 API 타입 생성기 하나입니다(업무 -777)

React 프로젝트는 `frontend/`를 루트로 구성합니다. 랜딩 페이지는 `landing/index.html`에
두어 React 진입 파일과 분리하며, React 빌드와 lint · 포맷 검사 대상에서 제외합니다.

랜딩 페이지는 `landing/` 전체를 배포해야 하며 별도 빌드는 필요하지 않습니다.
Nginx·Compose 및 인증서 운영 절차는 [인프라 문서](../infra/README.md)를 참고합니다.

화면 설계 문서는 [`docs/screens/`](./docs/screens/README.md)에 있습니다.

`src/api` 함수별로 실제 API를 부르는지, 목업이면 왜 남았고 무엇을 기다리는지는
[`docs/screens/api-status.md`](./docs/screens/api-status.md)에 있습니다(업무 S15P21E106-395).

## 실행 방법

Node 22.22.0 이상이 필요합니다. 패키지 매니저는 npm을 사용하며 `package-lock.json`을
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
| `npm run api:generate` | 백엔드 OpenAPI 스냅샷에서 API 타입을 다시 만듭니다 |
| `npm run api:check` | 생성 타입이 스냅샷과 어긋나면 실패합니다 |

커밋 전에 `npm run lint`와 `npm run format:check`를 통과하는지 확인합니다.
MR 생성 및 수정 커밋 Push 전 필수 포맷 검사 절차는 [`AGENTS.md`](AGENTS.md)를 따릅니다.

### API 타입 생성 (업무 S15P21E106-777)

`src/generated/openapi.json`은 백엔드 springdoc 문서(`/v3/api-docs`)의 스냅샷이고,
`src/generated/api-schema.d.ts`는 거기서 만든 타입입니다. **둘 다 손으로 고치지 않습니다.**

- 백엔드 계약이 바뀌면 스냅샷을 갱신합니다 —
  `cd backend && FEELM_OPENAPI_SNAPSHOT_WRITE=1 ./gradlew test --tests '*OpenApiSnapshotTest'`
- 스냅샷이 바뀌면 `npm run api:generate`로 타입을 다시 만듭니다
- 잊으면 `npm run build`가 멈춥니다(`vite.config.ts`의 `feelm-api-schema-drift`)

손으로 쓴 `src/types/*`가 생성 스키마와 어긋나는지는 `src/types/schema-conformance.ts`가
`tsc -b`에서 봅니다. 구조와 남은 제약은
[`docs/screens/frontend-structure.md`](./docs/screens/frontend-structure.md) 8.4에 있습니다.

### 백엔드 연동

계정 화면(회원가입 · 로그인 · 내 프로필)과 파티 화면, 2.2 오늘의 추천, 2.4 영화 상세,
4.x 검색, 감상 기록(2.4 OTT 클릭 · 2.4-A 감상 확인 · MY `안 남긴 영화` 탭), 2.5 평가
저장, MY `추천 기록` 탭, 7.8-A-1 회원 탈퇴, 취향 리포트(3.3 발급 · 3.4 · 7.3 · 7.4)는
목업이 아니라 실제 백엔드를 호출합니다
(업무 S15P21E106-364 · -393 · -475 · -476 · -477 · -478 · -480 · -491 · -558 · -610 · -613).
개발 서버가 `/api`를 백엔드로 넘기므로 프론트와 백엔드가 같은
출처가 되고, 브라우저가 교차 출처로 보지 않아 CORS 설정 없이 Refresh Token 쿠키까지
그대로 오갑니다. 배포에서 Nginx가 하는 일과 같은 구조입니다.

1. 백엔드를 `local` 프로파일로 띄웁니다(`backend/README.md`). 기본 주소는
   `http://localhost:8080`이며, 다른 주소면 `frontend/.env`에
   `VITE_API_PROXY_TARGET`을 적습니다.
2. `npm run dev`로 개발 서버를 띄우고 http://localhost:5173/app/login 으로 들어갑니다.

백엔드를 띄우지 않으면 계정 · 파티 · 검색 화면, 2.2 오늘의 추천, 2.4 영화 상세, 2.5
평가, MY 기록 탭과 연동 영역이 오류 상태로 표시됩니다. 파티 영역에서는 **파티 영화 추천
(5.4 · 5.5)과 팝콘 섞기 수치(5.9)만** 목업으로 남습니다.

**2.5 평가 저장(API-036)은 실제 호출입니다**(업무 -491). 서버가 평가 · 프레임 · 감상 기록
`RATED` 전이를 한 트랜잭션으로 처리하므로 평가한 영화는 7.7 `안 남긴 영화`에서 빠지고,
2.6 · 2.7의 프레임 · 팝콘 수치는 API-014 · API-041의 실제 값에서 시작합니다. 반면 **평가
수정(API-037)과 내 평가 목록(API-039)은 백엔드에 아직 없어 목업**입니다(업무 -213 · -214).
그래서 7.6 `평가한 영화`는 기준 목업 세 편만 보여주고 방금 저장한 평가가 나타나지 않으며,
2.5에서 기존 평가를 고쳐도 서버에는 반영되지 않습니다. **알림 목록(8.1)은 API-070~074의
서버 알림 정본을 사용**합니다. 감상 확인·파티 초대·파티 초대 수락을 표시하고, 미확인 개수,
개별·전체 읽음과 삭제 상태가 새로고침·재로그인 뒤에도 유지됩니다. 화면은 사용자에게
`삭제`로 표시하지만 API-074는 원본 사건과 알림 행을 지우지 않는 숨김 처리입니다. 알림은
연결된 감상 기록, 파티 초대 또는 파티 상세로 이동합니다. 브라우저 푸시 구독·수신
(API-075~077)은 별도 연동 범위입니다.

**취향 리포트는 목록 · 상세 · 첫 발급이 실제 호출입니다**(업무 -558, 백엔드 -556). 3.1 필름의
`새 리포트 받기`는 API-059 `current`가 `READY`인데 발급 기록이 없을 때만 나오고, 3.3에서
`리포트 보기`를 누르면 API-080으로 첫 발급을 기록한 뒤 3.4로 넘어갑니다. 기록에 실패하면
넘어가지 않고 그 자리에서 다시 시도합니다. 인화 중인 반기는 목록이 아니라 `current`로 오므로
7.3의 `인화 중` 칸은 그 값으로 그립니다. 상세 화면의 `저장`은 화면에 보이는 공용
`ReportFourCut` DOM을 그대로 1080×3268 PNG로 캡처해 API-061에 보내고, Backend는 이를 비공개
저장한 뒤 10분 서명 URL을 줍니다(업무 -557). 따라서 화면과 저장 이미지의 CSS·서체·로고·사진·
캐릭터 에셋은 하나의 구현을 공유합니다. 서버는 최초 PNG 한 장을 유지합니다.
갤럭시·웹은 기존 캡처 처리와 API-061 서명 URL의 PNG 다운로드를 사용합니다.
iPhone·iPad만 API-061 성공 후 **이번에 캡처한 앞면 한 장**을 내려받아 서버의 기존
불완전한 PNG 재사용을 피합니다. 반복 저장이 서버 파일 수를 늘리지 않습니다(S15P21E106-963).
아이폰 앞면 캡처는 사진을 Canvas에 직접 그리고, 기존 DOM에서 얻은 배경·글자·로고를
합성합니다. Safari가 SVG 안의 사진을 조용히 생략하는 경로를 피하며 기존 크롭·누끼·
흑백 효과와 글자 순서를 유지합니다.
iOS 앞면 저장용 폰트는 필요한 네 가지 파일만 포함하며 한글은 전체 가변 폰트를
사용합니다. 갤럭시·웹의 폰트 수집과 사진 처리에는 이 보정을 적용하지 않습니다.
검증 결과는 [S15P21E106-963](verification/S15P21E106-963.md)에 기록합니다.
상세 화면의 `저장`과 `공유하기`는 각각 한 번 눌러 실행합니다(업무 -861). 첫 미발급 리포트가
준비된 상태로 MY 목록을 열면 빈 목록 대신 첫 인화 화면으로
가는 `확인하러 가기`를 표시합니다(S15P21E106-848).

**7.8-A-1 회원 탈퇴(API-013)는 본인 확인을 한 번 더 거칩니다.** 이메일 계정은 현재
비밀번호를 적습니다(업무 -480, CR-24). 소셜로만 가입해 비밀번호가 없는 계정은 연결된
제공자(구글 · 카카오)로 다시 로그인해 확인합니다(업무 -435 · -754 · -880). 확인 창에서
`탈퇴`를 누르면 제공자로 나갔다가 `/oauth/callback`으로 돌아오고, 콜백이 인가 코드를
7.8-A-1에 넘겨 그 화면이 탈퇴를 한 번 부릅니다. 인가 코드는 1회용이라 이 요청은 401에도
재발급 뒤 다시 보내지 않습니다. 배포에서 동작하려면 소셜 client-id가 빌드에 들어가야 하고
(업무 -531 · -532), 없으면 화면이 확인을 쓸 수 없다고 알리고 `탈퇴하기`를 열지 않습니다.
탈퇴는 되돌릴 수 없으니 로컬에서 시험할 때는 버릴 계정을 씁니다.

**취향 분석(API-040) 클라이언트는 구현돼 있지만 현재 호출하는 화면은 없습니다**(업무
-479 · -461). 계약은 평가 트랜잭션이 취향 분석 캐시(`taste_analyses`)를 갱신한다고 정하지만
백엔드에는 아직 조회만 있고 캐시를 채우는 로직이 없습니다. API 단위 테스트는 캐시가 없을
때의 `insufficientData: true`와 빈 `keywords` 응답을 고정합니다. 3.1 `요즘 취향 흐름`은
대응 계약이 없어(CR-16) 목업으로 남습니다.

**2.2 오늘의 추천(API-045)과 MY `추천 기록` 탭(API-048)은 실제 호출입니다**(업무 -478 ·
-610 · -613). 추천 조회는 Redis의 최신 후보 generation을 확인해 PostgreSQL 추천 세트를
생성·재사용하며, 후보가 없거나 읽지 못하면 `503 RECOMMENDATION_UNAVAILABLE`을 반환합니다.
Kafka 갱신 알림 연동(-585)이 배포되기 전까지는 API-045 요청마다 최신 generation을 동기
확인합니다.

영화는 **상세(API-030)와 검색(API-028 · API-029)이** 실제 호출입니다. 검색 결과와 2.2
오늘의 추천 결과는 실제 영화이므로 고른 영화의 상세도 그대로 열립니다. 주소로 직접 열
수도 있습니다(`/app/movies/{movieId}`).

검색은 회원 전용이라 로그인해야 결과가 나오고, 로컬 DB에 영화가 없으면 결과도 비어
있습니다. 공백을 뺀 한 글자부터 타이핑하는 대로 검색하고, 결과는 포스터 3열 격자입니다
(업무 S15P21E106-612). 4.1 급상승(API-064)은 백엔드에 없어 화면에서 뺐습니다.

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

프론트엔드 이미지는 Node 22.22.0 기반 빌드 단계에서 `npm ci`와 `npm run build`를
실행하고, 운영 단계에는 빌드 결과인 `dist/`와 Nginx만 포함합니다. 컨테이너는 80번
포트의 `/app/` 경로에서 React 앱을 제공하며, 클라이언트 라우트로 직접 접근해도
`/app/index.html`로 연결됩니다. 정적 자원과 Browser Router도 `/app/`을 기준으로
생성되므로 운영 Nginx는 접두사를 제거하지 않고 프론트엔드 컨테이너에 전달합니다.

```bash
docker build --pull \
  --build-arg VITE_GOOGLE_CLIENT_ID="${VITE_GOOGLE_CLIENT_ID:-}" \
  --build-arg VITE_KAKAO_CLIENT_ID="${VITE_KAKAO_CLIENT_ID:-}" \
  --build-arg VITE_NAVER_CLIENT_ID="${VITE_NAVER_CLIENT_ID:-}" \
  --build-arg VITE_OAUTH_REDIRECT_URI="${VITE_OAUTH_REDIRECT_URI:-}" \
  -t feelm-frontend:local ./frontend
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

## PWA 설치와 서비스 워커

Production Build는 `/app/manifest.webmanifest`와 `/app/service-worker.js`를 함께 제공합니다.
지원 브라우저가 설치 이벤트를 주면 설정의 `Feelm 앱 설치`에서 설치를 시작합니다. iOS
Safari는 같은 이벤트가 없으므로 공유 메뉴의 `홈 화면에 추가` 순서를 안내합니다. 이미
standalone 모드로 실행 중이면 설치 항목을 표시하지 않습니다.

서비스 워커 등록 실패는 React 앱 진입을 막지 않습니다. 현재 서비스 워커는 `fetch`를
가로채거나 Cache Storage를 만들지 않습니다. 로그인 세션, `/api` 응답과 사용자별 취향
데이터는 항상 네트워크와 서버를 정본으로 사용합니다. Nginx는 서비스 워커 본문을
`no-cache`로 제공해 새 배포의 갱신 검사를 막지 않습니다.

브라우저 Web Push 업무 `S15P21E106-583`은 `public/service-worker.js`에 `push`와
`notificationclick` 이벤트를 추가합니다. 서비스 워커 등록과 설치 상태 관리는 이 업무의
`src/pwa/`를 재사용하고, 별도 서비스 워커 파일이나 중복 등록을 만들지 않습니다. Web Push를
추가할 때도 API 및 개인 데이터 비캐시 원칙은 유지합니다.

종료할 때는 실행 중인 터미널에서 `Ctrl+C`를 누릅니다. 운영 이미지는 개발 의존성,
소스 코드, 로컬 `node_modules/`, `dist/`, `.env*`, 문서와 독립 배포 대상인
`landing/`을 포함하지 않습니다.

## 환경 변수

Vite는 `VITE_` 접두사가 붙은 변수만 클라이언트 코드에 노출합니다. 접두사가 없는 변수는
번들에 포함되지 않습니다.

| 변수 | 쓰이는 곳 | 기본값 |
| --- | --- | --- |
| `VITE_API_PROXY_TARGET` | 개발 서버가 `/api`를 넘길 백엔드 주소 (`vite.config.ts`) | `http://localhost:8080` |
| `VITE_GOOGLE_CLIENT_ID` · `VITE_KAKAO_CLIENT_ID` · `VITE_NAVER_CLIENT_ID` | 소셜 로그인 client-id (`src/utils/oauth.ts`) | 없음 |
| `VITE_OAUTH_REDIRECT_URI` | 소셜 로그인 콜백 주소 | `{origin}/app/oauth/callback` |

소셜 로그인 client-id는 번들에 들어갑니다. OAuth 공개 클라이언트의 client-id는 공개해도
되는 값이지만 client-secret은 백엔드만 가지며 이곳에 두지 않습니다. 값이 없는 제공자는
버튼이 잠깁니다. 콜백 주소는 각 사 개발자 콘솔과 백엔드 허용 목록
(`OAUTH_ALLOWED_REDIRECT_URIS`)에 등록한 값과 **정확히 같아야** 합니다.

네이버 인증의 새 사용자 진입점은 업무 `S15P21E106-880`에서 제거했습니다. 기존 계정과
공통 API 계약을 위해 `NAVER` 타입·콜백 처리와 환경 변수는 유지하지만, 로그인·가입·계정
연결·탈퇴 재인증 화면에서는 네이버 인증을 시작하지 않습니다.

운영 이미지는 GitLab CI/CD Variable의 공개 client-id를 `frontend-image` Job에서 Docker
build argument로 전달해 빌드합니다. 이때 읽는 변수는 백엔드와 같은 `GOOGLE_CLIENT_ID`,
`KAKAO_CLIENT_ID`, `NAVER_CLIENT_ID`입니다. 제공자는 동의 화면의 `client_id`와 백엔드가
인가 코드를 교환할 때 보내는 `client_id`가 다르면 거절하므로 두 값은 반드시 같아야 하고,
변수를 따로 두면 한쪽만 갱신됐을 때 조용히 어긋납니다. 그래서 프론트 전용 client-id 변수는
만들지 않습니다. `VITE_OAUTH_REDIRECT_URI`만 프론트 전용이며 기본값
(`{origin}/app/oauth/callback`)과 다른 콜백을 등록한 환경에서만 설정합니다. 미설정 값은
빈 문자열로 전달되므로 이미지 빌드는 계속되고 해당 제공자 버튼만 잠깁니다.
`GOOGLE_CLIENT_SECRET`, `KAKAO_CLIENT_SECRET`, `NAVER_CLIENT_SECRET`은 프론트 변수,
build argument 또는 이미지에 절대 전달하지 않습니다. `frontend-image`는 `service` 환경을
준비하는 Job으로 선언되어 이 범위의 값을 읽되 실제 배포 상태는 뒤의 `deploy-frontend` Job만
기록합니다.

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
