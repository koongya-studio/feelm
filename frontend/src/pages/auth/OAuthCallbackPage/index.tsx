/*
 * 소셜 로그인 콜백. 화면 번호가 없는 통과 지점이다.
 *
 * 제공자가 동의 결과를 이 주소로 돌려보낸다. 인가 코드를 제공자와 교환하는 것은
 * 백엔드이므로(API-007 처리 규칙) 여기서는 주소에서 `code`를 꺼내 넘기고 결과에 따라
 * 갈라 보내기만 한다.
 *
 * - 연결된 계정이 있으면 로그인이 끝난 상태다. 이메일 로그인과 같게 `onboardingCompleted`로
 *   1.1과 2.1을 가른다(API-004 처리 규칙)
 * - 없으면 `signupToken`을 들고 0.3-A로 간다. 이때는 아직 세션이 없다
 *
 * **인가 코드는 1회용이다.** StrictMode는 효과를 두 번 돌리는데 두 번째 호출이 이미
 * 태운 코드를 다시 보내 실패한다. 0.8 이메일 인증에서 겪은 것과 같은 자리라 같은 방식으로
 * `useRef` 가드를 둔다(S15P21E106-366).
 *
 * 주소만 보고 알 수 있는 실패(취소 · `state` 불일치 · 코드 없음)는 효과에 들어가기 전에
 * 정한다. 0.8과 같은 이유다(react-hooks/set-state-in-effect).
 *
 * 실패해도 이 주소에 머무르지 않는다. 주소에 인가 코드가 남은 화면이라 새로고침하면
 * 반드시 실패하기 때문이다. 안내는 여기서 보여 주고 돌아가는 길만 준다.
 *
 * **회원 탈퇴의 본인 확인(`intent: 'withdraw'`)은 로그인하지 않는다**(업무 -754). 코드를
 * 7.8-A-1에 넘기고 그 화면이 API-013을 부른다. 취소하거나 코드가 없어도 로그인 화면이
 * 아니라 7.8-A-1로 돌아가 거기서 알린다 — 사용자는 로그인한 채 탈퇴 도중이었다.
 */
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import Button from '../../../components/Button';
import { ApiError } from '../../../api/apiError';
import { useAuth } from '../../../contexts/authContext';
import { path } from '../../../routes/paths';
import { isReturnPath } from '../../../routes/returnPath';
import { clearOAuthRequest, readOAuthRequest } from '../../../utils/oauth';
import type { WithdrawReauth } from '../../my/WithdrawPage/reauth';
import styles from './OAuthCallbackPage.module.css';

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  OAUTH_CODE_INVALID: '소셜 로그인이 완료되지 않았어요. 처음부터 다시 시도해 주세요.',
  UNSUPPORTED_PROVIDER: '지금은 이 제공자로 로그인할 수 없어요.',
  OAUTH_EMAIL_REQUIRED:
    '이메일을 받지 못해 가입할 수 없어요. 제공자에서 이메일 제공에 동의해 주세요.',
  INVALID_CREDENTIALS: '이 계정으로는 로그인할 수 없어요.',
  VALIDATION_FAILED: '소셜 로그인 설정이 올바르지 않아요. 잠시 후 다시 시도해 주세요.',
  OAUTH_PROVIDER_ERROR: '제공자와 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

const CANCELLED = '소셜 로그인을 취소했어요.';
const BROKEN_REQUEST = '요청이 올바르지 않아요. 로그인 화면에서 다시 시작해 주세요.';

export default function OAuthCallbackPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { signInWithSocial } = useAuth();

  const code = params.get('code');
  // 제공자가 동의를 받지 못하면 코드 대신 `error`를 붙여 보낸다
  const cancelled = Boolean(params.get('error'));
  const request = readOAuthRequest(params.get('state'));
  const reauth = request?.intent === 'withdraw';

  const [failure, setFailure] = useState<string | null>(
    reauth ? null : cancelled ? CANCELLED : !request || !code ? BROKEN_REQUEST : null,
  );

  const handled = useRef(false);

  // 탈퇴 본인 확인은 결과가 어떻든 7.8-A-1로 돌려보낸다. 코드를 쓰는 곳은 그 화면이다
  useEffect(() => {
    if (handled.current || !request || !reauth) {
      return;
    }

    handled.current = true;
    clearOAuthRequest();

    const state: WithdrawReauth =
      code && !cancelled
        ? { provider: request.provider, code, redirectUri: request.redirectUri }
        : { cancelled: true };

    void navigate(path.withdraw(), { replace: true, state: { reauth: state } });
  }, [cancelled, code, navigate, reauth, request]);

  useEffect(() => {
    if (handled.current || !request || !code || cancelled || reauth) {
      return;
    }

    handled.current = true;
    clearOAuthRequest();

    const run = async () => {
      try {
        const result = await signInWithSocial(request.provider, code, request.redirectUri);

        if (result.signupRequired) {
          void navigate(path.signupSocialProfile(), {
            replace: true,
            state: {
              provider: request.provider,
              signupToken: result.signupToken,
              suggestedNickname: result.suggestedNickname,
            },
          });

          return;
        }

        // 보호 라우트에서 왔으면 그 자리로 돌아간다(`RequireAuth`, 업무 -610)
        const returnTo = isReturnPath(request.returnTo) ? request.returnTo : path.film();

        void navigate(result.user.onboardingCompleted ? returnTo : path.onboardingTaste(), {
          replace: true,
        });
      } catch (error) {
        const failed = error instanceof ApiError ? error.code : '';
        const fallback =
          error instanceof ApiError
            ? error.message
            : '로그인하지 못했어요. 잠시 후 다시 시도해 주세요.';

        setFailure(MESSAGE[failed] ?? fallback);
      }
    };

    void run();
  }, [cancelled, code, navigate, reauth, request, signInWithSocial]);

  return (
    <div className={styles.page}>
      {failure ? (
        <>
          <p className={styles.failure} role="alert">
            {failure}
          </p>

          <Button onClick={() => void navigate(path.login(), { replace: true })}>
            로그인 화면으로
          </Button>
        </>
      ) : (
        <p className={styles.progress} role="status">
          소셜 계정을 확인하고 있어요…
        </p>
      )}
    </div>
  );
}
