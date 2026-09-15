/*
 * 소셜 로그인·가입 버튼.
 *
 * 와이어프레임 0.2 · 0.3 · 0.6 세 화면이 같은 모양을 쓴다(`common-components.md` 3.1).
 *
 * 와이어프레임은 제공자를 실제 로고가 아니라 무채색 상자에 첫 글자 한 자로 그린다.
 * `OttBadge`와 같은 표현이지만 OttBadge는 OTT를 아는 도메인 컴포넌트라 재사용하지
 * 않고, 같은 규격의 표식을 이 컴포넌트 안에 둔다.
 *
 * `appearance="brand"`는 각 사의 실제 로고를 그린다. 0.6 로그인이 받은 시안(영화 티켓,
 * 2026-09-14, S15P21E106-319)에서 쓴다. 기본값은 와이어프레임의 무채색 글자 표식이라
 * 0.2 · 0.3은 그대로다.
 *
 * `[?]` 각 사의 브랜드 가이드가 로고 크기 · 여백 · 버튼 색을 강제하는 경우가 있어 실제
 * 연동 전에 디자인·법무 확인이 필요하다.
 */
import type { ButtonHTMLAttributes } from 'react';
import styles from './SocialButton.module.css';

export type SocialProvider = 'kakao' | 'naver' | 'google';

/** 와이어프레임 0.2 · 0.6이 쓰는 표식 글자 */
const MARK: Record<SocialProvider, string> = {
  kakao: 'K',
  naver: 'N',
  google: 'G',
};

type Props = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> & {
  provider: SocialProvider;
  /** 버튼에 보이는 문구. 화면마다 다르다 (`카카오로 시작하기` · `카카오 로그인`) */
  label: string;
  /** 표식 모양. `mark`는 무채색 글자, `brand`는 각 사 로고 */
  appearance?: 'mark' | 'brand';
};

/** 각 사 로고. 장식이라 보조기술에는 숨기고 문구가 제공자를 말한다 */
function BrandLogo({ provider }: { provider: SocialProvider }) {
  if (provider === 'google') {
    return (
      <svg viewBox="0 0 48 48" width="22" height="22">
        <path
          fill="#EA4335"
          d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
        />
        <path
          fill="#4285F4"
          d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
        />
        <path
          fill="#FBBC05"
          d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
        />
        <path
          fill="#34A853"
          d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
        />
      </svg>
    );
  }

  if (provider === 'naver') {
    return (
      <svg viewBox="0 0 20 20" width="18" height="18">
        <path fill="#03C75A" d="M13.56 10.7 6.17 0H0v20h6.44V9.3L13.83 20H20V0h-6.44z" />
      </svg>
    );
  }

  return (
    <svg viewBox="0 0 24 24" width="24" height="24">
      <rect width="24" height="24" rx="6" fill="#FEE500" />
      <path
        fill="#191919"
        d="M12 6.2c-3.7 0-6.7 2.3-6.7 5.2 0 1.9 1.3 3.5 3.2 4.4l-.8 2.9c-.1.3.2.5.4.3l3.4-2.3h.5c3.7 0 6.7-2.3 6.7-5.2S15.7 6.2 12 6.2z"
      />
    </svg>
  );
}

export default function SocialButton({ provider, label, appearance = 'mark', ...rest }: Props) {
  return (
    <button type="button" className={styles.button} {...rest}>
      {appearance === 'brand' ? (
        <span className={styles.logo} aria-hidden="true">
          <BrandLogo provider={provider} />
        </span>
      ) : (
        <span className={styles.mark} aria-hidden="true">
          {MARK[provider]}
        </span>
      )}
      <span className={styles.label}>{label}</span>
    </button>
  );
}
