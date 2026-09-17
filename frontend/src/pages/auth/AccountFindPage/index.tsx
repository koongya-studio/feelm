/*
 * 0.7 비밀번호 찾기
 *
 * 이동표
 * 0.6 ⇢ 0.7 비밀번호 찾기
 * 0.7 ⇢ 0.6 로그인으로 돌아가기
 * 메일 링크 ⇢ 0.7-A 재설정 메일의 링크
 *
 * **비밀번호 찾기는 인증번호가 아니라 재설정 메일을 보낸다** (CR-21, 업무
 * S15P21E106-391). 와이어프레임은 `이메일 + 인증번호 요청` · `인증번호` · `확인`을
 * 그리지만 계약에는 인증번호를 확인하는 API가 없다. API-010이 메일을 보내고, 메일
 * 링크로 열리는 0.7-A가 토큰으로 재설정한다(API-011). 그래서 이 화면에서 0.7-A로 가는
 * 화면 이동은 없다 — 이동표의 `0.7 → 0.7-A`는 메일 링크가 잇는다.
 *
 * 응답은 계정이 있든 없든 `202`다(API-010 처리 규칙). 보냈다고 단정하는 문구를 쓰면
 * 가입 여부가 드러나므로 조건절로 안내한다.
 *
 * **아이디 찾기 탭은 없앴다** (CR-25, 팀 결정 2026-09-17, 업무 S15P21E106-455).
 * 로그인 식별자가 이메일이라 아이디가 곧 이메일이고(API-004), 이메일을 입력해 아이디를
 * 돌려받는 흐름은 순환이다. 대응 API도 계약에 없어(`docs/api/users.md`) 인증번호 요청과
 * 확인, 찾은 아이디를 돌려주는 경로가 모두 없었고, 그래서 그 탭은 아무 데도 요청하지
 * 않으면서 동작하는 것처럼 보였다. 요구사항에도 근거가 없다(FR-01).
 *
 * 탭이 하나만 남아 탭 줄을 지웠다. 화면 이름도 `계정 찾기`에서 `비밀번호 찾기`로
 * 바꿨다. 경로(`/account/find`)와 화면 번호(0.7)는 들어오는 링크가 여럿이라 그대로 둔다.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { requestPasswordReset } from '../../../api/auth';
import { path } from '../../../routes/paths';
import styles from './AccountFindPage.module.css';

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  RATE_LIMITED: '요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.',
};

export default function AccountFindPage() {
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

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
      <ScreenHeader title="비밀번호 찾기" showBack fallbackTo={path.login()} />

      <div className={styles.page}>
        {/* 인증번호가 아니라 메일 링크로 바뀌었으므로 안내도 그것을 말한다(CR-21) */}
        <p className={styles.lead}>
          {'가입할 때 사용한 이메일로\n비밀번호 재설정 링크를 보내드려요'}
        </p>

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
      </div>
    </>
  );
}
