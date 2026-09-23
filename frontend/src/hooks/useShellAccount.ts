/*
 * 데스크톱 셸이 보여주는 내 계정 (업무 S15P21E106-782).
 *
 * 시안의 좌측 내비 맨 아래 계정 행과 상단 바 오른쪽 아바타가 같은 사람을 가리킨다. 둘이
 * 각자 프로필을 받으면 화면 한 장에 같은 요청이 두 번 나가므로, 셸이 한 번 받아 둘에게 넘긴다.
 *
 * **닉네임은 기다리지 않는다.** 로그인 상태(`authContext`)가 이미 들고 있어 첫 그림부터 나온다.
 * 이메일과 사진(`avatarUrl`)만 API-014를 기다린다 — 계정 행이 비었다가 이름이 늦게 들어오면
 * 내비가 한 번 출렁인다.
 *
 * **보조 정보라 실패해도 조용하다.** 프로필을 못 받으면 이메일 없이 이름만 둔다. 셸은 앱의
 * 뼈대여서 여기서 오류 화면을 띄우면 모든 화면이 함께 막힌다(`AppHeader`의 읽지 않은 알림
 * 수와 같은 규칙).
 */
import { useEffect, useState } from 'react';
import { getMyProfile } from '../api/users';
import { useAuth } from '../contexts/authContext';

export type ShellAccount = {
  userId: number;
  nickname: string;
  /** API-014를 받기 전이거나 못 받았으면 `null`이다. 그때는 이름만 보여준다 */
  email: string | null;
  /** API-014의 프로필 사진. 없거나 아직 못 받았으면 아바타가 자리표시자를 그린다 */
  avatarUrl: string | null;
};

/**
 * @param enabled 데스크톱 셸이 떠 있을 때만 받는다. 모바일에서는 이 정보를 쓰는 자리가 없다
 */
export function useShellAccount(enabled: boolean): ShellAccount | null {
  const { user } = useAuth();
  const [detail, setDetail] = useState<{ email: string; avatarUrl: string | null } | null>(null);

  const userId = user?.userId ?? null;

  useEffect(() => {
    if (!enabled || userId === null) return;

    let active = true;

    getMyProfile()
      .then((profile) => {
        if (active) {
          setDetail({ email: profile.email, avatarUrl: profile.avatarUrl });
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [enabled, userId]);

  if (!user) return null;

  return {
    userId: user.userId,
    nickname: user.nickname,
    email: detail?.email ?? null,
    avatarUrl: detail?.avatarUrl ?? null,
  };
}
