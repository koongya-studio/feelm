/*
 * 7.8-A-2 비밀번호 변경
 *
 * 이동표: 7.8-A → 7.8-A-2 비밀번호 변경 · 7.8-A-2 → 0.6 변경 완료
 *
 * **와이어프레임에 없는 화면이다** (CR-22, 업무 S15P21E106-391). 7.8-A가 `비밀번호 변경`
 * 줄을 그려 두었는데 그 줄이 여는 화면이 없었다. API-012는 현재 비밀번호와 새 비밀번호를
 * 받으므로 입력 세 칸(현재 · 새 · 새 확인)을 0.7-A와 같은 형태로 둔다. 재확인 칸은
 * 계약에 없는 화면의 장치다 — 0.4 · 0.7-A와 같은 이유로 오타를 여기서 잡는다.
 *
 * **성공하면 다시 로그인해야 한다.** 서버가 Refresh Token을 모두 폐기하고, 이 경로에는
 * 쿠키가 실리지 않아 현재 세션만 남길 수도 없다(API-012 처리 규칙). 그래서 성공 직후
 * 세션을 끊고 0.6으로 보낸다. 끊지 않으면 Access Token이 만료되는 순간 아무 화면에서나
 * 갑자기 로그아웃된다.
 *
 * 계약이 가르는 세 실패를 화면도 가른다.
 * - `401 INVALID_CREDENTIALS` 현재 비밀번호가 틀렸다. 그 칸만 고치면 된다
 * - `409 PASSWORD_NOT_SET` 소셜 전용 계정이다. 7.8-A가 `hasPassword`로 미리 걸러 이
 *   화면을 감추지만, 주소로 바로 들어오면 여기서 만난다
 * - `400 VALIDATION_FAILED` 새 비밀번호가 정책에 맞지 않다. 서버 문구가 더 자세하다
 */
import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { changePassword } from '../../../api/auth';
import { useAuth } from '../../../contexts/authContext';
import { path } from '../../../routes/paths';
import { isValidPassword, PASSWORD_HINT } from '../../auth/password';
import styles from './PasswordChangePage.module.css';

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  INVALID_CREDENTIALS: '현재 비밀번호가 올바르지 않아요.',
  PASSWORD_NOT_SET: '소셜 계정으로 가입해 비밀번호가 없어요. 비밀번호를 바꿀 수 없어요.',
};

export default function PasswordChangePage() {
  const navigate = useNavigate();
  const { signOut } = useAuth();

  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const passwordError = submitted && !isValidPassword(password) ? PASSWORD_HINT : undefined;
  const confirmationError =
    submitted && confirmation !== password ? '비밀번호가 서로 달라요' : undefined;

  const save = async () => {
    setSaving(true);
    setFailure(null);

    try {
      await changePassword(current, password);
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';
      const fallback =
        error instanceof ApiError
          ? error.message
          : '비밀번호를 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.';

      setFailure(MESSAGE[code] ?? fallback);
      setSaving(false);

      return;
    }

    /*
     * 서버가 이미 Refresh Token을 폐기했으므로 로그아웃 요청이 실패해도 상관없다.
     * 화면이 할 일은 메모리의 Access Token을 버리고 0.6으로 보내는 것뿐이다.
     */
    try {
      await signOut();
    } finally {
      void navigate(path.login(), { replace: true });
    }
  };

  return (
    <>
      <ScreenHeader title="비밀번호 변경" showBack />

      <form
        className={styles.page}
        onSubmit={(event) => {
          event.preventDefault();
          setSubmitted(true);

          if (isValidPassword(password) && confirmation === password) {
            void save();
          }
        }}
      >
        <p className={styles.lead}>
          바꾸고 나면 로그인한 기기가 모두 로그아웃돼요
          <br />새 비밀번호로 다시 로그인해 주세요
        </p>

        <TextField
          label="현재 비밀번호"
          type="password"
          placeholder="현재 비밀번호"
          autoComplete="current-password"
          value={current}
          onChange={(event) => setCurrent(event.target.value)}
        />

        <TextField
          label="새 비밀번호"
          type="password"
          placeholder="새 비밀번호"
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          hint={PASSWORD_HINT}
          error={passwordError}
        />

        <TextField
          label="새 비밀번호 확인"
          type="password"
          placeholder="새 비밀번호 확인"
          autoComplete="new-password"
          value={confirmation}
          onChange={(event) => setConfirmation(event.target.value)}
          error={confirmationError}
        />

        {failure ? (
          <p className={styles.failure} role="alert">
            {failure}
          </p>
        ) : null}

        <div className={styles.actions}>
          <Button type="submit" disabled={saving || current === ''}>
            {saving ? '바꾸는 중…' : '비밀번호 변경'}
          </Button>
        </div>
      </form>
    </>
  );
}
