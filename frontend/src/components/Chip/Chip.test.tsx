/*
 * 취향 키워드 칩 (업무 S15P21E106-919)
 *
 * 두 가지를 고정한다.
 *
 * - **어두운 면에서 색이 바뀌는지.** 기본 색은 밝은 면을 전제로 해서 테두리가 잉크 18%다.
 *   7.1 모바일의 `내 취향`처럼 어두운 판 위에 그대로 놓으면 테두리가 보이지 않는다
 *   (업무 -836에서 그렇게 들어갔다). 색은 CSS에 있으므로 여기서는 표시가 붙는지를 본다
 * - **해시태그가 라벨을 건드리지 않는지.** `#`은 화면 표기일 뿐이고 서버가 준 값
 *   (API-040 `label`)은 그대로여야 한다
 * - **모든 칩이 한 모양인지.** 앞쪽 몇 개를 채우던 `variant`를 걷어냈다. 키워드 수가 사용자마다
 *   2~5개라 채운 칩의 비율이 화면마다 달라졌기 때문이다(업무 -919)
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import Chip from './index';

describe('Chip', () => {
  it('T2 기본은 밝은 면이다', () => {
    render(<Chip>스릴러 편식</Chip>);

    expect(screen.getByText('스릴러 편식')).toHaveAttribute('data-tone', 'light');
  });

  it('T1 어두운 면은 다른 표시를 단다', () => {
    render(<Chip tone="dark">스릴러 편식</Chip>);

    expect(screen.getByText('스릴러 편식')).toHaveAttribute('data-tone', 'dark');
  });

  it('해시태그를 켜면 `#`이 앞에 붙는다', () => {
    // `#`과 라벨이 각각 다른 칸이라 글자로는 따로 잡힌다. 눈에 보이는 줄로 확인한다
    const { container } = render(<Chip hashtag>스릴러 편식</Chip>);

    expect(container.textContent).toBe('#스릴러 편식');
  });

  it('T4 `#`은 화면 표기일 뿐 라벨 값을 바꾸지 않는다', () => {
    const label = '봉준호 몰아보기';
    render(<Chip hashtag>{label}</Chip>);

    // 라벨 문자열 자체는 그대로 남는다. 읽어 주는 기술에도 `#`이 들리지 않는다
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.getByText('#')).toHaveAttribute('aria-hidden', 'true');
  });

  it('해시태그는 기본으로 꺼져 있다', () => {
    render(<Chip>고전 발굴가</Chip>);

    expect(screen.queryByText('#')).toBeNull();
  });

  it('모든 칩이 한 모양이다. 채운 칩을 가르는 값이 없다', () => {
    const { container } = render(
      <>
        <Chip>첫째</Chip>
        <Chip>둘째</Chip>
        <Chip>셋째</Chip>
      </>,
    );

    const chips = container.querySelectorAll('span[data-tone]');
    expect(chips).toHaveLength(3);
    expect([...chips].every((chip) => chip.className === chips[0].className)).toBe(true);
    expect(container.querySelector('[data-variant]')).toBeNull();
  });
});
