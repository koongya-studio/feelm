/*
 * 남은 브라우저 Web Push 구독 정리 (업무 S15P21E106-859).
 *
 * 7.8-C `이 기기로 푸시 받기`(업무 -583)는 걷어냈다. 앱은 더 이상 구독을 만들지 않고, 받은
 * 푸시를 띄우던 서비스 워커 처리도 없다. 그 전에 켜 둔 기기에는 구독이 남아 있을 수 있는데,
 * 남겨 두면 로그아웃해도 이전 계정의 푸시가 오고, 띄울 곳이 없는 푸시는 브라우저가 기본
 * 알림으로 대신 띄운다. 그래서 앱이 뜰 때 이 기기의 구독을 지운다.
 *
 * 서버 구독(API-077)은 부르지 않는다. 기기 구독이 사라지면 서버의 다음 발송이 Push
 * Service에서 만료(404 · 410)로 돌아와 서버가 구독을 닫는다(Web Push 발송 규칙).
 */

/** 예전 구독이 남긴 서버 구독 번호 */
const LEGACY_SUBSCRIPTION_KEY = 'feelm.webPushSubscriptionId';

type PushRegistration = Pick<ServiceWorkerRegistration, 'pushManager'>;

/** 등록된 서비스 워커에 남은 푸시 구독을 지운다. 실패해도 앱 진입을 막지 않는다 */
export async function clearWebPushSubscription(
  registration: PushRegistration | null,
): Promise<void> {
  try {
    const subscription = await registration?.pushManager?.getSubscription();

    await subscription?.unsubscribe();
  } catch {
    // 브라우저가 이미 버린 구독이다
  }

  try {
    window.localStorage.removeItem(LEGACY_SUBSCRIPTION_KEY);
  } catch {
    // 저장소를 못 쓰는 탭이다. 번호만 남을 뿐 쓰는 곳이 없다
  }
}
