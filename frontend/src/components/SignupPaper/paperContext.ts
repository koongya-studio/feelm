/*
 * 종이 Context 정의와 조회 훅.
 *
 * 컴포넌트는 `index.tsx`에 둔다. 한 파일이 컴포넌트와 그 밖의 값을 함께 export하면
 * Fast Refresh가 동작하지 않는다(react-refresh/only-export-components). `authContext`와
 * 같은 이유로 나누었다.
 */
import { createContext, useContext } from 'react';

export type PaperValue = {
  /** 지금까지 나온 줄 번호 */
  fed: number;
  /** 다음 줄을 밀어 낸다. 이미 나온 줄은 되돌리지 않는다 */
  feed: (line: number) => void;
  /** 글자 하나를 친다. 입력 칸이 아닌 곳(선택 버튼 등)에서 쓴다 */
  type: (data: string | null) => void;
};

export const PaperContext = createContext<PaperValue | null>(null);

export function useSignupPaper(): PaperValue {
  const value = useContext(PaperContext);

  if (!value) {
    throw new Error('useSignupPaper는 SignupPaper 안에서만 사용할 수 있습니다.');
  }

  return value;
}
