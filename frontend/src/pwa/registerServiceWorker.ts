type ServiceWorkerRegistrationTarget = Pick<ServiceWorkerContainer, 'register'>;

type RegistrationOptions = {
  enabled?: boolean;
  baseUrl?: string;
  serviceWorker?: ServiceWorkerRegistrationTarget;
  onError?: (error: unknown) => void;
};

/**
 * 앱 렌더링과 독립적으로 서비스 워커를 등록한다.
 *
 * 등록 실패는 웹 앱 진입을 막지 않는다. 현재 서비스 워커는 캐시를 만들지 않는다.
 */
export async function registerServiceWorker({
  enabled = import.meta.env.PROD,
  baseUrl = import.meta.env.BASE_URL,
  serviceWorker = typeof navigator === 'undefined' ? undefined : navigator.serviceWorker,
  onError = (error) => console.warn('[PWA] 서비스 워커를 등록하지 못했습니다.', error),
}: RegistrationOptions = {}): Promise<ServiceWorkerRegistration | null> {
  if (!enabled || !serviceWorker) return null;

  const scope = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;

  try {
    return await serviceWorker.register(`${scope}service-worker.js`, { scope });
  } catch (error) {
    onError(error);
    return null;
  }
}
