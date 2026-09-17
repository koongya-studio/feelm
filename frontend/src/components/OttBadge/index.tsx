/*
 * OTT 식별 배지.
 *
 * 와이어프레임은 OTT를 서비스명 첫 글자 한 자로 표시했다. **확정 5종에 왓챠와 웨이브가
 * 함께 들어오면서 첫 글자가 겹쳤고**(둘 다 `W`), 첫 글자 규칙만으로는 두 OTT를 구분할
 * 수 없게 됐다. API-025가 로고 URL을 내려주므로 로고를 쓴다
 * (팀 결정 2026-09-15, 업무 S15P21E106-379).
 *
 * **배지는 `code`만 받고 표시는 스스로 정한다**(업무 S15P21E106-441). 로고가 있는 곳은
 * API-025 하나뿐인데, 화면이 보는 응답에는 로고가 없다 — 영화 상세도 프레임 상세도
 * 검색도 코드와 이름까지만 준다. 그래서 각 화면이 기준정보와 맞춰 로고를 넘기는 방식은
 * 화면마다 같은 조회를 반복하게 하고, 한 화면이라도 빠뜨리면 같은 OTT가 어디서는 로고로
 * 어디서는 글자로 보인다. 기준정보는 앱에서 한 번 받고(`contexts/OttCatalogProvider`)
 * 배지가 코드로 찾는다.
 *
 * 로고로 떨어지지 않는 경우가 셋이다. 기준정보를 아직 못 받았거나(앱이 막 떴을 때),
 * 조회에 실패했거나, 이미지를 불러오지 못했을 때다. 셋 다 같은 자리에서 글자 배지가
 * 된다 — 배지가 빈 사각형으로 남지 않게 한다.
 *
 * 크기가 화면마다 다르므로 size로 받는다. 와이어프레임 실측값이다.
 * 1.2 초기 설정 38px · 2.3 카드 메뉴 28px · 2.4 영화 상세 26px
 * 2.1 · 2.2 · 3.1-A 22px · 2.1 목록 20px
 *
 * 글자 크기는 기본적으로 한 변의 절반이다. 1.2만 38px 상자에 16px 글자여서 비율이
 * 다르므로 fontSize로 받는다. 모서리 반경도 1.2는 10px, 나머지는 8px이다.
 *
 * 글자 배지의 색은 서비스 브랜드 색이 아니다. 와이어프레임은 무채색이며 세 가지를 쓴다.
 * default    : 배경 #A5A29B, 어두운 글자 — 2.1 · 2.2 · 2.3 카드
 * unselected : 배경 #A5A29B, 흰 글자   — 1.2에서 선택하지 않은 OTT
 * selected   : 배경 #3C3A36, 흰 글자   — 1.2에서 구독 중으로 선택된 OTT
 * 로고는 브랜드 이미지 그대로라 이 세 가지를 따르지 않는다. 대신 선택 상태는 배지
 * 테두리로 남는다.
 */
import { useState } from 'react';
import { useOttCatalog } from '../../contexts/ottCatalogContext';
import { ottBadgeLetter } from '../../utils/ott';
import type { CSSProperties } from 'react';
import styles from './OttBadge.module.css';

type Props = {
  /** OTT 코드. 예: `NETFLIX`. 로고와 글자를 모두 이 값으로 정한다 */
  code: string;
  /** OTT 이름. 보조기술에 읽히는 이름이 된다 */
  name: string;
  /** 한 변의 길이(px) */
  size?: number;
  /** 글자 크기(px). 생략하면 한 변의 절반 */
  fontSize?: number;
  /** 모서리 반경(px) */
  radius?: number;
  variant?: 'default' | 'unselected' | 'selected';
  /**
   * 보조기술에 이름을 읽힐지. 이미 같은 이름이 옆에 텍스트로 있으면 false로 두어
   * 두 번 읽히지 않게 한다.
   */
  labelled?: boolean;
};

const TMDB_ORIGINAL_IMAGE_PREFIX = 'https://image.tmdb.org/t/p/original/';
const TMDB_OTT_LOGO_IMAGE_PREFIX = 'https://image.tmdb.org/t/p/w92/';
const OFFICIAL_LOGO_URL_BY_CODE: Readonly<Record<string, string>> = {
  // TMDb의 Watcha provider 로고가 현재 공식 브랜드 아이콘과 다른 이미지로 노출된다.
  WATCHA: 'https://watcha.com/favicon.ico',
};

/**
 * API-025의 저장 형식은 유지하되, 작은 OTT 배지에서 TMDb 원본 이미지를 내려받지 않는다.
 * 다른 호스트의 로고 URL은 백엔드가 준 값을 그대로 사용한다.
 */
function ottLogoImageUrl(logoUrl: string): string {
  if (!logoUrl.startsWith(TMDB_ORIGINAL_IMAGE_PREFIX)) return logoUrl;

  return `${TMDB_OTT_LOGO_IMAGE_PREFIX}${logoUrl.slice(TMDB_ORIGINAL_IMAGE_PREFIX.length)}`;
}

export default function OttBadge({
  code,
  name,
  size = 22,
  fontSize,
  radius,
  variant = 'default',
  labelled = true,
}: Props) {
  const catalog = useOttCatalog();
  /*
   * 불러오지 못한 로고 주소. 참·거짓이 아니라 **주소**를 담는다 — 같은 배지가 다른
   * OTT로 다시 쓰일 수 있고, 그때 앞의 실패를 물려받으면 멀쩡한 로고가 글자로 떨어진다.
   */
  const [failedLogoUrl, setFailedLogoUrl] = useState<string | null>(null);

  const catalogOtt = catalog.find(code);
  const catalogLogoUrl =
    catalogOtt === null ? null : (OFFICIAL_LOGO_URL_BY_CODE[code] ?? catalogOtt.logoUrl);
  const logoUrl = catalogLogoUrl === null ? null : ottLogoImageUrl(catalogLogoUrl);

  const style = {
    '--ott-badge-size': `${size}px`,
    '--ott-badge-font-size': `${fontSize ?? Math.round(size * 0.5 * 10) / 10}px`,
    '--ott-badge-radius': radius === undefined ? 'var(--radius-icon)' : `${radius}px`,
  } as CSSProperties;

  const showLogo = logoUrl !== null && failedLogoUrl !== logoUrl;
  const content = showLogo ? (
    <img
      className={styles.logo}
      src={logoUrl}
      alt=""
      // 배지 자체가 이름을 읽히므로 이미지는 장식이다
      aria-hidden="true"
      onError={() => setFailedLogoUrl(logoUrl)}
    />
  ) : (
    ottBadgeLetter(code)
  );

  const attributes = {
    className: styles.badge,
    'data-variant': variant,
    'data-logo': showLogo ? 'true' : undefined,
    style,
  };

  if (!labelled) {
    return (
      <span {...attributes} aria-hidden="true">
        {content}
      </span>
    );
  }

  return (
    <span {...attributes} role="img" aria-label={name}>
      {content}
    </span>
  );
}
