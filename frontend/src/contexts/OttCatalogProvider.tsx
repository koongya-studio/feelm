/*
 * OTT 기준정보 Provider (업무 S15P21E106-441).
 *
 * 배지에 붙는 로고는 API-025에만 있다. 계약이 **다른 응답에는 로고를 싣지 않기**
 * 때문이다 — 영화 상세의 `ottProviders`는 `ottId` · `code` · `name` · `watchUrl` ·
 * `subscribed`이고, 프레임 상세의 `ott`는 `code` · `name`이며, 검색은 `logoUrl`을
 * 포함하지 않는다고 명시한다(`docs/api/movies-search.md`). 그래서 로고가 필요한 화면은
 * 어느 것이든 기준정보와 맞춰 봐야 한다.
 *
 * 그 맞춤을 화면마다 하면 같은 조회가 화면 수만큼 늘고, 새 화면은 그 일을 잊는다.
 * 여기서 한 번 받아 배지가 직접 찾아 쓴다.
 *
 * **실패해도 앱 진입을 막지 않는다.** 로고를 못 얻으면 배지가 글자로 떨어질 뿐이라
 * 기준정보 조회는 화면이 기다릴 일이 아니다. 인증 세션 복구(`AuthProvider`)가
 * `restoring`을 두는 것과 다른 점이다 — 그쪽은 로그인 여부를 모르는 채로 화면을 그리면
 * 안 되지만, 이쪽은 모르면 모르는 대로 그릴 수 있다.
 *
 * **파일 이름이 `OttCatalogContext.tsx`이면 Windows에서 앱이 뜨지 않는다.** 같은 폴더의
 * `ottCatalogContext.ts`와 대소문자만 다르면 확장자 없는 import가 상태 파일로 잘못
 * 이어진다(S15P21E106-285). 그래서 이 파일은 `OttCatalogProvider.tsx`다.
 */
import { useEffect, useMemo, useState } from 'react';
import { getOtts } from '../api/otts';
import { OttCatalogContext } from './ottCatalogContext';
import type { ReactNode } from 'react';
import type { Ott } from '../types/ott';

export function OttCatalogProvider({ children }: { children: ReactNode }) {
  const [otts, setOtts] = useState<Ott[]>([]);

  useEffect(() => {
    let active = true;

    getOtts()
      .then((items) => {
        if (active) {
          setOtts(items);
        }
      })
      .catch(() => {
        // 로고 없이 글자 배지로 그린다. 여기서 알릴 것이 없다
      });

    return () => {
      active = false;
    };
  }, []);

  const value = useMemo(() => {
    const byCode = new Map(otts.map((ott) => [ott.code, ott]));

    return { find: (code: string) => byCode.get(code) ?? null };
  }, [otts]);

  return <OttCatalogContext.Provider value={value}>{children}</OttCatalogContext.Provider>;
}
