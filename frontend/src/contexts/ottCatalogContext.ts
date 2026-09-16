/*
 * OTT 기준정보 Context 정의와 조회 훅.
 *
 * Provider는 `OttCatalogProvider.tsx`에 둔다. 한 파일이 컴포넌트와 그 밖의 값을 함께
 * export하면 Fast Refresh가 동작하지 않아(react-refresh/only-export-components)
 * 컴포넌트가 아닌 것만 이 파일에 모았다(`authContext.ts`와 같은 방식).
 *
 * **Provider 없이도 동작해야 한다.** 배지(`components/OttBadge`)가 이 값을 읽는데,
 * 배지는 앱 바깥(단위 테스트)에서도 그려진다. 그때는 기준정보가 없는 것으로 보고
 * 글자 배지로 떨어진다.
 */
import { createContext, useContext } from 'react';
import type { Ott } from '../types/ott';

/** OTT 코드로 찾는 기준정보. 아직 받지 못했거나 받지 못한 경우 비어 있다 */
export type OttCatalog = {
  find: (code: string) => Ott | null;
};

const EMPTY_CATALOG: OttCatalog = { find: () => null };

export const OttCatalogContext = createContext<OttCatalog>(EMPTY_CATALOG);

export function useOttCatalog(): OttCatalog {
  return useContext(OttCatalogContext);
}
