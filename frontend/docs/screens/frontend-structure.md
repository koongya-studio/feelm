# React 프론트엔드 화면 구조 설계

Epic [S15P21E106-266]의 화면 구조 설계입니다. 화면 목록과 요소는
[Screen Spec](./screen-spec.md), 기준 자료와 우선순위는
[화면 문서 지도](./README.md)를 따릅니다.

## 1. 목적과 범위

Screen Spec의 15개 화면을 React 애플리케이션 구조로 옮기기 위한 설계입니다. 다음을
정합니다.

- 라우팅 구조와 경로
- 레이아웃 계층
- 폴더 구조와 파일 배치 규칙
- 화면 간 상태 전달 규칙
- 목업 데이터 계층과 교체 지점
- 타입 정의 방침

이 문서는 구조만 정합니다. 공통 컴포넌트의 목록과 사용 기준은
`[UI/UX] 공통 컴포넌트 구조 및 사용 기준 1차 정의`(S15P21E106-102)에서 정의하고, 실제
프로젝트 생성은 `[개발] React 프로젝트 초기 세팅`(S15P21E106-104)에서 수행합니다.

## 2. 확정된 기술 선택

### 2.1 ADR로 확정된 사항

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 프레임워크 | React | ADR-0013 |
| 빌드 도구 | Vite (개발 서버 · Production Build) | ADR-0013 |
| 형태 | 반응형 웹앱 | ADR-0013 |
| 팝콘 버킷 렌더링 | React에서 SVG | ADR-0013 |
| 인증 토큰 전달 | ADR-0009을 따름 | ADR-0013 |

### 2.2 이번 업무에서 확정한 사항

ADR-0013이 후속 작업으로 남긴 `React 프로젝트 구조·상태 관리·테스트 전략 확정` 중
구조와 상태 관리 부분입니다.

| 항목 | 값 | 이유 |
| --- | --- | --- |
| 언어 | TypeScript | `docs/api`에 응답 구조가 상세히 정의되어 있어 타입으로 옮길 수 있습니다. `WatchStatus`·`RecommendationType`·`PopcornFlavor`·`Visibility` 같은 열거형이 많고, 계약 불일치를 컴파일 단계에서 잡을 수 있습니다 |
| 라우팅 | React Router | 요구사항에서 지정 |
| 상태 관리 | React Router state + Context | 화면 간 전달 13개 흐름이 대부분 인접 화면 1회성 전달입니다. 여러 화면이 공유하는 상태는 온보딩 진행값 하나뿐이라 전역 상태 라이브러리가 필요하지 않습니다 |
| 스타일링 | CSS Modules | Vite 내장 기능이라 의존성이 늘지 않습니다. 디자인 방향이 미확정이므로 토큰을 CSS 변수로 두어 확정 후 한 곳에서 교체합니다 |
| 데이터 접근 | `src/api/` 함수 계층 (이번 단계는 목업 반환) | 실제 연동 시 교체 지점을 한 곳으로 모읍니다 |

**추가되는 외부 의존성은 `react`, `react-dom`, `react-router-dom`, `typescript`,
`vite`와 그 타입 패키지뿐입니다.** 상태 관리·스타일링·데이터 페칭 라이브러리는 도입하지
않습니다.

라이브러리를 추가해야 할 근거가 생기면 임의로 넣지 않고 먼저 공유합니다
([`ai-collaboration.md`](../../../docs/ai-collaboration.md) 8장).

### 2.3 이번 업무에서 정하지 않은 것

| 항목 | 정할 업무 |
| --- | --- |
| 테스트 전략과 도구 | 별도 결정 필요. ADR-0013 후속 작업 |
| Vite 환경변수 규칙 | S15P21E106-104 프로젝트 초기 세팅. ADR-0013 후속 작업 |
| Lint · Format 도구 | S15P21E106-104 |
| 공통 컴포넌트 목록과 API | S15P21E106-102 |
| 디자인 토큰 값 | `DESIGN_GOALS.md` 미확정 항목 해소 후 |

## 3. 라우팅 구조

### 3.1 경로 목록

| 경로 | 화면 | 레이아웃 | 비고 |
| --- | --- | --- | --- |
| `/onboarding/taste` | 1.1 영화 취향 초기 설정 | Onboarding | |
| `/onboarding/ott` | 1.2 구독 OTT 초기 설정 | Onboarding | |
| `/onboarding/complete` | 1.3 첫 취향 필름 생성 완료 | Onboarding | |
| `/` | 2.1 로그인 후 홈 | App | 탭 `홈` |
| `/recommendations` | 2.2 오늘의 추천 · 2.2-A 추천 실패 | App | 탭 `홈` 유지 |
| `/movies/:movieId` | 2.4 영화 상세 | FullScreen | |
| `/movies/:movieId/rate` | 2.5 영화 평가 | FullScreen | |
| `/movies/:movieId/rate/result` | 2.6 필름 추가 중 · 2.7 필름 추가 완료 | FullScreen | 한 라우트의 두 단계 |
| `/watch-records/:watchRecordId/confirm` | 2.4-A 감상 확인 | FullScreen | |
| `/film` | 3.1 취향 필름 메인 | App | 탭 `필름` |
| `/film/popcorn` | 3.2 100알의 취향 | App | 탭 `필름` 유지 |
| `/frames/:frameId` | 3.1-A 프레임 상세 | FullScreen | |
| `/parties/:partyId/popcorn` | 5.8 파티원 팝콘 한눈에 보기 | FullScreen | |
| `/parties/:partyId/popcorn/mix` | 5.9 파티 팝콘 섞기 | FullScreen | |
| `/search` `/party` `/my` | 범위 밖 | App | 각 탭. 아래 3.5 참조 |
| `*` | 없는 경로 | FullScreen | 아래 3.6 참조 |

경로 문자열은 `src/routes/paths.ts`에 상수로 모으고, 화면에서 문자열을 직접 쓰지
않습니다.

### 3.2 라우트 트리

```text
App
├─ AppLayout                        하단 내비 5탭 표시
│  ├─ /                             HomePage                    2.1
│  ├─ /recommendations              RecommendationsPage         2.2 · 2.2-A
│  ├─ /film                         FilmPage                    3.1
│  ├─ /film/popcorn                 PopcornBucketPage           3.2
│  ├─ /search                       NotInScopePage              범위 밖
│  ├─ /party                        NotInScopePage              범위 밖
│  └─ /my                           NotInScopePage              범위 밖
│
├─ OnboardingLayout                 하단 내비 없음
│  ├─ /onboarding/taste             TasteOnboardingPage         1.1
│  ├─ /onboarding/ott               OttOnboardingPage           1.2
│  └─ /onboarding/complete          OnboardingCompletePage      1.3
│
└─ FullScreenLayout                 하단 내비 없음 · 뒤로 가기
   ├─ /movies/:movieId              MovieDetailPage             2.4
   ├─ /movies/:movieId/rate         MovieRatingPage             2.5
   ├─ /movies/:movieId/rate/result  RatingResultPage            2.6 · 2.7
   ├─ /watch-records/:id/confirm    WatchConfirmPage            2.4-A
   ├─ /frames/:frameId              FrameDetailPage             3.1-A
   ├─ /parties/:partyId/popcorn     PartyPopcornPage            5.8
   ├─ /parties/:partyId/popcorn/mix PartyPopcornMixPage         5.9
   └─ *                             NotFoundPage
```

### 3.3 라우트로 만들지 않는 화면

Screen Spec 3.2절에서 미확정으로 남긴 항목을 여기서 정합니다.

| 화면 | 결정 | 이유 |
| --- | --- | --- |
| 2.3 추천 카드 ⋯ 메뉴 | `/recommendations` 내부 상태 | 어느 카드의 메뉴인지가 화면 상태이고, 열고 닫는 동작이 화면 이동이 아닙니다 |
| 2.3-A 관심없음 처리 | `/recommendations` 내부 상태 | 토스트와 카드 교체는 목록 화면의 상태 변화입니다 |
| 2.4-A 감상 확인 | **라우트로 만듦** | 알림(8.1 · 8.2)에서 특정 감상 기록으로 진입하는 화면이라 식별자가 URL에 필요합니다 |

**2.3 · 2.3-A의 대가**: 시트가 열린 상태에서 브라우저 뒤로 가기를 누르면 시트가 닫히지
않고 이전 화면으로 이동합니다. 이 동작을 바꿔야 한다면 라우트로 승격하는 것이 맞습니다.
공통 레이아웃 업무(S15P21E106-255)에서 시트 컴포넌트를 만들 때 다시 확인합니다.

### 3.4 진입 경로가 없는 화면

Screen Spec에서 확인된 문제입니다. 두 화면은 범위 안에 정상 진입 경로가 없습니다.

| 화면 | 유일한 진입 경로 | 상태 |
| --- | --- | --- |
| 2.4-A 감상 확인 | 8.1 알림 목록 · 8.2 푸시 알림 | 둘 다 범위 밖 |
| 5.8 파티원 팝콘 | 5.1 파티 목록 | 범위 밖 |

**라우트로 만들어 해결합니다.** 두 화면 모두 URL로 직접 접근할 수 있어 개발 중 확인이
가능하고, 나중에 알림 딥링크와 파티 목록이 구현되면 그대로 연결됩니다. 별도 임시 진입
버튼을 만들지 않습니다.

`[?]` 3.2 → 5.9 이동은 `partyId`가 없어 이번 단계에서 연결하지 않습니다. Screen Spec의
미확정 항목이며 5.9는 5.8에서만 진입합니다.

### 3.5 범위 밖 탭 처리

Screen Spec 3.1절에서 미확정으로 남긴 항목입니다. 하단 내비는 REF-01에 따라 5탭을 모두
표시해야 하지만 검색 · 파티 · MY는 이번 범위가 아닙니다.

**결정**: 탭은 5개 모두 표시하고 활성화하되, 검색 · 파티 · MY는 `NotInScopePage`로
이동합니다. 이 화면은 해당 기능이 이번 단계 범위가 아님을 알리고 홈으로 돌아가는 수단을
제공합니다.

이유는 세 가지입니다.

- REF-01이 정한 5탭 구성과 색상 규칙을 그대로 검증할 수 있습니다
- 탭을 비활성으로 두면 선택 상태 스타일을 확인할 수 없습니다
- 각 화면이 구현되면 해당 라우트의 요소만 교체하면 됩니다

`NotInScopePage`는 와이어프레임에 없는 화면입니다. 개발 확인용 최소 화면이며 새 디자인
컨셉이나 요소를 추가하지 않습니다.

### 3.6 없는 경로

`NotFoundPage`를 둡니다. 와이어프레임에 해당 화면이 없으므로 최소 안내와 홈 이동만
제공하고, 상태 UI 업무(S15P21E106-264)에서 오류 화면과 함께 다룹니다.

### 3.7 라우트 보호

이번 단계는 인증을 구현하지 않으므로 라우트 보호를 넣지 않습니다.

와이어프레임 이동표에는 `비로그인 ⇢ 0.6 홈·필름·MY 접근 시 로그인으로 이동`이 있고,
계약도 대부분의 API를 `회원`으로 정하고 있습니다. 인증 화면(0.x)이 범위 밖이므로 보호
계층은 후속 업무에서 추가합니다. 라우터 정의를 `src/routes/router.tsx` 한 곳에 모아
두므로 나중에 감싸는 것으로 처리할 수 있습니다.

## 4. 레이아웃 계층

| 레이아웃 | 하단 내비 | 적용 화면 | 근거 |
| --- | --- | --- | --- |
| `AppLayout` | 표시 | 2.1, 2.2, 2.2-A, 3.1, 3.2 + 범위 밖 3탭 | REF-01 적용 화면 |
| `OnboardingLayout` | 없음 | 1.1, 1.2, 1.3 | REF-01 미적용 — 초기 취향 설정 |
| `FullScreenLayout` | 없음 | 2.4, 2.4-A, 2.5, 2.6, 2.7, 3.1-A, 5.8, 5.9 | REF-01 미적용 — 영화 상세 · 평가 · 완료 · 전체화면 콘텐츠 |

REF-01은 `취향 필름 메인 · 팝콘 버킷`을 내비 적용 화면으로, `영화 상세 · 감상 확인 ·
평가`와 `로딩 · 완료 · 전체화면 콘텐츠`를 미적용 화면으로 정하고 있습니다. 위 배정은 이
규칙을 그대로 따릅니다.

### 4.1 하단 내비 선택 상태

| 탭 | 선택되는 경로 |
| --- | --- |
| 홈 | `/`, `/recommendations` |
| 검색 | `/search` |
| 필름 | `/film`, `/film/popcorn` |
| 파티 | `/party` |
| MY | `/my` |

`/recommendations`와 `/film/popcorn`은 하위 화면이지만 상위 탭의 선택 상태를
유지합니다. 경로 접두사로 판정합니다.

### 4.2 2.6 · 2.7을 한 라우트로 두는 이유

2.6은 자동으로 2.7로 넘어가는 중간 상태이고, 뒤로 가기로 돌아갈 대상이 아닙니다.
`/movies/:movieId/rate/result` 한 라우트에서 내부 단계(`adding` → `added`)로 전환합니다.

`[?]` 전환 시간과 자동 진행 여부는 Screen Spec의 미확정 항목입니다.

이 화면은 평가 저장 결과와 추가 전 카운트를 Router state로 받습니다. 새로 고침하면
state가 사라지므로 `/film`으로 리다이렉트합니다 (6.3절 참조).

## 5. 폴더 구조

기존 `frontend/docs/`와 `frontend/landing/`은 건드리지 않습니다. React 프로젝트는
`frontend/`를 루트로 구성하고 소스는 `frontend/src/`에 둡니다
([`frontend/README.md`](../../README.md)의 방침).

```text
frontend/
├─ index.html
├─ package.json
├─ tsconfig.json
├─ tsconfig.node.json
├─ vite.config.ts
├─ docs/                       기존. 화면 설계 문서
├─ landing/                    기존. 정적 랜딩 페이지
├─ public/
└─ src/
   ├─ main.tsx                 진입점
   ├─ App.tsx                  RouterProvider
   │
   ├─ routes/
   │  ├─ paths.ts              경로 상수
   │  └─ router.tsx            라우트 정의
   │
   ├─ layouts/
   │  ├─ AppLayout/
   │  ├─ OnboardingLayout/
   │  └─ FullScreenLayout/
   │
   ├─ pages/
   │  ├─ onboarding/
   │  │  ├─ TasteOnboardingPage/
   │  │  ├─ OttOnboardingPage/
   │  │  └─ OnboardingCompletePage/
   │  ├─ home/
   │  │  └─ HomePage/
   │  ├─ recommendations/
   │  │  └─ RecommendationsPage/
   │  ├─ movies/
   │  │  ├─ MovieDetailPage/
   │  │  ├─ MovieRatingPage/
   │  │  └─ RatingResultPage/
   │  ├─ watch/
   │  │  └─ WatchConfirmPage/
   │  ├─ film/
   │  │  ├─ FilmPage/
   │  │  ├─ PopcornBucketPage/
   │  │  └─ FrameDetailPage/
   │  ├─ party/
   │  │  ├─ PartyPopcornPage/
   │  │  └─ PartyPopcornMixPage/
   │  └─ system/
   │     ├─ NotInScopePage/
   │     └─ NotFoundPage/
   │
   ├─ components/              공통 UI. 목록은 S15P21E106-102에서 정의
   │
   ├─ contexts/
   │  └─ OnboardingContext.tsx
   │
   ├─ api/                     화면이 호출하는 데이터 접근 함수
   ├─ mocks/                   목업 데이터 원본
   ├─ types/                   API 계약 타입과 열거형
   ├─ hooks/
   ├─ utils/
   └─ styles/
      ├─ tokens.css            디자인 토큰 (CSS 변수)
      └─ global.css            리셋과 기본 타이포그래피
```

### 5.1 파일 배치 규칙

**컴포넌트와 페이지는 폴더 단위**로 둡니다.

```text
HomePage/
├─ index.tsx
└─ HomePage.module.css
```

화면 전용 하위 컴포넌트는 그 화면 폴더 안에 둡니다.

```text
HomePage/
├─ index.tsx
├─ HomePage.module.css
├─ TodayRecommendationCard/
│  ├─ index.tsx
│  └─ TodayRecommendationCard.module.css
└─ TasteSummarySection/
   ├─ index.tsx
   └─ TasteSummarySection.module.css
```

두 개 이상의 화면이 쓰게 되면 `src/components/`로 올립니다. 올릴 때 사용 중인 화면에
의도하지 않은 변경이 생기지 않는지 확인합니다.

### 5.2 이름 규칙

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| 컴포넌트 · 페이지 폴더 | PascalCase | `MovieDetailPage/` |
| 그 외 폴더 | camelCase | `routes/`, `mocks/` |
| 컴포넌트 파일 | `index.tsx` | |
| 스타일 파일 | `{컴포넌트명}.module.css` | `HomePage.module.css` |
| 타입 · 유틸 파일 | camelCase | `watchStatus.ts` |
| 타입 이름 | PascalCase | `RecommendationItem` |
| 열거형 값 | 계약 그대로 UPPER_SNAKE_CASE | `LINK_CLICKED` |

페이지 컴포넌트 이름은 `{역할}Page`로 끝냅니다. 화면 번호는 파일명에 넣지 않고 각 파일
상단 주석에 적어 Screen Spec과 연결합니다.

## 6. 화면 간 상태 전달

Screen Spec 10장의 13개 흐름을 구현하는 규칙입니다.

### 6.1 전달 수단 선택 기준

| 수단 | 쓰는 경우 | 예 |
| --- | --- | --- |
| URL 경로 파라미터 | 리소스 식별자. 새로 고침·공유로도 유지돼야 하는 값 | `movieId`, `frameId`, `partyId`, `watchRecordId` |
| Router state | 인접 화면으로 한 번만 넘기는 부가 정보 | `recommendationItemId`, 평가 결과, 추가 전 카운트 |
| Context | 여러 화면이 순차로 쌓아 가는 진행 상태 | 온보딩 선택값 |
| 화면 내부 state | 그 화면에서만 쓰는 상태 | 시트 열림, 선택된 카드 |

**URL에 넣을 값과 Router state에 넣을 값의 구분**: 그 화면을 URL만으로 다시 열 수
있어야 하는 값은 URL에, 없어도 화면이 성립하는 부가 정보는 Router state에 둡니다.

### 6.2 흐름별 전달 방식

| 흐름 | 수단 | 전달값 |
| --- | --- | --- |
| 1.1 → 1.2 | Context | 선택한 취향 목록, 건너뛰기 여부 |
| 1.2 → 1.3 | Context | 선택한 OTT 목록 |
| 2.1 → 2.2 | 없음 | |
| 2.2 → 2.4 | URL + Router state | `movieId` / `recommendationItemId` |
| 2.2 → 2.3 | 화면 내부 state | 선택한 카드 |
| 2.3 → 2.4 | URL + Router state | `movieId` / `recommendationItemId` |
| 2.3 → 2.3-A | 화면 내부 state | 관심없음 처리한 카드, 교체 후보 |
| 2.4 → 2.5 | URL + Router state | `movieId` / 기존 평가, 감상 기록(OTT · 감상일) |
| 2.4-A → 2.5 | URL + Router state | `movieId` / `watchRecordId` |
| 2.5 → 2.6 · 2.7 | URL + Router state | `movieId` / 평가 결과, 추가 전 프레임·팝콘 수 |
| 3.1 → 3.1-A | URL | `frameId` |
| 3.1-A → 2.5 | URL + Router state | `movieId` / `ratingId` |
| 5.8 → 5.9 | URL + Router state | `partyId` / 멤버 팝콘 집계 |

### 6.3 Router state가 없을 때의 대체 동작

Router state는 새로 고침이나 URL 직접 접근 시 사라집니다. 화면마다 대체 동작을
정의합니다.

| 화면 | state 없을 때 |
| --- | --- |
| 2.4 영화 상세 | 추천 이유 영역을 표시하지 않고 나머지를 그대로 표시. 계약과 일치 — API-030은 `recommendationItemId`가 없으면 `recommendation`을 생략합니다 |
| 2.5 영화 평가 | `movieId`로 영화와 기존 평가를 다시 조회. 감상 정보가 없으면 해당 줄을 표시하지 않음 |
| 2.6 · 2.7 결과 | **`/film`으로 리다이렉트.** 추가 전 카운트를 복원할 수 없고, 완료 화면을 다시 보여줄 근거가 없습니다 |
| 5.9 팝콘 섞기 | `partyId`로 팝콘 집계를 다시 조회 |
| 1.1 · 1.2 · 1.3 | Context가 비어 있으면 `/onboarding/taste`로 이동 |

### 6.4 Context 사용 범위

`OnboardingContext` 하나만 둡니다. `OnboardingLayout` 안에서만 제공하며, 온보딩을
벗어나면 사라집니다.

| 항목 | 값 |
| --- | --- |
| 보관 값 | 취향 선택 목록, 건너뛰기 여부, 선택한 OTT 목록 |
| 제공 위치 | `OnboardingLayout` |
| 소멸 시점 | 온보딩 레이아웃 이탈 |

`[?]` 온보딩 도중 앱을 닫았을 때 진행값을 유지할지는 미확정입니다. 유지가 필요하면
저장 위치(세션 스토리지 등)를 별도로 결정합니다.

전역 상태가 더 필요해지면 Context를 늘리기보다 먼저 공유하고 결정합니다. 인증 · 사용자
프로필 · 추천 세트처럼 여러 탭이 공유하는 상태가 생기면 상태 관리 방식을 다시 검토해야
합니다.

## 7. 데이터 계층

### 7.1 두 계층으로 분리

```text
화면 (pages)
   ↓  호출
src/api/          데이터 접근 함수. 화면이 아는 유일한 경계
   ↓  이번 단계
src/mocks/        목업 데이터 원본 (API 응답 형태)
```

화면은 `src/mocks/`를 직접 import하지 않고 항상 `src/api/`를 호출합니다. 실제 연동
단계에서는 `src/api/` 함수 본문만 교체하면 화면을 고치지 않아도 됩니다.

### 7.2 `src/api/` 구성

`docs/api`의 도메인 구분을 그대로 따릅니다.

| 파일 | 대응 명세 |
| --- | --- |
| `onboarding.ts` | API-023, API-024 |
| `otts.ts` | API-025, API-026, API-027 |
| `users.ts` | API-014 |
| `movies.ts` | API-030, API-031, API-032 |
| `watch.ts` | API-033, API-034, API-035, API-036, API-037 |
| `taste.ts` | API-040, API-041, API-042, API-043, API-044 |
| `recommendations.ts` | API-045, API-046, API-047 |
| `parties.ts` | API-050, API-057 |

함수 이름은 명세의 이름을 따릅니다. 각 함수 주석에 대응 API ID를 적어 계약과 연결합니다.

```text
// API-045 기본 추천 조회 · GET /api/v1/recommendations
export async function getRecommendations(): Promise<RecommendationSet>
```

### 7.3 `src/mocks/` 구성

목업 데이터는 **API 명세의 응답 구조를 그대로** 따릅니다. 명세에 없는 필드는 만들지
않습니다. 화면에 필요한데 계약에 없는 값은 Screen Spec 8.2절의 미확정 항목이며 해당
요소를 구현 보류합니다.

| 파일 | 내용 |
| --- | --- |
| `movies.ts` | 영화 기준 데이터. 다른 목업이 참조하는 원본 |
| `popcornFlavors.ts` | 팝콘 맛 8종 기준정보 |
| `otts.ts` | OTT 기준정보 |
| `user.ts` | 목업 사용자 프로필 |
| `recommendations.ts` | 추천 세트 |
| `ratings.ts` | 평가와 프레임 |
| `popcorn.ts` | 팝콘 버킷 집계 |
| `taste.ts` | 취향 분석과 키워드 |
| `parties.ts` | 파티와 파티원 팝콘 |

**영화 데이터는 `movies.ts` 한 곳에만 정의**하고 추천 · 평가 · 프레임 목업은 `movieId`로
참조합니다. 같은 영화 정보를 여러 파일에 중복해서 쓰지 않습니다.

와이어프레임의 표시값(본 영화 100편 · 팝콘 100알 등)을 기준 수치로 사용하면 화면과
데이터가 맞습니다. 이 값들은 모두 목업이며 측정 결과가 아닙니다.

`[?]` 팝콘 맛 8종의 표시명과 색상은 API-044 미정 사항입니다. `popcornFlavors.ts`에
잠정 값을 두고 확정 후 이 파일만 교체합니다. 화면은 `code`로 매칭하고 표시명 · 색상을
하드코딩하지 않습니다.

### 7.4 지연과 오류 상태

`src/api/` 함수는 `Promise`를 반환합니다. 로딩 · 빈 데이터 · 오류 상태 UI를 만드는
업무(S15P21E106-264)에서 지연과 실패를 주입할 수 있도록, 목업 반환에 지연과 오류
시뮬레이션 지점을 남깁니다.

2.2-A는 API-045가 `503 RECOMMENDATION_UNAVAILABLE`을 반환한 상황이므로, 오류 시뮬레이션
없이는 화면을 확인할 수 없습니다.

## 8. 타입 정의

### 8.1 배치

```text
src/types/
├─ enums.ts        공통 규약의 상태값
├─ common.ts       페이지네이션, 오류 응답
├─ movie.ts
├─ recommendation.ts
├─ watch.ts
├─ taste.ts
├─ party.ts
├─ onboarding.ts
└─ user.ts
```

### 8.2 열거형

공통 규약의 상태값을 타입으로 옮깁니다. 값은 계약 그대로 UPPER_SNAKE_CASE 문자열입니다.

| 타입 | 값 |
| --- | --- |
| `WatchStatus` | `LINK_CLICKED`, `WATCHED_UNRATED`, `RATED`, `EXPIRED` |
| `Visibility` | `PUBLIC`, `PRIVATE` |
| `RecommendationType` | `TASTE`, `DISCOVERY`, `PARTY` |
| `OnboardingPreference` | `LIKE`, `DISLIKE` |
| `InvitationStatus` | `PENDING`, `ACCEPTED`, `DECLINED` |
| `PartyStatus` | `ACTIVE`, `CLOSED` |

팝콘 맛(`PopcornFlavor`)은 코드가 기준정보로 관리되므로 고정 열거형이 아니라 문자열
타입으로 두고, 값은 API-044로 조회합니다.

### 8.3 계약과의 관계

타입은 계약의 사본입니다. 계약이 바뀌면 타입을 고치고, 타입을 먼저 바꿔 계약을 앞서지
않습니다. 계약에 없는 필드를 타입에 넣지 않습니다.

Screen Spec 8.1절에서 계약 기준으로 확정한 항목은 타입에도 반영합니다.

| 항목 | 타입 |
| --- | --- |
| 평가 점수 | `score: number` — 0.5~5.0, 0.5점 단위. 평균 평점에는 이 단위 제약을 적용하지 않음 |
| 감상 확인 응답 | `watched: boolean` — 2개 값 |
| 한줄평 공개 범위 | `visibility: Visibility` — `comment`가 있으면 필수 |

## 9. 스타일 구조

### 9.1 토큰

`src/styles/tokens.css`에 CSS 변수로 둡니다. 확정된 값만 넣고 미확정 값은 넣지
않습니다.

| 확정 | 값 | 근거 |
| --- | --- | --- |
| 배경 | 흰색 | `DESIGN_GOALS.md` 1.1 확정 |
| 본문 텍스트 | 검정 계열 | `DESIGN_GOALS.md` 1.2 확정 |
| 내비 선택 탭 | `#3C3A36` | REF-01 |
| 내비 비선택 탭 | `#A5A29B` | REF-01 |
| 내비 높이 | 66px | REF-01 |

`[?]` Accent color, Surface 단계, Motion 언어, 필름 · 팝콘 시각 표현은 미확정입니다
(`DESIGN_GOALS.md` 2.1~2.8). 이번 단계는 와이어프레임의 무채색 구성을 그대로 구현하고
확정 후 토큰 파일에서 교체합니다.

와이어프레임에서 반복되는 값(테두리 색, 라운드, 여백)은 토큰으로 올릴 후보입니다. 무엇을
토큰으로 만들지는 공통 컴포넌트 업무(S15P21E106-102)에서 정합니다.

### 9.2 CSS Modules 규칙

- 클래스 이름은 camelCase
- 전역 스타일은 `global.css`에만 둡니다
- 컴포넌트 스타일이 다른 컴포넌트에 영향을 주지 않도록 자손 선택자로 외부 요소를
  지정하지 않습니다

### 9.3 반응형

ADR-0013이 반응형 웹앱으로 정하고 있고, 와이어프레임은 Android 412px 프레임 기준입니다.

**결정**: 모바일 우선으로 작성하고, 넓은 화면에서는 콘텐츠 최대 폭을 제한해 가운데
정렬합니다. 데스크톱 전용 배치는 만들지 않습니다. 와이어프레임에 해당 배치가 없어
근거가 없습니다.

`[?]` 최대 폭 값과 넓은 화면에서 하단 내비를 어떻게 둘지는 공통 레이아웃
업무(S15P21E106-255)에서 정합니다.

## 10. 접근성 기준

와이어프레임의 배치와 디자인은 바꾸지 않는 범위에서 적용합니다.

| 항목 | 기준 |
| --- | --- |
| 하단 내비 | `nav` 요소, 현재 탭에 `aria-current="page"` |
| 시트 · 오버레이 (2.3, 2.3-A) | 열릴 때 초점 이동, `Esc`로 닫기, 배경 내용에 초점 이동 차단 |
| 별점 입력 (2.5) | 키보드로 조작 가능한 입력. 값이 이름으로 읽히도록 라벨 제공 |
| 이미지 | 포스터에 영화 제목을 대체 텍스트로 제공. 장식 이미지는 `alt=""` |
| 상태 변화 (2.3-A 토스트) | `role="status"`로 알림 |
| 진행 상태 (2.6) | `role="status"`로 진행 중임을 알림 |
| 색상만으로 구분하지 않기 | 팝콘 맛 범례는 색과 함께 이름 · 수치를 표시. 와이어프레임과 일치 |
| 초점 표시 | 기본 초점 표시를 제거하지 않음 |

`[?]` 색상 대비는 Accent color와 팝콘 맛 색상이 확정된 뒤 검증합니다.

## 11. 공통 파일 변경 예고

`[개발] React 프로젝트 초기 세팅`(S15P21E106-104)에서 저장소 공통 파일을 건드려야
합니다. [`ai-collaboration.md`](../../../docs/ai-collaboration.md) 5장이 구현 전 공유를
요구하는 항목입니다.

| 파일 | 필요한 변경 | 위험 |
| --- | --- | --- |
| `.gitignore` | `node_modules/`, `frontend/dist/`, Vite 캐시 추가. 현재 항목이 없습니다 | 중간. 공통 설정 |
| `frontend/package.json` `package-lock.json` | 신규 생성 | 중간. 의존성 · lock 파일 |
| `frontend/README.md` | React 프로젝트 실행 방법 추가 | 낮음 |
| `.gitlab-ci.yml` | 프론트엔드 빌드 · 검사 잡. 현재 백엔드 잡만 있습니다 | 높음. CI 설정 |

`.gitlab-ci.yml` 변경은 이번 에픽에 필수가 아니므로 **별도 업무로 분리하는 것을
제안**합니다. 나머지는 프로젝트 초기 세팅에 필요합니다.

`frontend/landing/`은 독립 배포 자산이므로 건드리지 않습니다. `landing/index.html`의
`meta[name="feelm-app-url"]` 연결은 실제 배포 작업이며 이번 범위가 아닙니다
([`frontend/README.md`](../../README.md)).

## 12. 남은 미확정

| 항목 | 정할 곳 |
| --- | --- |
| 테스트 전략과 도구 | 별도 결정. ADR-0013 후속 작업 |
| Vite 환경변수 규칙 | S15P21E106-104 |
| Lint · Format 도구 | S15P21E106-104 |
| 온보딩 진행값 유지 여부 | 필요 시 별도 결정 |
| 시트를 라우트로 승격할지 | S15P21E106-255 |
| 콘텐츠 최대 폭과 넓은 화면 내비 | S15P21E106-255 |
| 토큰으로 올릴 값 목록 | S15P21E106-102 |
| 2.6 전환 시간과 자동 진행 | Screen Spec 미확정 |
| 3.2 → 5.9의 `partyId` | Screen Spec 미확정 |
| 색상 대비 검증 | 디자인 확정 후 |
