import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { registerServiceWorker } from './pwa/registerServiceWorker';
import { clearWebPushSubscription } from './pwa/webPush';
import './styles/global.css';

const rootElement = document.getElementById('root');

if (!rootElement) {
  throw new Error('#root 요소를 찾을 수 없습니다.');
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

// 실패해도 React 앱 진입은 유지한다. 개발 서버에는 남은 서비스 워커를 만들지 않는다.
// 걷어낸 기기 푸시(업무 -859)가 남긴 구독은 등록을 마친 뒤 지운다.
void registerServiceWorker().then(clearWebPushSubscription);
