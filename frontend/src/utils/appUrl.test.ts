/*
 * 앱 밖으로 나가는 주소 (업무 S15P21E106-717)
 *
 * 공유한 프로필 주소가 404였다. `path.*`는 라우터 안에서 쓰는 경로라 앱이 놓인
 * 자리(`/app`)가 빠져 있는데, 그것을 `location.origin`에 그대로 붙였기 때문이다.
 * Nginx는 `/app/`만 앱으로 넘긴다 — 배포 서버에서 `/users/1024`는 404,
 * `/app/users/1024`는 200이었다.
 *
 * **시험에서는 자리를 세워 둔다.** vitest의 `import.meta.env.BASE_URL`은 `/`라(빌드의
 * `base: '/app/'`가 적용되지 않는다) 그대로 두면 배포와 같은 상황을 만들 수 없다.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { appUrl } from './appUrl';
import { path } from '../routes/paths';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('앱 밖으로 나가는 주소', () => {
  it('앱이 놓인 자리를 앞에 붙인다', () => {
    vi.stubEnv('BASE_URL', '/app/');

    expect(appUrl(path.publicProfile(1024))).toBe('http://localhost:3000/app/users/1024');
  });

  it('자리가 빗금으로 끝나지 않아도 붙인다', () => {
    vi.stubEnv('BASE_URL', '/app');

    expect(appUrl(path.publicProfile(1024))).toBe('http://localhost:3000/app/users/1024');
  });

  it('base를 두 번 붙이지 않는다', () => {
    vi.stubEnv('BASE_URL', '/app/');

    expect(appUrl(path.publicProfile(1024))).not.toContain('/app/app/');
  });

  it('앞의 빗금이 있든 없든 같은 주소가 된다', () => {
    vi.stubEnv('BASE_URL', '/app/');

    expect(appUrl('users/1024')).toBe(appUrl('/users/1024'));
  });

  it('라우터 경로에는 자리가 빠져 있다 — 그래서 이 함수가 필요하다', () => {
    expect(path.publicProfile(1024)).toBe('/users/1024');
  });
});
