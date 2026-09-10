import { RouterProvider } from 'react-router';
import SplashScreen from './components/SplashScreen';
import { router } from './routes/router';

export default function App() {
  return (
    <>
      <div className="preview-badge" role="status">
        PREVIEW · 목업 데이터
      </div>
      <RouterProvider router={router} />
      {/*
        라우터 뒤에 둔다. 스플래시가 걷히는 동안 아래에서 원래 목적지가 이미
        그려지고 있어야 하므로, 라우터를 대체하는 것이 아니라 덮는 것이다.
      */}
      <SplashScreen />
    </>
  );
}
