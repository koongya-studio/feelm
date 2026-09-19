import { createContext, useContext } from 'react';

export type InstallAvailability = 'installed' | 'prompt' | 'ios' | 'unavailable';
export type InstallOutcome = 'accepted' | 'dismissed' | 'unavailable';

export type DeferredInstallPrompt = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
};

export type PwaInstallValue = {
  availability: InstallAvailability;
  install: () => Promise<InstallOutcome>;
};

export const PwaInstallContext = createContext<PwaInstallValue>({
  availability: 'unavailable',
  install: () => Promise.resolve('unavailable'),
});

export function usePwaInstall() {
  return useContext(PwaInstallContext);
}

/** iPadOS는 데스크톱 모드에서 MacIntel로 보이므로 터치 포인트까지 함께 확인한다. */
export function isIosSafari(userAgent: string, platform: string, maxTouchPoints: number): boolean {
  const iosDevice =
    /iPad|iPhone|iPod/i.test(userAgent) || (platform === 'MacIntel' && maxTouchPoints > 1);
  const safari = /Safari/i.test(userAgent) && !/(CriOS|FxiOS|EdgiOS|OPiOS)/i.test(userAgent);

  return iosDevice && safari;
}
