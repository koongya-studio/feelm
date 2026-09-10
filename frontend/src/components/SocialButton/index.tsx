/*
 * 소셜 로그인·가입 버튼.
 *
 * 와이어프레임 0.2 · 0.3 · 0.6 세 화면이 같은 모양을 쓴다(`common-components.md` 3.1).
 *
 * 와이어프레임은 제공자를 실제 로고가 아니라 무채색 상자에 첫 글자 한 자로 그린다.
 * `OttBadge`와 같은 표현이지만 OttBadge는 OTT를 아는 도메인 컴포넌트라 재사용하지
 * 않고, 같은 규격의 표식을 이 컴포넌트 안에 둔다.
 *
 * `[?]` 실제 제공자 로고 사용 여부는 미확정이다. 각 사의 브랜드 가이드가 로고와 색을
 * 강제하는 경우가 있어 디자인·법무 확인이 필요하다. 와이어프레임대로 두었다.
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
};

export default function SocialButton({ provider, label, ...rest }: Props) {
  return (
    <button type="button" className={styles.button} {...rest}>
      <span className={styles.mark} aria-hidden="true">
        {MARK[provider]}
      </span>
      <span className={styles.label}>{label}</span>
    </button>
  );
}
