/*
 * 0.4 이메일 회원가입 정보 입력
 *
 * 이동표
 * 0.4 → 0.5 이메일 회원가입 완료
 * 0.4 ⇢ 0.3 소셜 계정으로 가입 선택
 *
 * 입력은 API-001 요청 필드를 따른다. 닉네임 · 생년월일 · 성별 · 이메일 · 비밀번호다
 * (screen-spec-auth.md 0.4, CR-19 · CR-20).
 *
 * **와이어프레임의 `인증요청`·`인증번호 6자리`·`확인`은 없앴다.** 인증번호를 확인하는
 * API가 계약에 없고, 이메일 인증은 메일 링크로 도착하는 0.8이 맡는다(API-002).
 * `나이`도 계약이 받는 `생년월일`로 바꿨다. 팀 합의 2026-09-11.
 *
 * **와이어프레임 0.4는 이전 팔레트로 그려져 있다.** 옥스블러드(`#4A0D0D`) 테두리와 원형
 * 소셜 표식은 폐기된 이전 방향의 잔재다(`design-concept.md` 4장). 표현만 확정 방향으로
 * 옮겼다.
 *
 * 가입에 성공해도 계정은 `PENDING_VERIFICATION`이라 아직 로그인할 수 없다. 0.5에 그 사실과
 * 재발송 경로가 필요하므로 이메일을 함께 넘긴다.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { signup } from '../../../api/auth';
import { path } from '../../../routes/paths';
import { PASSWORD_HINT } from '../password';
import styles from './EmailSignupPage.module.css';
import type { Gender } from '../../../types/auth';

/** 와이어프레임 0.4의 성별 선택지. 표시 문구는 그대로 두고 계약 값에 대응시킨다 */
const GENDERS: { label: string; value: Gender }[] = [
  { label: '여성', value: 'FEMALE' },
  { label: '남성', value: 'MALE' },
  { label: '선택 안 함', value: 'OTHER' },
];

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  EMAIL_DUPLICATED: '이미 가입된 이메일이에요. 로그인하거나 다른 이메일을 써 주세요.',
  NICKNAME_DUPLICATED: '이미 사용 중인 닉네임이에요.',
  RATE_LIMITED: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
};

export default function EmailSignupPage() {
  const navigate = useNavigate();

  const [nickname, setNickname] = useState('');
  const [birthDate, setBirthDate] = useState('');
  const [gender, setGender] = useState<Gender | null>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const submit = async () => {
    /*
     * 비밀번호 재확인은 계약에 없다. 서버에 보낼 값이 아니라 오타를 잡는 화면의 장치이므로
     * 여기서 본다.
     */
    if (password !== passwordConfirm) {
      setFailure('비밀번호가 서로 달라요.');

      return;
    }

    if (!gender) {
      setFailure('성별을 선택해 주세요.');

      return;
    }

    setPending(true);
    setFailure(null);

    try {
      await signup({ email, password, nickname, birthDate, gender });

      // 0.5가 인증 메일 안내와 재발송에 쓴다
      void navigate(path.signupComplete(), { state: { email } });
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';
      const fallback =
        error instanceof ApiError
          ? error.message
          : '가입하지 못했어요. 잠시 후 다시 시도해 주세요.';

      setFailure(MESSAGE[code] ?? fallback);
    } finally {
      setPending(false);
    }
  };

  return (
    <>
      <ScreenHeader title="회원가입" showBack />

      <form
        className={styles.page}
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <TextField
          label="닉네임"
          placeholder="닉네임"
          autoComplete="nickname"
          value={nickname}
          onChange={(event) => setNickname(event.target.value)}
        />

        <TextField
          label="생년월일"
          type="date"
          placeholder="생년월일"
          autoComplete="bday"
          value={birthDate}
          onChange={(event) => setBirthDate(event.target.value)}
        />

        <fieldset className={styles.genders}>
          <legend className={styles.legend}>성별</legend>

          {GENDERS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={styles.gender}
              aria-pressed={gender === option.value}
              onClick={() => setGender(option.value)}
            >
              {option.label}
            </button>
          ))}
        </fieldset>

        <TextField
          label="이메일"
          type="email"
          placeholder="이메일"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />

        <TextField
          label="비밀번호"
          type="password"
          placeholder="비밀번호"
          autoComplete="new-password"
          hint={PASSWORD_HINT}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />

        <TextField
          label="비밀번호 재확인"
          type="password"
          placeholder="비밀번호 재확인"
          autoComplete="new-password"
          value={passwordConfirm}
          onChange={(event) => setPasswordConfirm(event.target.value)}
        />

        {failure ? (
          <p className={styles.failure} role="alert">
            {failure}
          </p>
        ) : null}

        <button
          type="button"
          className={styles.social}
          onClick={() => void navigate(path.signupSocial())}
        >
          소셜 계정으로 가입
        </button>

        <div className={styles.submit}>
          <Button type="submit" disabled={pending}>
            {pending ? '가입 중…' : '가입하기'}
          </Button>
        </div>
      </form>
    </>
  );
}
