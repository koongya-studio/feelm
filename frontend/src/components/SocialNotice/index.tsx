/*
 * 소셜 버튼이 모두 잠겼을 때의 안내 (업무 S15P21E106-765).
 *
 * 소셜 로그인 · 가입은 빌드에 제공자 client-id가 들어가야 열린다(`utils/oauth.ts`). 없는
 * 제공자는 버튼이 잠기는데, 한때 0.6 로그인에만 `소셜 로그인은 준비 중입니다`가 있고 0.2
 * 가입 화면에는 없어 눌러도 아무 반응이 없는 버튼만 남았다(2026-09-21 배포본 점검).
 *
 * 두 화면이 **같은 기준**으로 판정하도록 여기 한 곳에 둔다 — 제공자가 하나라도 열려 있으면
 * 내린다. 배포에 client-id가 들어가면(업무 -531 · -532) 저절로 사라지고, 걷어낼 때도 이
 * 컴포넌트와 두 자리의 호출만 지우면 된다.
 */
import { isAnySocialConfigured } from '../../utils/oauth';
import styles from './SocialNotice.module.css';

type Props = {
  /** 이 화면에서 소셜 버튼이 하는 일. 0.6은 로그인, 0.2는 가입이다 */
  action: '로그인' | '가입';
};

export default function SocialNotice({ action }: Props) {
  if (isAnySocialConfigured()) return null;

  return <p className={styles.notice}>소셜 {action}은 준비 중입니다.</p>;
}
