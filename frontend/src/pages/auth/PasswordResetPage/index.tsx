/*
 * 0.7-A 비밀번호 재설정
 *
 * 이동표
 * 메일 링크 ⇢ 0.7-A (CR-21로 바뀐 진입 경로)
 * 0.7-A → 0.6 비밀번호 변경 완료
 *
 * **재설정 메일의 링크로 열린다** (업무 S15P21E106-391). 와이어프레임은 0.7의 인증번호를
 * 통과하면 이 화면이 열리는 것으로 그렸지만, 계약에는 인증번호를 확인하는 API가 없고
 * API-011이 메일 링크에 실린 토큰을 받는다. 와이어프레임 0.7-A의 안내 문구
 * `이메일 인증이 완료됐어요`가 오히려 이 상태와 맞는다.
 *
 * 토큰은 서버가 발급한 불투명 문자열이므로 0.8과 같이 해석하지 않고 그대로 넘긴다.
 * 링크 주소는 백엔드 설정값(`feelm.mail.password-reset-url`)과 같아야 한다.
 *
 * 계약이 가르는 세 실패를 화면도 가른다. 위조·소진된 토큰(`RESET_TOKEN_INVALID`)과
 * 만료(`RESET_TOKEN_EXPIRED`)는 입력을 고쳐도 소용이 없어 0.7에서 다시 요청해야 하고,
 * 정책 위반(`VALIDATION_FAILED`)은 이 화면에서 다시 입력하면 된다. 한 문구로 뭉뚱그리면
 * 사용자가 다음에 무엇을 할지 알 수 없다.
 *
 * 성공하면 서버가 모든 Refresh Token을 폐기한다(API-011 처리 규칙). 다른 기기도 로그인이
 * 풀리므로 그 사실을 알리고 0.6으로 보낸다.
 *
 * 비밀번호 규칙은 계약(API-011)을 따른다. 와이어프레임 0.7-A가 적어 둔
 * `8자 이상, 영문과 숫자 포함`은 계약보다 느슨해 화면 안내대로 입력해도 서버가
 * 거절했다(S15P21E106-448). 규칙과 문구는 `../password`가 함께 가진다. 화면이 계약과
 * 같은 규칙을 검사하므로 위 `VALIDATION_FAILED`는 서버만 아는 규칙에만 닿는다.
 */
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { resetPassword } from '../../../api/auth';
import { path } from '../../../routes/paths';
import { isValidPassword, PASSWORD_HINT } from '../password';
import styles from './PasswordResetPage.module.css';

/** 링크가 더 이상 쓸 수 없는 상태. 이 화면에서 다시 입력해도 소용이 없다 */
type LinkFailure = 'invalid' | 'expired';

const LINK_MESSAGE: Record<LinkFailure, string> = {
  invalid: '재설정 링크가 올바르지 않아요. 메일의 링크를 다시 확인해 주세요.',
  expired: '재설정 링크가 만료됐어요. 재설정 메일을 다시 받아 주세요.',
};

function linkFailureOf(error: ApiError): LinkFailure | null {
  if (error.code === 'RESET_TOKEN_INVALID') {
    return 'invalid';
  }

  return error.code === 'RESET_TOKEN_EXPIRED' ? 'expired' : null;
}

export default function PasswordResetPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token');

  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  /* 주소에 토큰이 없으면 서버에 물어볼 것도 없다. 처음부터 끊긴 링크로 본다 */
  const [linkFailure, setLinkFailure] = useState<LinkFailure | null>(token ? null : 'invalid');
  const [failure, setFailure] = useState<string | null>(null);

  const passwordError = submitted && !isValidPassword(password) ? PASSWORD_HINT : undefined;
  const confirmationError =
    submitted && confirmation !== password ? '비밀번호가 서로 달라요' : undefined;

  const save = async () => {
    if (!token) {
      return;
    }

    setSaving(true);
    setFailure(null);

    try {
      await resetPassword(token, password);
      setSaved(true);
    } catch (error) {
      if (error instanceof ApiError) {
        const link = linkFailureOf(error);

        if (link) {
          setLinkFailure(link);

          return;
        }

        // 정책 위반은 서버 문구가 무엇이 틀렸는지 더 잘 안다
        setFailure(error.message);

        return;
      }

      setFailure('비밀번호를 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.');
    } finally {
      setSaving(false);
    }
  };

  if (linkFailure) {
    return (
      <>
        <ScreenHeader title="비밀번호 재설정" showBack />

        <div className={styles.page}>
          <p className={styles.lead} role="alert">
            {LINK_MESSAGE[linkFailure]}
          </p>

          <div className={styles.actions}>
            <Button onClick={() => void navigate(path.accountFind(), { replace: true })}>
              재설정 메일 다시 받기
            </Button>

            <Button variant="text" onClick={() => void navigate(path.login())}>
              로그인으로 돌아가기
            </Button>
          </div>
        </div>
      </>
    );
  }

  if (saved) {
    return (
      <>
        <ScreenHeader title="비밀번호 재설정" showBack />

        <div className={styles.page}>
          <p className={styles.lead} role="status">
            비밀번호를 바꿨어요
            <br />
            로그인한 기기는 모두 로그아웃됐으니 새 비밀번호로 다시 로그인해 주세요
          </p>

          <div className={styles.actions}>
            <Button onClick={() => void navigate(path.login(), { replace: true })}>
              로그인하러 가기
            </Button>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <ScreenHeader title="비밀번호 재설정" showBack />

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
          이메일 인증이 완료됐어요
          <br />새 비밀번호를 설정해 주세요
        </p>

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
          <Button type="submit" disabled={saving}>
            {saving ? '바꾸는 중…' : '비밀번호 변경'}
          </Button>

          <Button variant="text" onClick={() => void navigate(path.login())}>
            로그인으로 돌아가기
          </Button>
        </div>
      </form>
    </>
  );
}
