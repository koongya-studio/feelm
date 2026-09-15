/*
 * 0.6 로그인의 영화 티켓 틀 (받은 시안, 2026-09-14, S15P21E106-319).
 *
 * 네 모서리가 안으로 파이고, 절취선 높이에서 양옆이 반원으로 파인 종이 한 장이다.
 * 절취선 위(`children`)가 본문, 아래(`stub`)가 바코드가 찍힌 꼬리표다.
 *
 * 파인 모서리를 따라 테두리를 그어야 해서 CSS 테두리로는 그릴 수 없다. 실제 크기를 재서
 * SVG 경로 하나로 종이와 테두리를 함께 그린다. 크기를 재기 전(첫 그림 · jsdom)에는 틀만
 * 빠지고 내용은 그대로 보인다.
 */
import { useLayoutEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import styles from './LoginPage.module.css';

/** 모서리가 파인 반지름 */
const CORNER = 22;
/** 절취선 양옆이 파인 반지름 */
const NOTCH = 18;
/** 테두리 두께의 절반. 선이 상자 밖으로 잘리지 않게 안쪽으로 민다 */
const INSET = 1;

type Size = { width: number; height: number; perforation: number };

function outline({ width, height, perforation }: Size): string {
  const l = INSET;
  const t = INSET;
  const r = width - INSET;
  const b = height - INSET;
  const p = perforation;

  return [
    `M ${l + CORNER} ${t}`,
    `H ${r - CORNER}`,
    `A ${CORNER} ${CORNER} 0 0 0 ${r} ${t + CORNER}`,
    `V ${p - NOTCH}`,
    `A ${NOTCH} ${NOTCH} 0 0 0 ${r} ${p + NOTCH}`,
    `V ${b - CORNER}`,
    `A ${CORNER} ${CORNER} 0 0 0 ${r - CORNER} ${b}`,
    `H ${l + CORNER}`,
    `A ${CORNER} ${CORNER} 0 0 0 ${l} ${b - CORNER}`,
    `V ${p + NOTCH}`,
    `A ${NOTCH} ${NOTCH} 0 0 0 ${l} ${p - NOTCH}`,
    `V ${t + CORNER}`,
    `A ${CORNER} ${CORNER} 0 0 0 ${l + CORNER} ${t}`,
    'Z',
  ].join(' ');
}

type Props = {
  children: ReactNode;
  stub: ReactNode;
};

export default function Ticket({ children, stub }: Props) {
  const ticketRef = useRef<HTMLDivElement>(null);
  const perforationRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<Size | null>(null);

  useLayoutEffect(() => {
    const ticket = ticketRef.current;
    const perforation = perforationRef.current;

    if (!ticket || !perforation) {
      return;
    }

    const measure = () => {
      if (ticket.offsetWidth === 0) {
        return;
      }

      setSize({
        width: ticket.offsetWidth,
        height: ticket.offsetHeight,
        perforation: perforation.offsetTop,
      });
    };

    measure();

    const observer = new ResizeObserver(measure);

    observer.observe(ticket);

    return () => observer.disconnect();
  }, []);

  return (
    <div ref={ticketRef} className={styles.ticket}>
      {size ? (
        <svg
          className={styles.ticketFrame}
          width={size.width}
          height={size.height}
          viewBox={`0 0 ${size.width} ${size.height}`}
          aria-hidden="true"
        >
          <path className={styles.ticketPaper} d={outline(size)} />
          <line
            className={styles.ticketPerforation}
            x1={INSET + NOTCH + 6}
            x2={size.width - INSET - NOTCH - 6}
            y1={size.perforation}
            y2={size.perforation}
          />
        </svg>
      ) : null}

      <div className={styles.ticketBody}>{children}</div>
      <div ref={perforationRef} className={styles.perforation} />
      <div className={styles.ticketStub}>{stub}</div>
    </div>
  );
}

/*
 * 꼬리표의 바코드. 장식이라 값이 없다. 매번 같은 모양이 나오도록 막대 굵기를 고정된 수열로
 * 만든다.
 */
const BARS = [
  3, 1, 2, 1, 4, 1, 1, 2, 3, 1, 2, 2, 1, 3, 1, 1, 4, 2, 1, 1, 2, 3, 1, 2, 1, 1, 3, 1, 2, 4, 1, 2, 1,
  3, 1, 1, 2, 1, 3, 2, 1,
];

const UNIT = 3;

/** 짝수 번째가 막대, 홀수 번째가 빈칸이다. 모든 칸 사이에 한 단위씩 띄운다 */
const BAR_RECTS = BARS.reduce<{ x: number; rects: { x: number; width: number }[] }>(
  (acc, width, index) => ({
    x: acc.x + width * UNIT + UNIT,
    rects: index % 2 === 0 ? [...acc.rects, { x: acc.x, width: width * UNIT }] : acc.rects,
  }),
  { x: 0, rects: [] },
);

export function Barcode() {
  const rects = BAR_RECTS.rects;

  return (
    <svg
      className={styles.barcode}
      viewBox={`0 0 ${BAR_RECTS.x} 60`}
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      {rects.map((rect) => (
        <rect key={rect.x} x={rect.x} y="0" width={rect.width} height="60" />
      ))}
    </svg>
  );
}
