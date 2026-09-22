/*
 * 7.8-A 계정 관리
 *
 * 이동표: 7.8 → 7.8-A · 7.8-A → 7.8-A-1 회원 탈퇴 · 7.8-A ⇢ 0.7 비밀번호 복구
 *
 * 요소는 헤더 아래 이메일, 이메일 줄, 비밀번호 변경, 비밀번호 복구, `소셜 계정` 묶음,
 * `위험` 묶음의 회원 탈퇴다. 와이어프레임의 `아이디 찾기` 줄은 뺐다(CR-25, 업무
 * S15P21E106-455).
 *
 * 이메일은 API-014에서 읽는다. 이 도메인은 이미 실제 호출이다(`api/users.ts`).
 *
 * - `비밀번호 변경`은 **소셜 전용 계정에서 감춘다.** API-012가 비밀번호 없는 계정에
 *   `409 PASSWORD_NOT_SET`을 주고, API-014 `hasPassword`로 미리 알 수 있다
 * - `비밀번호 복구`는 0.7로 간다(이동표). 같이 있던 `아이디 찾기`는 아이디가 곧
 *   이메일이라 찾을 것이 없고 대응 API도 계약에 없어 없앴다. 이 화면은 그 이메일을
 *   헤더에 이미 띄우고 있다(API-014)
 *
 * `비밀번호 변경`이 가는 화면은 와이어프레임에 없어 새로 만들었다(7.8-A-2, CR-22,
 * 업무 S15P21E106-391).
 *
 * ─────────────────────────── 소셜 계정 연결 (업무 S15P21E106-799)
 *
 * 계약 API-009의 화면은 ⑪-6 설정(지금 번호로 이 화면)이다. 와이어프레임 7.8-A에 자리가
 * 없어 업무 -499는 전송 계층(`api/auth.ts` `linkSocialAccount`)까지만 두었고, 여기서
 * `소셜 계정` 묶음으로 붙였다. 배치는 와이어프레임에 그림이 없어 기존 `ActionList` 줄
 * 모양을 따른다.
 *
 * - 제공자마다 한 줄이다. API-014 `linkedProviders`에 있으면 `연결됨` 값 줄이고, 없으면
 *   `연결하기` 동작 줄이다. 자격 증명이 설정되지 않은 제공자(`isOAuthConfigured`)는
 *   로그인 화면과 같은 규칙으로 줄을 두지 않는다 — 눌러도 백엔드가
 *   `UNSUPPORTED_PROVIDER`다. 열린 제공자가 하나도 없으면 묶음 자체가 없다
 * - 인가는 소셜 로그인과 같은 길(`startOAuth`, `intent: 'link'`)로 나간다. 콜백은 이
 *   코드로 로그인하지 않고 7.8-A로 돌려보내며(`linkReturn.ts`), 이 화면이 API-009를
 *   **한 번** 부른 뒤 API-014를 다시 읽어 줄을 갱신한다. 7.8-A-1 회원 탈퇴의 본인
 *   확인(업무 -754)과 같은 방식이다
 * - 연결 해제는 없다. 계약의 미정 사항(DELETE 제공 여부 · 마지막 로그인 수단 해제 불가
 *   규칙)이라 백엔드 계약이 없다
 */
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import ActionList from '../../../components/ActionList';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ApiError } from '../../../api/apiError';
import { linkSocialAccount } from '../../../api/auth';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import {
  isOAuthConfigured,
  SOCIAL_PROVIDER_LABEL,
  startOAuth,
  toSocialProvider,
} from '../../../utils/oauth';
import { readSocialLinkReturn } from './linkReturn';
import type { ActionItem } from '../../../components/ActionList';
import type { SocialProvider } from '../../../types/auth';
import type { MyProfile } from '../../../types/user';
import styles from './AccountManagePage.module.css';

/** 줄의 순서. 0.6 로그인의 버튼 순서와 같다 */
const PROVIDERS: SocialProvider[] = ['kakao', 'naver', 'google'];

/** 연결 실패. 계약(API-009)의 오류 코드마다 무엇을 하면 되는지 적는다 */
const LINK_MESSAGE: Record<string, string> = {
  PROVIDER_ALREADY_LINKED: '이미 연결된 소셜 계정이에요.',
  OAUTH_ACCOUNT_ALREADY_LINKED:
    '이 소셜 계정은 다른 Feelm 계정에 연결되어 있어요. 다른 소셜 계정으로 시도해 주세요.',
  UNSUPPORTED_PROVIDER: '지금은 이 소셜 계정을 연결할 수 없어요.',
  OAUTH_CODE_INVALID: '연결이 끝나지 않았어요. 다시 시도해 주세요.',
  VALIDATION_FAILED: '연결 설정이 올바르지 않아요. 잠시 후 다시 시도해 주세요.',
  OAUTH_PROVIDER_ERROR: '소셜 계정 제공자와 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

export default function AccountManagePage() {
  const navigate = useNavigate();
  const location = useLocation();

  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  /*
   * 소셜 연결에서 돌아왔는지. 처음 그릴 때 한 번만 읽는다 — 읽은 뒤 state를 지우므로
   * 다음 렌더에서는 비어 있다.
   */
  const [returned] = useState(() => readSocialLinkReturn(location.state));
  /** 연결 요청이 나가 있는 제공자. 그 줄을 잠근다 */
  const [linking, setLinking] = useState<SocialProvider | null>(
    returned && !('cancelled' in returned) ? returned.provider : null,
  );
  const [notice, setNotice] = useState<{ kind: 'done' | 'failed'; text: string } | null>(
    returned && 'cancelled' in returned
      ? {
          kind: 'failed',
          text: `${SOCIAL_PROVIDER_LABEL[returned.provider]} 계정 연결을 취소했어요.`,
        }
      : null,
  );

  /*
   * 이 화면이 아직 떠 있는지. 콜백에서 돌아와 API-009를 기다리는 동안 뒤로 가면 화면이
   * 내려가는데, 그 뒤에 도착한 응답으로 안내를 그리지 않는다 — 프로필 조회의 `active`와
   * 같은 규칙이다(리뷰 !562).
   */
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;

    return () => {
      mounted.current = false;
    };
  }, []);

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

  /** API-009를 부르고, 되면 프로필을 다시 읽어 줄을 갱신한다 */
  async function link(provider: SocialProvider, code: string, redirectUri: string) {
    try {
      await linkSocialAccount(provider, code, redirectUri);

      if (!mounted.current) return;

      setNotice({ kind: 'done', text: `${SOCIAL_PROVIDER_LABEL[provider]} 계정을 연결했어요.` });
      setReloadCount((count) => count + 1);
    } catch (error) {
      if (!mounted.current) return;

      const failedCode = error instanceof ApiError ? error.code : '';
      const fallback =
        error instanceof ApiError
          ? error.message
          : '소셜 계정을 연결하지 못했어요. 잠시 후 다시 시도해 주세요.';

      setNotice({ kind: 'failed', text: LINK_MESSAGE[failedCode] ?? fallback });
    } finally {
      if (mounted.current) setLinking(null);
    }
  }

  /*
   * 콜백에서 돌아왔을 때 한 번. 주소의 state부터 지운다 — 코드는 1회용이라 새로고침 · 뒤로
   * 가기로 같은 코드를 다시 보내면 안 되고, 취소 안내도 다시 뜨면 안 된다. StrictMode가 효과를
   * 두 번 돌려도 같은 코드를 다시 보내지 않게 `useRef`로 막는다(`OAuthCallbackPage`와 같은 이유).
   */
  const handled = useRef(false);

  const onReturned = useEffectEvent(() => {
    if (!returned) return;

    void navigate(location.pathname, { replace: true, state: null });

    if (!('cancelled' in returned)) {
      void link(returned.provider, returned.code, returned.redirectUri);
    }
  });

  useEffect(() => {
    if (handled.current) return;

    handled.current = true;
    onReturned();
  }, []);

  if (failed) {
    return (
      <>
        <ScreenHeader title="계정 관리" showBack fallbackTo={path.settings()} />

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
    return <ScreenHeader title="계정 관리" showBack fallbackTo={path.settings()} />;
  }

  const linked = new Set(
    profile.linkedProviders
      .map(toSocialProvider)
      .filter((value): value is SocialProvider => value !== null),
  );

  const socialItems: ActionItem[] = PROVIDERS.filter(isOAuthConfigured).map((provider) => {
    const label = SOCIAL_PROVIDER_LABEL[provider];

    if (linked.has(provider)) return { label, value: '연결됨' };
    if (linking === provider) return { label, value: '연결하는 중…' };

    return {
      label: `${label} 연결하기`,
      onClick: () => {
        setNotice(null);
        setLinking(provider);
        // 제공자 동의 화면으로 나간다. 돌아오면 콜백이 이 화면으로 다시 보낸다
        startOAuth(provider, 'link');
      },
    };
  });

  return (
    <>
      <ScreenHeader
        title="계정 관리"
        subtitle={profile.email}
        showBack
        fallbackTo={path.settings()}
      />

      <div className={styles.page}>
        <ActionList
          items={[
            { label: '이메일', value: profile.email },
            ...(profile.hasPassword ? [{ label: '비밀번호 변경', to: path.passwordChange() }] : []),
            { label: '비밀번호 복구', to: path.accountFind() },
          ]}
        />

        {socialItems.length > 0 ? (
          <div className={styles.social}>
            <ActionList label="소셜 계정" items={socialItems} />

            {notice ? (
              <p
                className={notice.kind === 'done' ? styles.done : styles.failure}
                role={notice.kind === 'done' ? 'status' : 'alert'}
              >
                {notice.text}
              </p>
            ) : null}
          </div>
        ) : null}

        <ActionList label="위험" items={[{ label: '회원 탈퇴', to: path.withdraw() }]} />
      </div>
    </>
  );
}
