/*
 * 닉네임 중복 확인 상태.
 *
 * 가입은 `중복 확인`(API-016)을 통과해야 할 수 있다. 확인한 뒤 닉네임을 고치면 확인이
 * 풀린다 — 결과를 확인한 닉네임과 함께 들고 있다가 지금 값과 다르면 없는 것으로 본다
 * (팀 결정 2026-09-14, S15P21E106-319).
 *
 * 0.4 이메일 회원가입과 0.3-A 소셜 가입 정보 입력이 같은 규칙을 쓰므로 한곳에 둔다
 * (업무 S15P21E106-499).
 */
import { useState } from 'react';
import { checkNicknameAvailability, isValidNickname } from '../api/users';

export type NicknameResult = 'available' | 'duplicated' | 'invalid' | 'failed';

export type NicknameCheck = {
  nickname: string;
  setNickname: (value: string) => void;
  /** 지금 값에 대한 확인 결과. 아직 확인하지 않았으면 `null` */
  result: NicknameResult | null;
  checking: boolean;
  check: () => Promise<void>;
  /** 확인과 가입 사이에 누가 먼저 가져갔을 때 (`NICKNAME_DUPLICATED`) */
  markDuplicated: () => void;
};

export function useNicknameCheck(initial = ''): NicknameCheck {
  const [nickname, setNickname] = useState(initial);
  const [checked, setChecked] = useState<{ nickname: string; result: NicknameResult } | null>(null);
  const [checking, setChecking] = useState(false);

  /* 확인한 뒤 닉네임을 고쳤으면 그 결과는 지금 값의 것이 아니다 */
  const result = checked?.nickname === nickname ? checked.result : null;

  const check = async () => {
    const target = nickname;

    // 형식이 틀린 값은 서버도 `INVALID_FORMAT`으로 답하므로 부르지 않는다
    if (!isValidNickname(target)) {
      setChecked({ nickname: target, result: 'invalid' });

      return;
    }

    setChecking(true);

    try {
      const { available, reason } = await checkNicknameAvailability(target);

      setChecked({
        nickname: target,
        result: available ? 'available' : reason === 'INVALID_FORMAT' ? 'invalid' : 'duplicated',
      });
    } catch {
      setChecked({ nickname: target, result: 'failed' });
    } finally {
      setChecking(false);
    }
  };

  return {
    nickname,
    setNickname,
    result,
    checking,
    check,
    markDuplicated: () => setChecked({ nickname, result: 'duplicated' }),
  };
}
