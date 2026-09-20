/*
 * 앱 바깥으로 내보내는 절대 주소를 만든다 (업무 S15P21E106-717).
 *
 * **앱은 `/app` 아래에 있다**(`routes/router.tsx`의 `basename`). 그래서 `routes/paths.ts`가
 * 돌려주는 `/users/1024` 같은 값은 **라우터 안에서만** 통한다. 그것을 `location.origin`에
 * 그대로 이어 붙이면 `https://호스트/users/1024`가 되는데, Nginx는 `/app/`만 앱으로
 * 넘기므로 그 주소는 404다(배포 서버 확인 2026-09-20).
 *
 * 공유 · 복사처럼 **주소가 앱 밖으로 나가는 자리**에서는 이 함수를 쓴다. 라우터에 넘기는
 * 값에는 쓰지 않는다 — base가 두 번 붙어 `/app/app/...`이 된다.
 */

/**
 * 라우터 경로를 절대 주소로 바꾼다.
 *
 * @param routerPath `path.*`가 돌려주는 값. 앞의 `/`는 있어도 없어도 된다
 */
export function appUrl(routerPath: string): string {
  const base = import.meta.env.BASE_URL.endsWith('/')
    ? import.meta.env.BASE_URL
    : `${import.meta.env.BASE_URL}/`;

  return new URL(`${base}${routerPath.replace(/^\//, '')}`, window.location.origin).toString();
}
