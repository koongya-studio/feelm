/*
 * 회원가입이 실제로 끝났다는 표시 (업무 S15P21E106-610).
 *
 * 0.5 완료 화면은 라우터 state만 보고 성공을 판단했다. state는 주소창에 직접 쳐서 들어와도
 * 없고 새로고침해도 사라지므로, 가입하지 않은 사람이 `/signup/complete`를 열면 **소셜 가입
 * 성공 화면**이 그대로 나왔다.
 *
 * 가입 API(API-001 · API-008)가 성공한 그 자리에서 세션 저장소에 표시를 남기고, 0.5는 그
 * 표시가 있을 때만 그린다. 탭이 닫히면 함께 사라지고, 0.5를 떠나면 지운다.
 *
 * 이메일 주소는 더 이상 담지 않는다. 0.5가 인증 메일 안내와 재발송에 쓰던 값인데, 가입이
 * 인증을 먼저 끝내는 흐름으로 바뀌어 그 안내 자체가 사라졌다(S15P21E106-603 · -637).
 */

const KEY = 'feelm.signup.completed';

/**
 * 어느 경로로 가입했는지.
 *
 * 두 경로의 결과가 같아졌다(S15P21E106-603). 이메일 가입도 인증번호를 먼저 확인하므로
 * 계정이 곧바로 `ACTIVE`이고 응답이 세션을 함께 내린다. 0.5는 둘을 가르지 않지만, 어느
 * 경로였는지는 이후 안내가 갈릴 때를 위해 남긴다.
 */
export type SignupCompletion = { kind: 'email' } | { kind: 'social' };

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

export function markSignupCompleted(completion: SignupCompletion): void {
  storage()?.setItem(KEY, JSON.stringify(completion));
}

export function readSignupCompletion(): SignupCompletion | null {
  const saved = storage()?.getItem(KEY);

  if (!saved) {
    return null;
  }

  try {
    const value = JSON.parse(saved) as Partial<SignupCompletion>;

    if (value.kind === 'social') {
      return { kind: 'social' };
    }

    if (value.kind === 'email') {
      return { kind: 'email' };
    }
  } catch {
    // 손상된 값은 표시가 없는 것과 같다
  }

  return null;
}

export function clearSignupCompletion(): void {
  storage()?.removeItem(KEY);
}
