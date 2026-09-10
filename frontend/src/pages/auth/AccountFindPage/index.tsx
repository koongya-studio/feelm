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
 * 인증이 끝났을 때 가는 곳이 탭에 따라 다르다. 비밀번호 찾기는 0.7-A로 가고, 아이디
 * 찾기는 이동표에 다음 화면이 없다.
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
    lead: '가입할 때 사용한 이메일로\n인증하면 비밀번호를 재설정할 수 있어요',
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

  const confirm = () => {
    void navigate(tab === 'password' ? path.passwordReset() : path.login());
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
          <Button onClick={confirm}>확인</Button>

          <Button variant="text" onClick={() => void navigate(path.login())}>
            로그인으로 돌아가기
          </Button>
        </div>
      </div>
    </>
  );
}
