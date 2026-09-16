/// <reference types="vite/client" />

/*
 * 번들에 들어가는 빌드 변수의 타입.
 *
 * `vite/client`의 `ImportMetaEnv`는 아무 이름이나 `any`로 받아 준다. 오타를 잡고 어떤
 * 값이 있는지 한곳에서 보이도록 이 앱이 읽는 것만 선언한다. 실제 값은 `.env`와 GitLab
 * CI 변수에서 오고 저장소에는 `.env.example`만 둔다.
 *
 * **Secret은 여기 오지 않는다.** 번들에 들어가는 값이므로 공개해도 되는 것만 둔다
 * (`docs/secret-environment-variables.md` 4장).
 */
interface ImportMetaEnv {
  /** 소셜 로그인 client-id. 없으면 그 제공자 버튼이 잠긴다 (API-007) */
  readonly VITE_GOOGLE_CLIENT_ID?: string;
  readonly VITE_KAKAO_CLIENT_ID?: string;
  readonly VITE_NAVER_CLIENT_ID?: string;
  /** 각 사 콘솔·백엔드 허용 목록에 등록한 콜백 주소가 기본 규칙과 다를 때만 쓴다 */
  readonly VITE_OAUTH_REDIRECT_URI?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
