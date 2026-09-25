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
 * **`OTT 관리`(6.1) 한 줄만 와이어프레임에 없이 더 있다**(사용자 지시 2026-09-16). 7.1 MY
 * 헤더에 있던 것을 여기로 옮겼다. 구독 OTT는 계정에 매인 설정이라 `계정` 묶음에 둔다.
 * **7.8이 6.1의 유일한 입구가 됐다.**
 *
 * 그 아래 **`나에게 맞는 OTT 찾기` 배너(EXT-01)도 와이어프레임에 없이 더 있다**
 * (사용자 지시 2026-09-17, 업무 -461). 홈을 없애면서 옮겨왔고, `OTT 관리` 옆이 제자리라
 * 여기 둔다. **7.8이 EXT-01의 유일한 입구다.**
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
import ConfirmDialog from '../../../components/ConfirmDialog';
import ScreenHeader from '../../../components/ScreenHeader';
import { useAuth } from '../../../contexts/authContext';
import { useShellSize } from '../../../hooks/useShellSize';
import { usePwaInstall } from '../../../pwa/installContext';
import { path } from '../../../routes/paths';
import {
  FILM_GUIDE,
  MOVIE_DETAIL_GUIDE,
  PARTY_GUIDE,
  restartFirstUse,
  restartGuide,
} from '../../../utils/userGuides';
import styles from './SettingsPage.module.css';

/** 와이어프레임 7.8의 표기 */
const APP_VERSION = 'v 1.0.0';

export default function SettingsPage() {
  const navigate = useNavigate();
  const desktop = useShellSize() !== 'mobile';
  const { signOut, user } = useAuth();
  const { availability: installAvailability, install } = usePwaInstall();
  const [signingOut, setSigningOut] = useState(false);
  const [showIosInstallGuide, setShowIosInstallGuide] = useState(false);

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

  function handleInstall() {
    if (installAvailability === 'ios') {
      setShowIosInstallGuide(true);
      return;
    }

    void install();
  }

  return (
    <>
      <ScreenHeader title="설정" showBack={!desktop} fallbackTo={path.my()} />

      <div className={styles.page}>
        <ActionList
          label="계정"
          items={[
            { label: '프로필 편집', to: path.profileEdit() },
            { label: '계정 관리', to: path.accountManage() },
            { label: 'OTT 관리', to: path.ottManage() },
          ]}
        />

        {/*
         * 나에게 맞는 OTT 찾기 (CR-18) → EXT-01 OTT 분석.
         *
         * **홈을 없애면서 여기로 왔다**(사용자 지시 2026-09-16, 업무 -461). 바로 위
         * `OTT 관리`(6.1) 옆이다 — 구독 중인 OTT를 고르는 자리와 어느 OTT를 구독할지
         * 정하는 자리가 붙어 있어야 읽힌다(사용자 지시 2026-09-17).
         *
         * 목록 한 줄로 두지 않고 배너로 둔 것은 **설명을 살리기 위해서다.** `ActionList`는
         * 이름과 화살표만 그려 `감상 기록을 분석해…` 한 줄이 들어갈 자리가 없다.
         *
         * 배너 자체는 데이터를 부르지 않는다. 들어가는 화면(EXT-01 ~ EXT-05)은 업무 -564에서
         * API-068에 연동했다. 이 화면의 **하나뿐인 어두운 블록**이다.
         */}
        <section className={styles.banner}>
          <h2 className={styles.bannerTitle}>나에게 맞는 OTT 찾기</h2>
          <p className={styles.bannerText}>OTT마다 지금 볼 수 있는 작품을 찾아보세요</p>
          <Link to={path.ottFinder()} className={styles.bannerButton}>
            OTT 추천받기
          </Link>
        </section>

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
            ...(installAvailability === 'prompt' || installAvailability === 'ios'
              ? [{ label: 'Feelm 앱 설치', onClick: handleInstall }]
              : []),
            { label: '차단 사용자 관리', to: path.blockedUsers() },
            {
              label: '앱 사용법 다시 보기',
              onClick: () => {
                if (user) {
                  restartFirstUse(user.userId);
                  restartGuide(user.userId, MOVIE_DETAIL_GUIDE);
                  restartGuide(user.userId, PARTY_GUIDE);
                  restartGuide(user.userId, FILM_GUIDE);
                }
                void navigate(path.recommendations());
              },
            },
            { label: '버전 정보', value: APP_VERSION },
            { label: '로그아웃', onClick: () => void handleSignOut() },
          ]}
        />

        <Link to={path.withdraw()} className={styles.withdraw}>
          회원 탈퇴
        </Link>
      </div>

      {showIosInstallGuide ? (
        <ConfirmDialog
          title="Feelm을 홈 화면에 추가해 주세요"
          description={
            <ol className={styles.installSteps}>
              <li>Safari 아래쪽의 공유 버튼을 눌러요.</li>
              <li>메뉴에서 ‘홈 화면에 추가’를 선택해요.</li>
              <li>오른쪽 위의 ‘추가’를 누르면 앱처럼 열 수 있어요.</li>
            </ol>
          }
          confirmLabel="확인"
          cancelLabel="나중에"
          onConfirm={() => setShowIosInstallGuide(false)}
          onCancel={() => setShowIosInstallGuide(false)}
        />
      ) : null}
    </>
  );
}
