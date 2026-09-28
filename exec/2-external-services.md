# 2. 외부 서비스 정보

feelm이 사용하는 외부 서비스의 용도, 가입·설정 방법, 설정 값이 들어가는 변수를 정리한다.
실제 키와 비밀번호는 저장소에 없고 GitLab CI/CD Variables(`service` 환경 범위)에 있다.
변수 전체 목록은 [1장 1.4](./1-build-and-deploy.md#14-빌드실행-환경-변수)를 따른다.

## 2.1 요약

| 서비스 | 용도 | 필수 여부 | 설정 변수 |
| --- | --- | --- | --- |
| Google OAuth 2.0 | 소셜 로그인·가입·연결 | 선택(이메일 가입으로 대체 가능) | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` |
| Kakao Login | 소셜 로그인·가입·연결 | 선택 | `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` |
| Naver Login | API 계약만 유지, 화면 진입점 없음 | 선택 | `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` |
| Brevo SMTP Relay | 가입 인증번호·비밀번호 재설정 메일 | 필수(배포) | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` |
| SSAFY GMS (Gemini) | 추천 이유(XAI) 문장 생성 | 선택(없으면 고정 문구) | `GBT_XAI_ENABLED`, `GMS_KEY`, `GEMINI_REASON_MODEL` |
| TMDb API | 영화 메타데이터·포스터·OTT 제공처 수집 | 데이터 적재 시 필수 | `TMDB_TOKEN`(적재 스크립트 셸 변수) |
| TMDb 이미지 CDN | 포스터·로고 이미지 | 필수 | 없음(Nginx `/tmdb-images/` 프록시) |
| MovieLens 32M | 추천 모델 학습 평점 데이터 | 학습 시 필수 | 없음(수동 다운로드) |
| Hugging Face | E5 임베딩 모델 다운로드 | 임베딩 생성 시 필수 | 없음(익명 다운로드) |
| Docker Hub | CI 이미지 저장소 | 필수(CI) | `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `DOCKERHUB_NAMESPACE` |
| Let's Encrypt | HTTPS 인증서 | 필수(배포) | 없음(Certbot) |
| Web Push(VAPID) | 브라우저 푸시 알림 | 선택(배포 `disabled`) | `WEB_PUSH_MODE`, `WEB_PUSH_VAPID_*` |
| Mattermost | 사용자 의견·배포·모니터링 알림 | 선택 | `MATTERMOST_*` |
| GitLab / Jira | CI/CD, 업무 관리 자동화 | 개발 협업용 | `CI_VARIABLE_AUDIT_TOKEN`, `JIRA_*` |

## 2.2 소셜 로그인 (Google, Kakao, Naver)

- **흐름**: 프론트엔드가 제공자 동의 화면으로 보내고, 콜백 `/app/oauth/callback`에서 받은 인가 코드를
  백엔드(API-007~009)에 넘긴다. 백엔드가 토큰 교환과 사용자 정보 조회를 한다. 제공자는 `state`로 기억하므로
  콜백 주소는 제공자마다 따로 두지 않는다.
- **콜백 주소**

  | 환경 | 주소 |
  | --- | --- |
  | 배포 | `https://j15e106.p.ssafy.io/app/oauth/callback` |
  | 로컬 | `http://localhost:5173/app/oauth/callback` |

  제공자 콘솔, 백엔드 `OAUTH_ALLOWED_REDIRECT_URIS`, 프론트엔드가 만드는 주소(`VITE_OAUTH_REDIRECT_URI`, 비우면
  `{origin}/app/oauth/callback`)가 글자까지 같아야 한다.
- **Google**: [Google Cloud Console](https://console.cloud.google.com/) → API 및 서비스 → OAuth 동의 화면 구성 →
  사용자 인증 정보 → OAuth 클라이언트 ID(웹 애플리케이션) 생성 → 승인된 리디렉션 URI에 콜백 등록.
  범위는 `openid email profile`이다.
- **Kakao**: [Kakao Developers](https://developers.kakao.com/) → 애플리케이션 추가 → 카카오 로그인 활성화 →
  Redirect URI 등록 → 동의항목 설정 → 보안에서 Client Secret 발급. 필요한 정보는 scope가 아니라 콘솔 동의항목으로 정한다.
  REST API 키가 `KAKAO_CLIENT_ID`다.
- **Naver**: [NAVER Developers](https://developers.naver.com/) → 애플리케이션 등록(네이버 로그인) → Callback URL 등록.
  현재 화면은 Google·Kakao만 노출한다(S15P21E106-880).
- **값 배치**: client-id는 백엔드 변수와 프론트엔드 빌드 인자(`VITE_*_CLIENT_ID`)에 같은 값을 넣는다. CI는
  `GOOGLE_CLIENT_ID` 등 같은 변수를 프론트엔드 빌드 인자로 넘긴다. Client Secret은 백엔드에만 둔다.
- **코드**: `frontend/src/utils/oauth.ts`, `backend/src/main/java/com/feelm/backend/infrastructure/oauth/`

## 2.3 메일 발송 (Brevo SMTP Relay)

- **용도**: 이메일 가입 인증번호, 비밀번호 재설정 링크(`${FRONTEND_BASE_URL}/app/account/reset`).
- **가입·설정**: [Brevo](https://www.brevo.com/) 가입 → 발신 도메인 또는 발신 주소 인증 →
  SMTP & API 메뉴에서 SMTP 키 발급. 배포 값은 `MAIL_HOST=smtp-relay.brevo.com`, `MAIL_PORT=587`(STARTTLS)이며
  `MAIL_USERNAME`은 Brevo SMTP 로그인, `MAIL_PASSWORD`는 SMTP 키, `MAIL_FROM`은 인증한 발신 주소다.
- **대체**: SMTP 서버라면 어디든 쓸 수 있다. 코드 기본값은 Gmail(`smtp.gmail.com:587`, 2단계 인증 후 앱 비밀번호)이다.
  배포 초기에 Gmail을 썼으나 발송 제한으로 계정이 정지되어 2026-09-15 Brevo로 바꿨다.
- **발송 상한**: 시간당 100통, 하루 300통(`MAIL_SEND_BUDGET_*`)으로 무료 한도 안에서 막는다.
- **로컬**: `MAIL_MODE=log`로 두면 메일 대신 백엔드 로그에 인증번호가 찍힌다.
- **코드**: `backend/src/main/java/com/feelm/backend/infrastructure/mail/`

## 2.4 추천 이유 생성 (SSAFY GMS Gateway, Google Gemini)

- **용도**: 추천 카드의 "추천 이유" 문장을 GBT 근거로부터 생성한다.
- **엔드포인트**: `https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`,
  헤더 `x-goog-api-key: ${GMS_KEY}`.
- **설정**: SSAFY GMS에서 키를 발급받아 `GMS_KEY`에 넣고 `GBT_XAI_ENABLED=true`로 켠다.
  모델은 `GEMINI_REASON_MODEL`(기본 `gemini-2.5-flash-lite`). 배포는 켜져 있다.
- **키가 없을 때**: 검증된 근거로 만든 고정 문구로 대체하므로 서비스는 동작한다.
- **코드**: `backend/src/main/java/com/feelm/backend/infrastructure/gemini/GeminiRecommendationReasonGenerator.java`

## 2.5 영화 데이터

### TMDb

- **용도**: 영화 제목·줄거리·개봉일·장르·국가·인물·키워드·포스터, 한국 OTT 제공처(`watch/providers`, `watch_region=KR`).
- **가입·설정**: [TMDb](https://www.themoviedb.org/) 가입 → 설정 → API → API 읽기 액세스 토큰(v4 Bearer) 발급.
  적재 스크립트 실행 셸에서만 `TMDB_TOKEN`으로 넘긴다. 서비스 런타임은 TMDb API를 호출하지 않는다.
- **이미지**: Nginx가 `/tmdb-images/{size}/{file}`을 `https://image.tmdb.org/t/p/...`로 프록시한다.
- **스크립트**: `backend/scripts/collect-tmdb-raw.sh`, `collect-tmdb-keywords.sh`, `prepare-tmdb-input.sh`,
  `prepare-tmdb-raw-handoff.sh`, `run-tmdb-ingestion.sh`. 절차는 [`backend/README.md`](../backend/README.md) 초기 적재 장.
- **OTT 연결**: 영화 상세의 OTT 링크는 TMDb가 제공하는 지역 제공처 페이지(JustWatch 데이터)이며 화면에 출처를 표시한다.
  OTT 사업자 API는 쓰지 않는다. 운영 절차는 [`backend/scripts/OTT-CATALOG-OPERATIONS.md`](../backend/scripts/OTT-CATALOG-OPERATIONS.md).
- **결정 기록**: [`docs/adr/0007-tmdb-movielens-data-sources.md`](../docs/adr/0007-tmdb-movielens-data-sources.md)

### MovieLens 32M (GroupLens)

- **용도**: 추천 모델 학습용 평점 데이터. 서비스 사용자와 연결하지 않는다.
- **받는 법**: [grouplens.org/datasets/movielens](https://grouplens.org/datasets/movielens/)에서 `ml-32m.zip`을 받아 푼다.
  가입은 필요 없고 연구·비상업 이용 조건을 따른다. `links.csv`로 TMDb ID를 연결하고 HDFS `/data/raw/movielens/ml-32m`에 올린다.

### KOBIS

- v26 GBT의 관객 수 피처에 쓴 공개 통계 페이지 수집 기록만 `pipeline/docs/assets/`에 보관한다. 키나 가입이 필요 없고
  서비스 런타임은 호출하지 않는다.

## 2.6 Hugging Face (E5 임베딩 모델)

- 모델 `intfloat/multilingual-e5-base`를 고정 revision(40자 커밋 SHA)으로 익명 다운로드한다. 토큰은 필요 없다.
- Spark 이미지의 `HF_HOME=/opt/spark/.cache/huggingface`에 캐시된다. 외부망이 막힌 서버는 이미지에 모델을 미리 넣는다.
- 절차: [`pipeline/spark/batch/embedding/README.md`](../pipeline/spark/batch/embedding/README.md)

## 2.7 Docker Hub

- CI가 이미지(`feelm-backend`, `feelm-frontend`, `feelm-foldin-consumer`, `feelm-spark-ml` 등)를 `<커밋 SHA>` 태그로 푸시하고
  서버가 받아 실행한다.
- **설정**: Docker Hub 가입 → Account Settings → Personal access tokens에서 Read & Write 토큰 발급 →
  GitLab CI 변수 `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`(Masked), `DOCKERHUB_NAMESPACE`에 등록.

## 2.8 Let's Encrypt (Certbot)

- webroot 방식으로 `/.well-known/acme-challenge/`를 `/var/www/certbot`에서 응답한다.
- 인증서 경로: `/etc/letsencrypt/live/j15e106.p.ssafy.io/{fullchain,privkey}.pem`
- 발급: `certbot certonly --webroot -w /var/www/certbot --cert-name j15e106.p.ssafy.io -d j15e106.p.ssafy.io`
- 갱신은 `certbot.timer`와 Nginx reload deploy hook으로 한다. 절차는 [`infra/README.md`](../infra/README.md) HTTPS 장.

## 2.9 Web Push (VAPID)

- 표준 Web Push로 FCM·Mozilla·Apple 푸시 엔드포인트에 보낸다. 제공자 가입은 필요 없고 VAPID 키 쌍을 직접 만든다
  (예: `npx web-push generate-vapid-keys`).
- `WEB_PUSH_MODE=vapid`, `WEB_PUSH_VAPID_PUBLIC_KEY`, `WEB_PUSH_VAPID_PRIVATE_KEY`, `WEB_PUSH_VAPID_SUBJECT=mailto:<관리자 주소>`.
  배포는 `disabled`다.
- 결정 기록: [`docs/adr/0022-standard-web-push-vapid-delivery.md`](../docs/adr/0022-standard-web-push-vapid-delivery.md)

## 2.10 협업·운영 도구

| 서비스 | 용도 | 설정 |
| --- | --- | --- |
| Mattermost (`meeting.ssafy.com`) | 사용자 의견 알림(백엔드), Grafana 알림 중계, 배포 알림, Jira 일일 알림 | `MATTERMOST_BASE_URL`, `MATTERMOST_LOGIN_ID`, `MATTERMOST_PASSWORD`, `MATTERMOST_CHANNEL_ID` 또는 `MATTERMOST_TEAM_NAME`+`MATTERMOST_CHANNEL_NAME`. 웹훅이 아니라 계정 로그인 REST API를 쓴다 |
| GitLab (`lab.ssafy.com`) | 소스·CI/CD·변수 관리. 러너 `e106-shell`(서비스 노드), `e106-distributed`(분산 노드), `e106-mr-isolated`(MR 검사) | [`docs/secret-environment-variables.md`](../docs/secret-environment-variables.md) |
| Jira (`ssafy.atlassian.net`) | 업무 생성 자동화 | `JIRA_*`, [`docs/jira/README.md`](../docs/jira/README.md) |
| Grafana·Prometheus·Loki | 자체 호스팅 모니터링, `https://j15e106.p.ssafy.io/grafana/` | [`infra/monitoring/README.md`](../infra/monitoring/README.md) |
| Claude Code | MR 자동 리뷰(개발 보조, 서비스 기능 아님) | [`infra/claude-review/README.md`](../infra/claude-review/README.md) |
