import { describe, expect, it, vi } from 'vitest';
import { registerServiceWorker } from './registerServiceWorker';

describe('서비스 워커 등록', () => {
  it('/app/ 아래의 고정 파일과 범위를 사용한다', async () => {
    const registration = {} as ServiceWorkerRegistration;
    const register = vi.fn().mockResolvedValue(registration);

    await expect(
      registerServiceWorker({
        enabled: true,
        baseUrl: '/app',
        serviceWorker: { register },
      }),
    ).resolves.toBe(registration);

    expect(register).toHaveBeenCalledWith('/app/service-worker.js', { scope: '/app/' });
  });

  it('등록 실패를 보고하되 호출자에게 오류를 전파하지 않는다', async () => {
    const error = new Error('registration failed');
    const onError = vi.fn();

    await expect(
      registerServiceWorker({
        enabled: true,
        baseUrl: '/app/',
        serviceWorker: { register: vi.fn().mockRejectedValue(error) },
        onError,
      }),
    ).resolves.toBeNull();

    expect(onError).toHaveBeenCalledWith(error);
  });

  it('개발 빌드에서는 서비스 워커를 만들지 않는다', async () => {
    const register = vi.fn();

    await expect(
      registerServiceWorker({ enabled: false, serviceWorker: { register } }),
    ).resolves.toBeNull();
    expect(register).not.toHaveBeenCalled();
  });
});
