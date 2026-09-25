/*
 * 취향 4컷 — 데이터가 바뀌어도 칸 넷과 이름이 남는지 (업무 S15P21E106-321)
 *
 * 사진 모양(누끼 · 원본 · 없음)은 칸의 `data-mode`로 CSS가 가른다. 사진을 못 불러오면 칸이
 * 사라지지 않고 이름 중심 모양으로 바뀌는지 본다. 글자 크기 맞춤은 jsdom에 크기가 없어 잴 수 없다.
 */
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ReportFourCut from '.';
import type { ReportFourCut as ReportFourCutData } from '../../types/report';

const cuts: ReportFourCutData = {
  genre: {
    name: '로맨스',
    displayName: 'ROMANCE',
    image: { url: '/genre.jpg', cutoutUrl: '/genre-cutout.png' },
  },
  director: {
    name: '웨스 앤더슨',
    displayName: 'Wes Anderson',
    image: { url: '/director.jpg' },
    work: { url: '/work.jpg' },
  },
  actor: { name: '엠마 스톤', displayName: 'Emma Stone', image: { url: null } },
  // 04 칸은 계약이 사진을 주지 않는다. 그림은 `characterCode`로 저장소에서 찾는다(업무 -618)
  character: { name: '안락의자 탐정' },
};

function cut(kind: string) {
  const element = document.querySelector(`[data-cut="${kind}"]`);
  if (!(element instanceof HTMLElement)) throw new Error(`${kind} 칸이 없다`);
  return element;
}

describe('ReportFourCut', () => {
  it('머리에 닉네임과 인화한 달, 발에 날짜를 찍는다', () => {
    render(<ReportFourCut cuts={cuts} owner="김난다" createdAt="2026-09-01T12:00:00Z" />);

    expect(screen.getByRole('figure', { name: '김난다의 취향 4컷' })).toBeInTheDocument();
    expect(screen.getByText('SEPTEMBER 2026')).toBeInTheDocument();
    expect(screen.getByText('2026.09')).toBeInTheDocument();
  });

  it('닉네임이 없으면 나의 취향 4컷이다', () => {
    render(<ReportFourCut cuts={cuts} createdAt="2026-09-01T12:00:00Z" />);

    expect(screen.getByRole('figure', { name: '나의 취향 4컷' })).toBeInTheDocument();
  });

  it('칸마다 번호 라벨과 한글 이름을 쓰고, 영문 이름이 없으면 한글 이름을 한 번만 쓴다', () => {
    render(<ReportFourCut cuts={cuts} createdAt="2026-09-01T12:00:00Z" />);

    expect(screen.getAllByRole('listitem')).toHaveLength(4);
    ['01 장르', '02 감독', '03 배우', '04 캐릭터'].forEach((label) =>
      expect(screen.getByText(label)).toBeInTheDocument(),
    );
    expect(screen.getByText('로맨스')).toBeInTheDocument();
    expect(screen.getByText('ROMANCE').closest('[aria-hidden]')).not.toBeNull();
    // 배우 이름은 마지막 단어만 따로 그린다
    expect(screen.getByText('Emma')).toBeInTheDocument();
    expect(screen.getByText('Stone')).toBeInTheDocument();
    expect(screen.getAllByText('안락의자 탐정')).toHaveLength(1);
  });

  it('누끼가 있으면 누끼, 없으면 원본, 둘 다 없으면 이름만 그린다', () => {
    render(<ReportFourCut cuts={cuts} createdAt="2026-09-01T12:00:00Z" />);

    expect(cut('genre')).toHaveAttribute('data-mode', 'cutout');
    expect(cut('director')).toHaveAttribute('data-mode', 'photo');
    // 계약이 사진을 주지 않는 칸은 다른 사진으로 채우지 않는다
    expect(cut('actor')).toHaveAttribute('data-mode', 'none');
    expect(cut('actor').querySelector('img')).toBeNull();
  });

  it('계약이 카드를 주지 않은 칸은 번호 라벨과 준비 중만 남는다', () => {
    // 04 캐릭터는 백엔드 구현(업무 -779) 전까지 늘 null이다
    render(<ReportFourCut cuts={{ ...cuts, character: null }} createdAt="2026-09-01T12:00:00Z" />);

    expect(screen.getAllByRole('listitem')).toHaveLength(4);
    expect(screen.getByText('04 캐릭터')).toBeInTheDocument();
    expect(screen.getByText('준비 중')).toBeInTheDocument();
    expect(screen.queryByText('안락의자 탐정')).toBeNull();
    expect(cut('character').querySelector('img')).toBeNull();
  });

  /*
   * 뒤집기(업무 -812). 뒷면을 준 칸만 뒤집히고, 뒷면 내용과 서식은 화면이 가진다.
   * 3.3 인화기 · 7.3 목록 · 7.1 대시보드는 뒷면을 주지 않으므로 그대로 그림 칸이다.
   */
  it('뒷면을 준 칸만 뒤집는 버튼이 생긴다', () => {
    const onFlip = vi.fn();
    render(
      <ReportFourCut
        cuts={cuts}
        createdAt="2026-09-01T12:00:00Z"
        backs={{ genre: <p>장르 설명</p> }}
        onFlip={onFlip}
      />,
    );

    expect(screen.queryByRole('button', { name: '02 감독 설명 보기' })).toBeNull();

    const flip = screen.getByRole('button', { name: '01 장르 설명 보기' });
    // 숨긴 글자에 기대지 않고 버튼 자체에 이름을 둔다 (업무 -877)
    expect(flip).toHaveAttribute('aria-label', '01 장르 설명 보기');
    fireEvent.click(flip);
    expect(onFlip).toHaveBeenCalledWith('genre');
  });

  it('뒤집힌 칸은 버튼 이름과 aria-expanded가 바뀐다', () => {
    render(
      <ReportFourCut
        cuts={cuts}
        createdAt="2026-09-01T12:00:00Z"
        backs={{ genre: <p>장르 설명</p> }}
        flipped={['genre']}
      />,
    );

    expect(screen.getByRole('button', { name: '01 장르 사진 보기' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('뒷면은 그림이 없는 준비 중 칸에도 붙는다', () => {
    render(
      <ReportFourCut
        cuts={{ ...cuts, character: null }}
        createdAt="2026-09-01T12:00:00Z"
        backs={{ character: <p>지난 반기와 비교</p> }}
      />,
    );

    expect(screen.getByText('준비 중')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '04 캐릭터 설명 보기' })).toBeInTheDocument();
  });

  it('뒷면을 주지 않으면 누를 곳이 없다', () => {
    render(<ReportFourCut cuts={cuts} createdAt="2026-09-01T12:00:00Z" />);

    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('사진을 못 불러오면 다음 사진으로, 끝내 없으면 칸을 남기고 이름만 그린다', () => {
    render(<ReportFourCut cuts={cuts} createdAt="2026-09-01T12:00:00Z" />);

    fireEvent.error(cut('genre').querySelector('img')!);
    expect(cut('genre')).toHaveAttribute('data-mode', 'photo');
    expect(cut('genre').querySelector('img')).toHaveAttribute('src', '/genre.jpg');

    fireEvent.error(cut('genre').querySelector('img')!);
    expect(cut('genre')).toHaveAttribute('data-mode', 'none');
    expect(screen.getByText('ROMANCE')).toBeInTheDocument();

    // 감독 대표작은 장식이라 못 불러오면 빠지기만 한다
    const work = [...cut('director').querySelectorAll('img')].find(
      (img) => img.getAttribute('src') === '/work.jpg',
    );
    fireEvent.error(work!);
    expect(cut('director').querySelectorAll('img')).toHaveLength(1);
    expect(cut('director')).toHaveAttribute('data-mode', 'photo');
  });
});
