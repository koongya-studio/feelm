/*
 * 0.7-A 비밀번호 재설정
 *
 * 이동표
 * 0.7 → 0.7-A 이메일 인증 완료 (비밀번호 찾기)
 * 0.7-A → 0.6 비밀번호 변경 완료
 *
 * 비밀번호 규칙은 계약(API-011)을 따른다. 와이어프레임 0.7-A가 적어 둔
 * `8자 이상, 영문과 숫자 포함`은 계약보다 느슨해 화면 안내대로 입력해도 서버가
 * 거절했다(S15P21E106-448). 규칙과 문구는 `../password`가 함께 가진다.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { path } from '../../../routes/paths';
import { isValidPassword, PASSWORD_HINT } from '../password';
import styles from './PasswordResetPage.module.css';

export default function PasswordResetPage() {
  const navigate = useNavigate();
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const passwordError = submitted && !isValidPassword(password) ? PASSWORD_HINT : undefined;
  const confirmationError =
    submitted && confirmation !== password ? '비밀번호가 서로 달라요' : undefined;

  return (
    <>
      <ScreenHeader title="비밀번호 재설정" showBack />

      <form
        className={styles.page}
        onSubmit={(event) => {
          event.preventDefault();
          setSubmitted(true);

          if (isValidPassword(password) && confirmation === password) {
            void navigate(path.login());
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

        <div className={styles.actions}>
          <Button type="submit">비밀번호 변경</Button>

          <Button variant="text" onClick={() => void navigate(path.login())}>
            로그인으로 돌아가기
          </Button>
        </div>
      </form>
    </>
  );
}
