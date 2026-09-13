/*
 * 7.8-A 계정 관리
 *
 * 이동표: 7.8 → 7.8-A · 7.8-A → 7.8-A-1 회원 탈퇴 · 7.8-A ⇢ 0.7 아이디 찾기 또는 비밀번호 복구
 *
 * 요소는 와이어프레임 그대로다 — 헤더 아래 이메일, 이메일 줄, 비밀번호 변경, 아이디 찾기,
 * 비밀번호 복구, `위험` 묶음의 회원 탈퇴.
 *
 * 이메일은 API-014에서 읽는다. 이 도메인은 이미 실제 호출이다(`api/users.ts`).
 *
 * - `비밀번호 변경`은 **소셜 전용 계정에서 감춘다.** API-012가 비밀번호 없는 계정에
 *   `409 PASSWORD_NOT_SET`을 주고, API-014 `hasPassword`로 미리 알 수 있다
 * - `아이디 찾기` · `비밀번호 복구`는 둘 다 0.7로 간다(이동표). 0.7이 두 방법을 한
 *   화면에서 고른다
 *
 * `[?]` `비밀번호 변경`이 가는 화면이 와이어프레임에 없다. API-012는 있지만 입력 화면이
 * 없어 누르면 준비 중 안내만 한다.
 */
import { useEffect, useState } from 'react';
import ActionList from '../../../components/ActionList';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './AccountManagePage.module.css';
import type { MyProfile } from '../../../types/user';

export default function AccountManagePage() {
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [notice, setNotice] = useState('');

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((value) => {
        if (active) {
          setProfile(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  if (failed) {
    return (
      <>
        <ScreenHeader title="계정 관리" showBack />

        <StatusView title="계정 정보를 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  if (!profile) {
    return <ScreenHeader title="계정 관리" showBack />;
  }

  return (
    <>
      <ScreenHeader title="계정 관리" subtitle={profile.email} showBack />

      <div className={styles.page}>
        <ActionList
          items={[
            { label: '이메일', value: profile.email },
            ...(profile.hasPassword
              ? [
                  {
                    label: '비밀번호 변경',
                    onClick: () => setNotice('비밀번호 변경 화면은 준비 중이에요'),
                  },
                ]
              : []),
            { label: '아이디 찾기', to: path.accountFind() },
            { label: '비밀번호 복구', to: path.accountFind() },
          ]}
        />

        {notice ? (
          <p className={styles.notice} role="status">
            {notice}
          </p>
        ) : null}

        <ActionList label="위험" items={[{ label: '회원 탈퇴', to: path.withdraw() }]} />
      </div>
    </>
  );
}
