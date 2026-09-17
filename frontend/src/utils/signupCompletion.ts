/*
 * 회원가입이 실제로 끝났다는 표시 (업무 S15P21E106-610).
 *
 * 0.5 완료 화면은 라우터 state만 보고 성공을 판단했다. state는 주소창에 직접 쳐서 들어와도
 * 없고 새로고침해도 사라지므로, 가입하지 않은 사람이 `/signup/complete`를 열면 **소셜 가입
 * 성공 화면**이 그대로 나왔다.
 *
 * 가입 API(API-001 · API-008)가 성공한 그 자리에서 세션 저장소에 표시를 남기고, 0.5는 그
 * 표시가 있을 때만 그린다. 탭이 닫히면 함께 사라지고, 0.5를 떠나면 지운다.
 */

const KEY = 'feelm.signup.completed';

export type SignupCompletion =
  /** 이메일 가입. 인증 메일이 간 주소를 0.5가 안내와 재발송에 쓴다 */
  | { kind: 'email'; email: string }
  /** 소셜 가입. 곧바로 `ACTIVE`라 로그인된 상태다(API-008) */
  | { kind: 'social' };

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

    if (value.kind === 'email' && typeof value.email === 'string' && value.email) {
      return { kind: 'email', email: value.email };
    }
  } catch {
    // 손상된 값은 표시가 없는 것과 같다
  }

  return null;
}

export function clearSignupCompletion(): void {
  storage()?.removeItem(KEY);
}
