import { RouterProvider } from 'react-router';
import SplashScreen from './components/SplashScreen';
import { AuthProvider } from './contexts/AuthProvider';
import { router } from './routes/router';

export default function App() {
  return (
    <>
      <div className="preview-badge" role="status">
        PREVIEW · 목업 데이터
      </div>
      {/*
        인증 세션은 라우터 바깥에서 제공한다. 화면마다 로그인 상태가 달라지면 안 되고,
        새로고침 복구(`AuthProvider`)도 라우트 전환과 무관하게 한 번만 일어나야 한다.
      */}
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
      {/*
        라우터 뒤에 둔다. 스플래시가 걷히는 동안 아래에서 원래 목적지가 이미
        그려지고 있어야 하므로, 라우터를 대체하는 것이 아니라 덮는 것이다.
      */}
      <SplashScreen />
    </>
  );
}
