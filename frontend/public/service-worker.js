/*
 * Feelm 서비스 워커 공통 기반 (S15P21E106-643).
 *
 * 이 파일은 의도적으로 fetch를 가로채거나 Cache Storage를 만들지 않는다. 인증·/api 응답과
 * 사용자별 영화 취향 데이터는 항상 네트워크와 서버를 정본으로 사용한다. 이후
 * S15P21E106-583은 이 파일에 push·notificationclick 처리만 추가하고 캐시 정책은 바꾸지 않는다.
 */
self.addEventListener('install', () => {
  // 캐시가 없으므로 구 버전과 병행할 이유가 없다.
  void self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});
