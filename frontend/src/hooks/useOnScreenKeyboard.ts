/*
 * 화면 키보드가 올라와 있는지, 올라왔다면 보이는 영역이 어디서 끝나는지
 * (업무 S15P21E106-718 · -882).
 *
 * 폰에서 입력 칸을 누르면 OS 키보드가 화면 아래를 덮는데, 그 자리에 무언가를 그려 둔
 * 화면은 둘이 겹쳐 무너진다. 가입 화면의 타자기가 그렇다.
 *
 * ─────────────────────────── 기종마다 키보드가 화면을 줄이는 방식이 다르다 (업무 -882)
 *
 * | 환경 | 키보드가 올라오면 | 718의 계산(`innerHeight - 보이는 높이`) |
 * | --- | --- | --- |
 * | 안드로이드 크롬 · 삼성 인터넷 | `interactive-widget=resizes-content`(index.html)라 레이아웃 높이와 보이는 높이가 **함께** 준다 | 차이가 0 — 키보드를 못 알아챘다 |
 * | iOS 사파리 · 홈 화면 앱 | 레이아웃은 그대로, 보이는 영역만 준다. 초점 칸을 보이려 보이는 영역을 아래로 옮기기도 한다(`offsetTop`) | 옮긴 만큼 크게 잡아 종이가 키보드보다 높이 떴다 |
 *
 * 그래서 두 가지를 따로 준다.
 *
 * - **열렸는지(`open`)** — 이 방향(폭)에서 본 가장 큰 보이는 높이를 기준으로 삼고, 거기서
 *   160px 넘게 줄었고 **입력 칸에 초점이 있을 때**만 키보드로 본다. 두 환경 모두 보이는 높이는
 *   줄어든다. 초점 조건은 안드로이드 화면 분할처럼 키보드 없이 창이 줄어드는 경우를 거른다 —
 *   화면 키보드는 입력 칸에 초점이 있을 때만 뜬다
 * - **보이는 영역의 아래 끝(`visibleBottom`)** — 레이아웃 좌표(`getBoundingClientRect`와 같은
 *   기준)로 `offsetTop + height`다. 두 값 모두 CSS px라 화면이 확대돼 있어도 그대로 맞다. 화면은 자기 아래 끝을 이 값에 맞추면 키보드 바로 위에 선다.
 *   iOS의 옮김, 안드로이드의 줄어든 레이아웃, 노치 아래 여백까지 한 번에 맞는다
 *
 * 화면 종류나 폭으로 가르지 않는다 — 폰에 외장 키보드를 붙이면 키보드가 뜨지 않고, 그때는
 * 타자기를 바꿀 이유도 없다. PC도 같은 이유로 아무 일이 없다.
 *
 * `visualViewport`가 없는 환경(구형 브라우저 · jsdom)에서는 늘 닫힘이다. 그 경우 화면은
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
  /** 키보드가 덮은 높이(px). 닫혀 있거나 레이아웃이 이미 줄었으면(안드로이드) `0`이다 */
  inset: number;
  /** 보이는 영역의 아래 끝. 레이아웃 좌표(px)다. 닫혀 있으면 `null`이다 */
  visibleBottom: number | null;
};

const CLOSED: OnScreenKeyboard = { open: false, inset: 0, visibleBottom: null };

/** 화면 키보드를 부르는 칸에 초점이 있는지 */
function editing(): boolean {
  const element = document.activeElement;

  if (!(element instanceof HTMLElement)) return false;
  if (element.isContentEditable || element instanceof HTMLTextAreaElement) return true;
  if (!(element instanceof HTMLInputElement)) return false;

  return ![
    'button',
    'checkbox',
    'color',
    'file',
    'hidden',
    'image',
    'radio',
    'range',
    'reset',
    'submit',
  ].includes(element.type);
}

export function useOnScreenKeyboard(): OnScreenKeyboard {
  const [state, setState] = useState<OnScreenKeyboard>(CLOSED);

  useEffect(() => {
    const viewport = window.visualViewport;

    if (!viewport) return;

    /*
     * 확대를 걷어 낸 보이는 높이. iOS 사파리는 16px보다 작은 입력 칸에 초점이 가면 화면을
     * 저절로 키우는데(가입 칸은 15px — `TextField`), 그러면 `visualViewport`의 폭과 높이가
     * 배율만큼 함께 준다. 배율을 곱해 되돌려야 키보드가 줄인 높이만 남는다(!679 리뷰).
     */
    const visibleHeight = () => viewport.height * (viewport.scale || 1);
    /*
     * 방향은 레이아웃 폭으로 가른다. 보이는 영역의 폭은 확대만으로도 바뀌어, 그것을 기준으로
     * 삼았더니 iOS 자동 확대를 회전으로 읽고 이미 줄어든 높이를 새 기준으로 잡아 키보드를
     * 놓쳤다. 레이아웃 폭은 확대 · 키보드에 흔들리지 않고 돌릴 때만 바뀐다.
     */
    const layoutWidth = () => document.documentElement.clientWidth || window.innerWidth;

    /** 이 방향에서 본 가장 큰 보이는 높이. 돌리면(레이아웃 폭이 바뀌면) 새로 잰다 */
    let baseline = {
      width: layoutWidth(),
      height: Math.max(visibleHeight(), window.innerHeight),
    };
    let deferred = 0;

    const check = () => {
      const width = layoutWidth();

      if (width !== baseline.width) {
        baseline = { width, height: visibleHeight() };
      }

      baseline.height = Math.max(baseline.height, visibleHeight());

      const open = editing() && baseline.height - visibleHeight() >= KEYBOARD_MIN_HEIGHT;

      if (!open) {
        setState((current) => (current.open ? CLOSED : current));
        return;
      }

      const visibleBottom = Math.round((viewport.offsetTop ?? 0) + viewport.height);
      const inset = Math.max(0, Math.round(window.innerHeight - visibleBottom));

      setState((current) =>
        current.open && current.visibleBottom === visibleBottom && current.inset === inset
          ? current
          : { open: true, inset, visibleBottom },
      );
    };

    /*
     * 칸에서 칸으로 옮기면 초점이 잠깐 빈다. 그 틈에 닫힘으로 읽으면 타자기가 한 번 펴졌다
     * 접힌다. 다음 칸이 초점을 받은 뒤에 본다.
     */
    const checkLater = () => {
      window.clearTimeout(deferred);
      deferred = window.setTimeout(check, 0);
    };

    check();
    // 높이는 `resize`, iOS가 보이는 영역을 옮기는 것은 `scroll`로 온다
    viewport.addEventListener('resize', check);
    viewport.addEventListener('scroll', check);
    window.addEventListener('focusin', check);
    window.addEventListener('focusout', checkLater);

    return () => {
      window.clearTimeout(deferred);
      viewport.removeEventListener('resize', check);
      viewport.removeEventListener('scroll', check);
      window.removeEventListener('focusin', check);
      window.removeEventListener('focusout', checkLater);
    };
  }, []);

  return state;
}
