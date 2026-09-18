/*
 * OTT 식별 배지 (업무 S15P21E106-441)
 *
 * 배지가 로고와 글자 중 무엇을 그리는지는 화면이 정하지 않는다. 기준정보(API-025)에
 * 로고가 있으면 로고이고, 없거나 불러오지 못하면 글자다. 세 갈래가 모두 같은 자리에서
 * 일어나야 배지가 빈 사각형으로 남지 않는다.
 *
 * 글자로 떨어지는 경우를 특히 고정한다. **왓챠와 웨이브는 코드 첫 글자가 같다.**
 * 로고를 쓸 수 없는 상태에서 둘이 같은 글자가 되면 사용자는 두 OTT를 구분할 수 없다.
 */
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import OttBadge from './index';
import { OttCatalogContext } from '../../contexts/ottCatalogContext';
import type { OttCatalog } from '../../contexts/ottCatalogContext';
import type { Ott } from '../../types/ott';

const NETFLIX: Ott = {
  ottId: 1,
  code: 'NETFLIX',
  name: '넷플릭스',
  logoUrl: 'https://image.tmdb.org/t/p/original/netflix.png',
};

const WATCHA: Ott = {
  ottId: 3,
  code: 'WATCHA',
  name: '왓챠',
  logoUrl: 'https://image.tmdb.org/t/p/original/outdated-watcha.png',
};

const UNKNOWN: Ott = { ottId: 999, code: 'UNKNOWN', name: '알 수 없음', logoUrl: null };

function catalogOf(...otts: Ott[]): OttCatalog {
  return { find: (code) => otts.find((ott) => ott.code === code) ?? null };
}

function renderBadge(ui: React.ReactElement, catalog?: OttCatalog) {
  if (!catalog) {
    // Provider 없이 그리는 경우. 앱 바깥에서도 배지가 터지지 않아야 한다
    return render(ui);
  }

  return render(<OttCatalogContext.Provider value={catalog}>{ui}</OttCatalogContext.Provider>);
}

describe('OTT 식별 배지', () => {
  it('기준정보에 로고가 있으면 로고를 그린다', () => {
    renderBadge(<OttBadge code="NETFLIX" name="넷플릭스" />, catalogOf(NETFLIX));

    const badge = screen.getByRole('img', { name: '넷플릭스' });
    const logo = badge.querySelector('img');

    expect(logo).toHaveAttribute('src', 'https://image.tmdb.org/t/p/w92/netflix.png');
    expect(badge).not.toHaveTextContent('N');
  });

  it('TMDb 이외의 로고 주소는 그대로 사용한다', () => {
    const selfHostedNetflix = {
      ...NETFLIX,
      logoUrl: 'https://cdn.feelm.example/ott/netflix.png',
    };
    renderBadge(<OttBadge code="NETFLIX" name="넷플릭스" />, catalogOf(selfHostedNetflix));

    expect(screen.getByRole('img', { name: '넷플릭스' }).querySelector('img')).toHaveAttribute(
      'src',
      selfHostedNetflix.logoUrl,
    );
  });

  it('왓챠는 TMDb의 잘못된 provider 이미지 대신 공식 아이콘을 사용한다', () => {
    renderBadge(<OttBadge code="WATCHA" name="왓챠" />, catalogOf(WATCHA));

    expect(screen.getByRole('img', { name: '왓챠' }).querySelector('img')).toHaveAttribute(
      'src',
      'https://watcha.com/favicon.ico',
    );
  });

  it('기준정보가 없으면 글자로 그린다', () => {
    renderBadge(<OttBadge code="NETFLIX" name="넷플릭스" />);

    const badge = screen.getByRole('img', { name: '넷플릭스' });

    expect(badge).toHaveTextContent('N');
    expect(badge.querySelector('img')).toBeNull();
  });

  // 기준정보에는 있지만 로고 주소가 비어 있는 OTT도 계약상 가능하다
  it('기준정보에 로고 주소가 없으면 글자로 그린다', () => {
    renderBadge(<OttBadge code="UNKNOWN" name="알 수 없음" />, catalogOf(UNKNOWN));

    expect(screen.getByRole('img', { name: '알 수 없음' })).toHaveTextContent('U');
  });

  it('로고를 불러오지 못하면 같은 자리에서 글자로 떨어진다', () => {
    renderBadge(<OttBadge code="NETFLIX" name="넷플릭스" />, catalogOf(NETFLIX));

    const badge = screen.getByRole('img', { name: '넷플릭스' });
    const logo = badge.querySelector('img');

    expect(logo).not.toBeNull();
    fireEvent.error(logo as HTMLImageElement);

    expect(badge).toHaveTextContent('N');
    expect(badge.querySelector('img')).toBeNull();
  });

  /*
   * 로고를 쓸 수 없는 상태에서도 두 OTT가 구분돼야 한다. 코드 첫 글자를 그대로 쓰면
   * 왓챠도 웨이브도 `W`다(`utils/ott.ts`).
   */
  it('글자로 떨어져도 왓챠와 웨이브가 구분된다', () => {
    renderBadge(
      <>
        <OttBadge code="WATCHA" name="왓챠" />
        <OttBadge code="WAVVE" name="웨이브" />
      </>,
    );

    expect(screen.getByRole('img', { name: '왓챠' })).toHaveTextContent('왓');
    expect(screen.getByRole('img', { name: '웨이브' })).toHaveTextContent('W');
  });

  it('이름이 옆에 있으면 보조기술에 두 번 읽히지 않는다', () => {
    renderBadge(<OttBadge code="NETFLIX" name="넷플릭스" labelled={false} />, catalogOf(NETFLIX));

    expect(screen.queryByRole('img', { name: '넷플릭스' })).not.toBeInTheDocument();
  });
});
