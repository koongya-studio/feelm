/*
 * 4컷의 큰 이름을 정해진 글자 상자 안에 맞춘다.
 *
 * 이름 길이는 데이터마다 다르다(`SF` · `ROMANCE` · `SCIENCE FICTION` · `봉준호`). 칸과 글자 상자는
 * 고정하고 **글자 크기만** 줄여 맞춘다.
 *
 * - 글자 크기는 CSS가 `calc(기준 크기 × var(--fit))`로 쓴다. 여기서는 `--fit`(0.3 ~ 1)만 고른다
 * - 맞는지 보는 기준은 셋이다. 가로로 넘치지 않는다 · 상자 높이를 넘지 않는다 · `maxLines`줄을
 *   넘지 않는다. 한 단어가 길면 줄을 바꾸지 않고 작아진다
 * - 가장 작게 줄여도 넘치면 `data-overflow`를 붙인다. CSS가 그때만 단어 중간에서 줄을 바꾼다
 * - `stretch`면 맞춘 뒤 남는 높이만큼 세로로 늘린다(`--stretch`, 최대 `stretch`배). 장르 칸의
 *   길쭉한 글자다
 *
 * 4컷의 모든 길이가 폭에 비례하므로 폭이 바뀌어도 `--fit`은 거의 그대로다. 그래도 반올림과
 * 서체 로딩 때문에 크기가 바뀌거나 서체가 들어오면 다시 잰다. jsdom처럼 크기가 0이면 재지 않는다.
 */
import { useLayoutEffect, useRef } from 'react';

const MIN_FIT = 0.3;
const STEPS = 8;

type Options = {
  maxLines: number;
  /** 세로로 늘릴 수 있는 최대 배율. 없으면 늘리지 않는다 */
  stretch?: number;
};

function fits(box: HTMLElement, text: HTMLElement, maxLines: number): boolean {
  const lineHeight = parseFloat(getComputedStyle(text).lineHeight);
  const lines = Number.isFinite(lineHeight) ? text.offsetHeight / lineHeight : 1;

  return (
    text.scrollWidth <= box.clientWidth + 1 &&
    text.offsetHeight <= box.clientHeight + 1 &&
    lines <= maxLines + 0.1
  );
}

export function useFitText<T extends HTMLElement>(content: string, { maxLines, stretch }: Options) {
  const boxRef = useRef<T>(null);

  useLayoutEffect(() => {
    const box = boxRef.current;
    const text = box?.firstElementChild;

    if (!box || !(text instanceof HTMLElement)) return;

    let active = true;

    function fit() {
      if (!active || !box || !(text instanceof HTMLElement) || box.clientWidth === 0) return;

      box.style.setProperty('--stretch', '1');
      box.style.setProperty('--fit', '1');
      delete box.dataset.overflow;

      if (!fits(box, text, maxLines)) {
        let low = MIN_FIT;
        let high = 1;

        for (let step = 0; step < STEPS; step += 1) {
          const middle = (low + high) / 2;
          box.style.setProperty('--fit', String(middle));

          if (fits(box, text, maxLines)) low = middle;
          else high = middle;
        }

        box.style.setProperty('--fit', String(low));

        if (!fits(box, text, maxLines)) box.dataset.overflow = 'true';
      }

      if (stretch && text.offsetHeight > 0) {
        const room = box.clientHeight / text.offsetHeight;
        box.style.setProperty('--stretch', String(Math.min(Math.max(room, 1), stretch)));
      }
    }

    fit();

    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(fit);
    observer?.observe(box);
    void document.fonts?.ready.then(fit);

    return () => {
      active = false;
      observer?.disconnect();
    };
  }, [content, maxLines, stretch]);

  return boxRef;
}
