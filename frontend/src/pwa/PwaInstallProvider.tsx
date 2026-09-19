import { useCallback, useEffect, useMemo, useState } from 'react';
import { isIosSafari, PwaInstallContext } from './installContext';
import type { DeferredInstallPrompt, InstallAvailability, InstallOutcome } from './installContext';
import type { ReactNode } from 'react';

type Props = { children: ReactNode };

function isStandalone() {
  const displayModeStandalone = window.matchMedia?.('(display-mode: standalone)').matches ?? false;
  const navigatorStandalone = Boolean(
    (navigator as Navigator & { standalone?: boolean }).standalone,
  );

  return displayModeStandalone || navigatorStandalone;
}

export default function PwaInstallProvider({ children }: Props) {
  const [installed, setInstalled] = useState(isStandalone);
  const [deferredPrompt, setDeferredPrompt] = useState<DeferredInstallPrompt | null>(null);

  useEffect(() => {
    const displayMode = window.matchMedia?.('(display-mode: standalone)');

    function handleBeforeInstallPrompt(event: Event) {
      // 브라우저 미니 인포바 대신 설정 화면의 명시적인 사용자 동작에서 설치를 시작한다.
      event.preventDefault();
      setDeferredPrompt(event as DeferredInstallPrompt);
    }

    function handleInstalled() {
      setDeferredPrompt(null);
      setInstalled(true);
    }

    function handleDisplayModeChange() {
      setInstalled(isStandalone());
    }

    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt);
    window.addEventListener('appinstalled', handleInstalled);
    displayMode?.addEventListener?.('change', handleDisplayModeChange);

    return () => {
      window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt);
      window.removeEventListener('appinstalled', handleInstalled);
      displayMode?.removeEventListener?.('change', handleDisplayModeChange);
    };
  }, []);

  const availability: InstallAvailability = installed
    ? 'installed'
    : deferredPrompt
      ? 'prompt'
      : isIosSafari(navigator.userAgent, navigator.platform, navigator.maxTouchPoints)
        ? 'ios'
        : 'unavailable';

  const install = useCallback(async (): Promise<InstallOutcome> => {
    if (!deferredPrompt) return 'unavailable';

    try {
      await deferredPrompt.prompt();
      const choice = await deferredPrompt.userChoice;

      // 같은 BeforeInstallPromptEvent는 두 번 사용할 수 없다.
      setDeferredPrompt(null);
      if (choice.outcome === 'accepted') setInstalled(true);

      return choice.outcome;
    } catch {
      // 브라우저가 이벤트를 회수했거나 설치 UI를 열 수 없어도 앱 사용은 계속한다.
      setDeferredPrompt(null);
      return 'unavailable';
    }
  }, [deferredPrompt]);

  const value = useMemo(() => ({ availability, install }), [availability, install]);

  return <PwaInstallContext.Provider value={value}>{children}</PwaInstallContext.Provider>;
}
