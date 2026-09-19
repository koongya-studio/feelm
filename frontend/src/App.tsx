import { useEffect } from 'react';
import { RouterProvider } from 'react-router';
import SplashScreen from './components/SplashScreen';
import { AuthProvider } from './contexts/AuthProvider';
import { OttCatalogProvider } from './contexts/OttCatalogProvider';
import PwaInstallProvider from './pwa/PwaInstallProvider';
import { router } from './routes/router';
import { redirectOnSessionExpiry } from './routes/sessionExpiry';

export default function App() {
  /*
    세션 만료는 아무 API 호출 도중에나 일어나므로 특정 화면이 아니라 여기서 받는다.
    라우터 바깥이라 `useNavigate`를 쓸 수 없어 라우터 인스턴스로 직접 이동한다
    (`routes/sessionExpiry.ts`, 업무 -596).
  */
  useEffect(() => redirectOnSessionExpiry((to, options) => void router.navigate(to, options)), []);

  return (
    <>
      {import.meta.env.DEV ? (
        <div className="preview-badge" role="status">
          LOCAL · API + 로컬 DB
        </div>
      ) : null}
      {/*
        인증 세션은 라우터 바깥에서 제공한다. 화면마다 로그인 상태가 달라지면 안 되고,
        새로고침 복구(`AuthProvider`)도 라우트 전환과 무관하게 한 번만 일어나야 한다.
      */}
      <PwaInstallProvider>
        <AuthProvider>
          {/*
            OTT 기준정보도 라우터 바깥이다. 배지는 어느 화면에나 있고, 화면을 옮길 때마다
            같은 목록을 다시 받을 이유가 없다(`contexts/OttCatalogProvider`). 공개 API라
            로그인 여부와도 무관하다.
          */}
          <OttCatalogProvider>
            <RouterProvider router={router} />
          </OttCatalogProvider>
        </AuthProvider>
      </PwaInstallProvider>
      {/*
        라우터 뒤에 둔다. 스플래시가 걷히는 동안 아래에서 원래 목적지가 이미
        그려지고 있어야 하므로, 라우터를 대체하는 것이 아니라 덮는 것이다.
      */}
      <SplashScreen />
    </>
  );
}
