/*
 * 7.8 설정
 *
 * 이동표
 * - 7.1 → 7.8 설정 · 7.8 ⇢ 7.1 뒤로 가기
 * - 7.8 → 7.8-A 계정 관리 · 7.8-B 공개 범위 설정 · 7.8-C 알림 설정 · 7.8-D 차단 사용자 관리
 * - 7.8 ⇢ 0.6 로그아웃
 *
 * 요소와 묶음은 와이어프레임 그대로다 — `계정`(프로필 편집 · 계정 관리), `공개 및 알림`
 * (공개 범위 설정 · 알림 설정), `기타`(차단 사용자 관리 · 버전 정보 · 로그아웃), 맨 아래
 * `회원 탈퇴`.
 *
 * - `프로필 편집`은 이동표에 없지만 와이어프레임에 줄이 있다. 7.2로 간다
 * - `회원 탈퇴`도 이동표에는 7.8-A를 거치는 길(`7.8-A → 7.8-A-1`)만 있다. 7.8 맨 아래
 *   글자도 7.8-A-1로 바로 간다. 둘 다 같은 확인 화면이라 경로가 갈려도 결과는 같다
 * - 로그아웃은 실제 세션을 끊는다(API-005, `useAuth().signOut`). 이 업무의 다른 API는
 *   목업이지만 로그아웃은 이미 연결된 기능이라 목업으로 되돌리지 않았다
 *
 * `[?]` 로그아웃 확인 창이 와이어프레임에 없다. 되돌리기 쉬운 동작이라 바로 나간다.
 * `[?]` `v 1.0.0`은 와이어프레임 문구다. 빌드 버전(package.json `0.0.0`)과 잇지 않았다.
 */
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import ActionList from '../../../components/ActionList';
import ScreenHeader from '../../../components/ScreenHeader';
import { useAuth } from '../../../contexts/authContext';
import { path } from '../../../routes/paths';
import styles from './SettingsPage.module.css';

/** 와이어프레임 7.8의 표기 */
const APP_VERSION = 'v 1.0.0';

export default function SettingsPage() {
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const [signingOut, setSigningOut] = useState(false);

  async function handleSignOut() {
    if (signingOut) return;

    setSigningOut(true);

    // 서버 응답과 무관하게 로컬 세션은 끊긴다(api/auth.ts `logout`)
    try {
      await signOut();
    } finally {
      void navigate(path.login(), { replace: true });
    }
  }

  return (
    <>
      <ScreenHeader title="설정" showBack />

      <div className={styles.page}>
        <ActionList
          label="계정"
          items={[
            { label: '프로필 편집', to: path.profileEdit() },
            { label: '계정 관리', to: path.accountManage() },
          ]}
        />

        <ActionList
          label="공개 및 알림"
          items={[
            { label: '공개 범위 설정', to: path.privacySettings() },
            { label: '알림 설정', to: path.notificationSettings() },
          ]}
        />

        <ActionList
          label="기타"
          items={[
            { label: '차단 사용자 관리', to: path.blockedUsers() },
            { label: '버전 정보', value: APP_VERSION },
            { label: '로그아웃', onClick: () => void handleSignOut() },
          ]}
        />

        <Link to={path.withdraw()} className={styles.withdraw}>
          회원 탈퇴
        </Link>
      </div>
    </>
  );
}
