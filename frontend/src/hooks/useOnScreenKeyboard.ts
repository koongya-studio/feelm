/*
 * 화면 키보드가 올라와 있는지 (업무 S15P21E106-718).
 *
 * 폰에서 입력 칸을 누르면 OS 키보드가 화면 아래를 덮는데, 그 자리에 무언가를 그려 둔
 * 화면은 둘이 겹쳐 무너진다. 가입 화면의 타자기가 그렇다.
 *
 * **`visualViewport`의 높이가 줄어드는 것으로 안다.** 키보드는 레이아웃 뷰포트
 * (`innerHeight`)를 바꾸지 않고 보이는 영역만 줄인다. 그래서 두 값의 차이가 키보드의
 * 높이다. 화면 종류나 폭으로 가르지 않는다 — 폰에 외장 키보드를 붙이면 키보드가 뜨지
 * 않고, 그때는 타자기를 내릴 이유도 없다. PC도 같은 이유로 아무 일이 없다.
 *
 * `visualViewport`가 없는 환경(구형 브라우저 · jsdom)에서는 늘 `false`다. 그 경우 화면은
 * 지금까지와 똑같이 보인다.
 */
import { useEffect, useState } from 'react';

/**
 * 키보드로 볼 최소 높이(px).
 *
 * `[?]` 주소 표시줄이 접히고 펴질 때도 보이는 영역이 100px 안팎으로 움직인다. 그것을
 * 키보드로 잘못 읽지 않도록 잡은 값이며, 실제 키보드는 훨씬 높다(폰에서 보통 화면의
 * 3분의 1 이상). 기기에서 보고 조정한다.
 */
const KEYBOARD_MIN_HEIGHT = 160;

export type OnScreenKeyboard = {
  open: boolean;
  /** 키보드가 덮은 높이(px). 닫혀 있으면 `0`이다 */
  inset: number;
};

export function useOnScreenKeyboard(): OnScreenKeyboard {
  const [inset, setInset] = useState(0);

  useEffect(() => {
    const viewport = window.visualViewport;

    if (!viewport) return;

    const check = () => {
      const covered = window.innerHeight - viewport.height;

      setInset(covered >= KEYBOARD_MIN_HEIGHT ? covered : 0);
    };

    check();
    viewport.addEventListener('resize', check);

    return () => {
      viewport.removeEventListener('resize', check);
    };
  }, []);

  return { open: inset > 0, inset };
}
