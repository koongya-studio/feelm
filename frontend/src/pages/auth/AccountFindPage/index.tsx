/*
 * 0.7 계정 찾기
 *
 * 이동표
 * 0.6 ⇢ 0.7 아이디 찾기 · 비밀번호 찾기
 * 0.7 ⇢ 0.6 로그인으로 돌아가기
 * 0.7 → 0.7-A 이메일 인증 완료 (비밀번호 찾기)
 *
 * 두 갈래를 탭으로 나눠 갖고, 0.6에서 어느 쪽을 눌러 들어왔는지를 라우터 state로 받는다.
 * 직접 열면 첫 탭인 아이디 찾기다.
 *
 * **비밀번호 찾기는 인증번호가 아니라 재설정 메일을 보낸다** (CR-21, 업무
 * S15P21E106-391). 와이어프레임은 `이메일 + 인증번호 요청` · `인증번호` · `확인`을
 * 그리지만 계약에는 인증번호를 확인하는 API가 없다. API-010이 메일을 보내고, 메일
 * 링크로 열리는 0.7-A가 토큰으로 재설정한다(API-011). 그래서 이 탭에서 0.7-A로 가는
 * 화면 이동은 사라진다 — 이동표의 `0.7 → 0.7-A`는 이제 메일 링크가 잇는다.
 *
 * 응답은 계정이 있든 없든 `202`다(API-010 처리 규칙). 보냈다고 단정하는 문구를 쓰면
 * 가입 여부가 드러나므로 조건절로 안내한다.
 *
 * **아이디 찾기 탭은 그대로 두었다.** 대응 API가 계약에 아예 없어 연동할 것이 없다
 * (screen-spec-auth.md 5장). 이번 업무 범위는 비밀번호 쪽이다.
 *
 * `[?]` 아이디 찾기 성공 시 표시(찾은 아이디를 어디에 어떻게 보여주는지)가 와이어프레임에
 * 없다. 화면을 임의로 만들지 않고 0.6으로 돌려보낸다.
 * `[?]` 제한 시간이 끝났을 때의 처리도 없다. 표시만 `00:00`에서 멈춘다.
 */
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { requestPasswordReset } from '../../../api/auth';
import { path } from '../../../routes/paths';
import styles from './AccountFindPage.module.css';

type Tab = 'id' | 'password';

const TABS: { id: Tab; label: string; lead: string }[] = [
  {
    id: 'id',
    label: '아이디 찾기',
    lead: '가입할 때 사용한 이메일로\n인증하면 아이디를 알려드려요',
  },
  {
    id: 'password',
    label: '비밀번호 찾기',
    // 인증번호가 아니라 메일 링크로 바뀌었으므로 안내도 그것을 말한다(CR-21)
    lead: '가입할 때 사용한 이메일로\n비밀번호 재설정 링크를 보내드려요',
  },
];

/** 와이어프레임 0.7의 인증번호 자리수. 표시값이 `4 2 8 _ _ _`이라 여섯 자리다 */
const CODE_LENGTH = 6;

/**
 * 입력 제한 시간(초).
 *
 * 와이어프레임은 남은 시간을 `02:47`로 그려 두었다. 3분에서 13초가 지난 순간을 그린
 * 것으로 보고 3분을 제한 시간으로 잡았다. `[?]` 실제 값은 계약에서 확인이 필요하다.
 */
const CODE_TTL_SECONDS = 180;

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  RATE_LIMITED: '요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.',
};

function formatRemaining(seconds: number) {
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`;
}

export default function AccountFindPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const initialTab = (location.state as { tab?: Tab } | null)?.tab ?? 'id';
  const [tab, setTab] = useState<Tab>(initialTab);
  const [remaining, setRemaining] = useState<number | null>(null);

  const [email, setEmail] = useState('');
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  // 요청 이후에만 센다. 0에 닿으면 멈춘다
  useEffect(() => {
    if (remaining === null || remaining <= 0) return;

    const timer = window.setInterval(() => {
      setRemaining((value) => (value === null ? null : Math.max(0, value - 1)));
    }, 1000);

    return () => window.clearInterval(timer);
  }, [remaining]);

  const active = TABS.find((item) => item.id === tab) ?? TABS[0];
  const requested = remaining !== null;

  const sendResetMail = async () => {
    setSending(true);
    setFailure(null);

    try {
      await requestPasswordReset(email);
      setSent(true);
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';
      const fallback =
        error instanceof ApiError
          ? error.message
          : '메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요.';

      setFailure(MESSAGE[code] ?? fallback);
    } finally {
      setSending(false);
    }
  };

  return (
    <>
      <ScreenHeader title="계정 찾기" showBack />

      <div className={styles.page}>
        <div className={styles.tabs} role="tablist" aria-label="계정 찾기 방식">
          {TABS.map((item) => (
            <button
              key={item.id}
              type="button"
              role="tab"
              className={styles.tab}
              aria-selected={tab === item.id}
              onClick={() => setTab(item.id)}
            >
              {item.label}
            </button>
          ))}
        </div>

        <p className={styles.lead}>{active.lead}</p>

        {tab === 'password' ? (
          <form
            className={styles.form}
            onSubmit={(event) => {
              event.preventDefault();
              void sendResetMail();
            }}
          >
            <TextField
              label="이메일"
              type="email"
              placeholder="이메일"
              autoComplete="email"
              value={email}
              onChange={(event) => {
                setEmail(event.target.value);
                setSent(false);
              }}
            />

            {/*
             * 가입 여부를 드러내지 않으려고 조건절로 적는다(API-010 처리 규칙). 여기서
             * `보냈어요`라고 단정하면 응답이 숨긴 것을 화면이 흘린다.
             */}
            {sent ? (
              <p className={styles.notice} role="status">
                가입된 계정이 있다면 재설정 링크를 보냈어요. 메일함을 확인해 주세요.
              </p>
            ) : null}

            {failure ? (
              <p className={styles.failure} role="alert">
                {failure}
              </p>
            ) : null}

            <div className={styles.actions}>
              <Button type="submit" disabled={sending || email === ''}>
                {sending ? '보내는 중…' : sent ? '메일 다시 보내기' : '재설정 링크 받기'}
              </Button>

              <Button variant="text" onClick={() => void navigate(path.login())}>
                로그인으로 돌아가기
              </Button>
            </div>
          </form>
        ) : (
          <>
            <TextField
              label="이메일"
              type="email"
              placeholder="이메일"
              autoComplete="email"
              action={{ label: '인증번호 요청', onClick: () => setRemaining(CODE_TTL_SECONDS) }}
            />

            <TextField
              label="인증번호"
              inputMode="numeric"
              maxLength={CODE_LENGTH}
              placeholder={`인증번호 ${CODE_LENGTH}자리`}
              disabled={!requested}
              trailing={
                requested ? (
                  <span className={styles.remaining}>{formatRemaining(remaining)}</span>
                ) : null
              }
            />

            {requested ? (
              <p className={styles.resend}>
                메일이 오지 않았나요?{' '}
                <button
                  type="button"
                  className={styles.resendAction}
                  onClick={() => setRemaining(CODE_TTL_SECONDS)}
                >
                  다시 보내기
                </button>
              </p>
            ) : null}

            <div className={styles.actions}>
              <Button onClick={() => void navigate(path.login())}>확인</Button>

              <Button variant="text" onClick={() => void navigate(path.login())}>
                로그인으로 돌아가기
              </Button>
            </div>
          </>
        )}
      </div>
    </>
  );
}
