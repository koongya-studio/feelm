/*
 * Feelm 서비스 워커 공통 기반 (S15P21E106-643).
 *
 * 이 파일은 의도적으로 fetch를 가로채거나 Cache Storage를 만들지 않는다. 인증·/api 응답과
 * 사용자별 영화 취향 데이터는 항상 네트워크와 서버를 정본으로 사용한다. 이후
 * S15P21E106-583이 이 파일에 push·notificationclick 처리를 더했다(아래). 캐시 정책은 그대로다.
 */
self.addEventListener('install', () => {
  // 캐시가 없으므로 구 버전과 병행할 이유가 없다.
  void self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

/*
 * ── Web Push (업무 S15P21E106-583) ──────────────────────────────────────────
 *
 * payload는 공통 알림 항목의 `notificationId` · `type` · `title` · `body` · `targetType` ·
 * `targetId`다(`docs/api/notifications.md` Web Push 발송 규칙). 구독은 앱(`src/pwa/webPush.ts`)이
 * 만들고, 여기서는 받은 푸시를 띄우고 누르면 해당 화면을 연다.
 */

/**
 * 읽을 수 없는 푸시에 띄우는 알림. 누르면 알림 목록이 열린다.
 *
 * **받은 푸시는 빠짐없이 알림으로 보인다.** 구독이 `userVisibleOnly`라, 알림 없이 넘긴 푸시가
 * 쌓이면 크롬은 기본 알림을 대신 띄우고 사파리는 구독을 끊는다. 계약상 서버는 파티 초대 · 초대
 * 수락만 보내지만, 계약과 다른 푸시가 와도 구독을 잃지 않도록 걸러 내지 않는다.
 */
const FALLBACK = { title: '새 알림이 있어요', body: 'Feelm에서 확인해 주세요' };

/**
 * 알림이 여는 앱 경로. **`src/pages/notifications/destination.ts`와 같은 규칙이다** — 서비스
 * 워커는 앱 코드를 import할 수 없어 옮겨 적었다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
 */
function notificationPath(targetType, targetId) {
  switch (targetType) {
    case 'WATCH_RECORD':
      return `watch-records/${targetId}/confirm`;
    case 'PARTY_INVITATION':
      return `parties/invitations/${targetId}`;
    case 'PARTY':
      return `parties/${targetId}/popcorn`;
    default:
      return 'notifications';
  }
}

function readPayload(event) {
  try {
    return event.data ? event.data.json() : null;
  } catch {
    return null;
  }
}

self.addEventListener('push', (event) => {
  const payload = readPayload(event);
  const readable = Boolean(payload && typeof payload.title === 'string' && payload.title);
  const title = readable ? payload.title : FALLBACK.title;
  const body = readable && typeof payload.body === 'string' ? payload.body : FALLBACK.body;
  const path = readable ? notificationPath(payload.targetType, payload.targetId) : 'notifications';

  // 같은 알림이 두 번 올 수 있다(발송 성공 직후 서버가 멈추면 재발송). 알림 번호를 태그로 써서
  // 같은 알림은 한 장으로 겹친다. 이미 떠 있으면 소리 · 진동 없이 제자리에서 바꾼다 — 걸러 내면
  // 알림 없는 푸시가 된다(위 FALLBACK).
  const tag =
    readable && payload.notificationId != null
      ? `feelm-notification-${payload.notificationId}`
      : 'feelm-notification';

  event.waitUntil(
    self.registration.getNotifications({ tag }).then((shown) =>
      self.registration.showNotification(title, {
        body,
        tag,
        silent: shown.length > 0,
        icon: new URL('icon-192.png', self.registration.scope).href,
        badge: new URL('icon-192.png', self.registration.scope).href,
        data: { url: new URL(path, self.registration.scope).href },
      }),
    ),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const url = event.notification.data && event.notification.data.url;

  if (!url) return;

  // 앱이 열려 있으면 그 창을 그 화면으로 옮기고 앞으로 가져온다. 없으면 새 창을 연다
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const app = windows.find((client) => client.url.startsWith(self.registration.scope));

      if (app) {
        // 이 워커가 아직 제어하지 않는 창은 `navigate`가 거절된다. 그때는 새 창으로 연다
        return app
          .navigate(url)
          .then((client) => (client || app).focus())
          .catch(() => self.clients.openWindow(url));
      }

      return self.clients.openWindow(url);
    }),
  );
});
