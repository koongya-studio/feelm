/*
 * 가입·로그인 화면의 껍데기. 하단 내비게이션이 없다 (REF-01 미적용 화면).
 * 적용 화면은 0.1 ~ 0.7-A이다.
 *
 * `OnboardingLayout`과 달리 화면 간에 넘길 상태가 없어 Context를 두지 않는다.
 * 0.4의 입력값은 그 화면 안에서만 쓰이고, 0.7 → 0.7-A로 넘기는 것은 "이메일 인증을
 * 마쳤다"는 사실 하나뿐이라 라우터 state로 보낸다(frontend-structure.md 6.4).
 *
 * `[?]` 비로그인 상태에서 보호 화면 접근 시 0.6으로 보내는 이동(와이어프레임 이동표
 * `비로그인 ⇢ 0.6`)은 넣지 않았다. 인증 상태 계층이 있어야 하고 이번 화면 초안 범위를
 * 넘는다. 넣으면 목업 미리보기에서 모든 화면이 로그인으로 튕긴다.
 */
import { Outlet } from 'react-router';
import styles from './AuthLayout.module.css';

export default function AuthLayout() {
  return (
    <div className={styles.layout} data-layout="auth">
      <Outlet />
    </div>
  );
}
