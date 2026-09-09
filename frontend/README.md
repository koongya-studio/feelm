# Frontend

React + Vite 기반 반응형 웹 애플리케이션 소스와 관련 문서를 관리합니다.

## 디렉터리 구성

- `src/`: React 애플리케이션 소스
- `landing/`: 독립적으로 배포하는 Feelm 정적 HTML 랜딩 페이지와 이미지·JSON 자료
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

`frontend/`, `.gitlab-ci.yml` 또는 `.gitattributes` 변경이 `develop`에 Push되면 CI가
일회성 Node 컨테이너에서 의존성을 새로 설치하고 lint, 포맷 검사 및 Production Build를
실행합니다. 검증에 성공하면 같은 커밋의 프론트엔드 이미지를 빌드해
`$DOCKERHUB_NAMESPACE/feelm-frontend:$CI_COMMIT_SHA` 태그로 Docker Hub에 Push합니다.
실패 시 `frontend-test` 로그에서 최초 실패 명령을 확인하고, 이미지 빌드·인증·Push 실패는
`frontend-image` 로그와 보호·마스킹된 Docker Hub CI/CD Variable 상태를 확인합니다.

MR 코드를 실행하는 전용 Runner는 현재 백엔드 Job만 허용하므로 프론트엔드 검증은 보호된
`develop` Push에서만 자동 실행됩니다. 전용 Runner의 입력·명령 정책을 확장하기 전에는
비보호 MR을 운영 Runner에서 실행하지 않습니다.

`dist/`와 `node_modules/`는 저장소 루트 `.gitignore`에서 제외합니다.

## 컨테이너 이미지

프론트엔드 이미지는 Node 20.19 기반 빌드 단계에서 `npm ci`와 `npm run build`를
실행하고, 운영 단계에는 빌드 결과인 `dist/`와 Nginx만 포함합니다. 컨테이너는 80번
포트에서 React 앱을 제공하며, 클라이언트 라우트로 직접 접근해도 `index.html`로
연결됩니다.

```bash
docker build --pull -t feelm-frontend:local ./frontend
docker run --rm -p 8081:80 --name feelm-frontend feelm-frontend:local
```

다른 터미널에서 기본 화면과 헬스체크를 확인합니다.

```bash
curl --fail http://127.0.0.1:8081/
curl --fail http://127.0.0.1:8081/healthz
docker inspect --format '{{.State.Health.Status}}' feelm-frontend
```

종료할 때는 실행 중인 터미널에서 `Ctrl+C`를 누릅니다. 운영 이미지는 개발 의존성,
소스 코드, 로컬 `node_modules/`, `dist/`, `.env*`, 문서와 독립 배포 대상인
`landing/`을 포함하지 않습니다.

## 환경 변수

Vite는 `VITE_` 접두사가 붙은 변수만 클라이언트 코드에 노출합니다. 접두사가 없는 변수는
번들에 포함되지 않습니다.

현재 필요한 환경 변수는 없습니다. 목업 데이터로 동작하며 실제 API를 호출하지 않습니다.
변수를 추가할 때는 `frontend/.env.example`을 만들어 키 이름만 기록하고 실제 값은
커밋하지 않습니다. 자세한 기준은
[Secret 및 환경변수 관리 규칙](../docs/secret-environment-variables.md)을 따릅니다.

## 랜딩 메뉴 및 앱 연결

소개·기능 메뉴는 페이지 내 해당 섹션으로 이동합니다. 시작하기는 앱 주소가 비어 있으면
준비 안내를 표시하며, 문의도 준비 안내만 표시하고 개인정보를 수집하거나 전송하지 않습니다.

React 앱 배포 후 `landing/index.html`의 `meta[name="feelm-app-url"]` content를
배포된 같은 사이트 경로(예: `/app/`) 또는 HTTPS URL로 설정합니다. 현재 값은 빈 문자열입니다.
실제 React 배포와 Nginx 경로 연결은 별도 작업이며, 주소를 설정하기 전에 대상 페이지가
정상 동작하는지 확인해야 합니다. HTTPS 외 프로토콜 및 계정정보를 포함한 URL은 연결하지 않습니다.

내비게이션은 반투명 배경으로 상단에 고정되며, 모바일에서는 시작하기와 메뉴 버튼을 제공합니다.
메뉴 이동은 헤더 높이를 제외한 위치로 스크롤하고 Vibe 진행률과 관계없이 사용할 수 있습니다.
