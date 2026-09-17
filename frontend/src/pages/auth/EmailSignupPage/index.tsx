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
 *
 * **닉네임은 `중복 확인`(API-016)을 통과해야 가입할 수 있다**(`useNicknameCheck`).
 * 팀 결정 2026-09-14(S15P21E106-319).
 *
 * **타자기에서 종이가 나오는 연출**은 `SignupPaper`가 맡는다. 0.3-A 소셜 가입 정보 입력도
 * 같은 종이를 쓰게 되어 공통으로 올렸다(업무 S15P21E106-499).
 */
import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import GenderField from '../../../components/GenderField';
import NicknameField from '../../../components/NicknameField';
import ScreenHeader from '../../../components/ScreenHeader';
import SignupPaper, { SignupPaperHeading, SignupPaperLine } from '../../../components/SignupPaper';
import { useSignupPaper } from '../../../components/SignupPaper/paperContext';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { signup } from '../../../api/auth';
import { useNicknameCheck } from '../../../hooks/useNicknameCheck';
import type { NicknameCheck } from '../../../hooks/useNicknameCheck';
import { path } from '../../../routes/paths';
import { markSignupCompleted } from '../../../utils/signupCompletion';
import { isValidPassword, PASSWORD_HINT } from '../password';
import styles from './EmailSignupPage.module.css';
import type { Gender } from '../../../types/auth';

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  EMAIL_DUPLICATED: '이미 가입된 이메일이에요. 로그인하거나 다른 이메일을 써 주세요.',
  NICKNAME_DUPLICATED: '이미 사용 중인 닉네임이에요.',
  RATE_LIMITED: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
};

/** 종이의 줄 순서. 닉네임 줄(1)은 처음부터 나와 있다 */
const LINE = {
  nickname: 1,
  birthDate: 2,
  gender: 3,
  email: 4,
  password: 5,
  passwordConfirm: 6,
  submit: 7,
} as const;

export default function EmailSignupPage() {
  const navigate = useNavigate();
  const nicknameCheck = useNicknameCheck();

  const [birthDate, setBirthDate] = useState('');
  const [gender, setGender] = useState<Gender | null>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const submit = async () => {
    if (nicknameCheck.result !== 'available') {
      setFailure('닉네임 중복 확인을 해 주세요.');

      return;
    }

    /*
     * 비밀번호 규칙은 계약(API-001)에 있고 서버도 검사하지만 화면에서 먼저 본다.
     * 서버가 거절하면 `VALIDATION_FAILED` 하나로만 돌아와 어느 값이 문제인지
     * 알려 줄 수 없다(S15P21E106-448).
     */
    if (!isValidPassword(password)) {
      setFailure(`비밀번호는 ${PASSWORD_HINT} 조건을 지켜야 해요.`);

      return;
    }

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
      await signup({ email, password, nickname: nicknameCheck.nickname, birthDate, gender });

      // 0.5는 이 표시가 있을 때만 그려지고, 인증 메일 안내와 재발송에 주소를 쓴다(업무 -610)
      markSignupCompleted({ kind: 'email', email });
      void navigate(path.signupComplete());
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';

      // 확인과 가입 사이에 누가 먼저 가져갔다. 확인을 풀어 다시 고르게 한다
      if (code === 'NICKNAME_DUPLICATED') {
        nicknameCheck.markDuplicated();
      }

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
      <ScreenHeader title="회원가입" showBack fallbackTo={path.signupMethod()} />

      <SignupPaper onSubmit={() => void submit()}>
        <SignupPaperHeading eyebrow="CREATE YOUR FEELM" title="지금 당신의 필름을 시작하세요">
          {/* 입력이 아니라 이동이라 줄을 기다리지 않고 제목 아래에 늘 보인다 */}
          <button
            type="button"
            className={styles.social}
            onClick={() => void navigate(path.signupSocial())}
          >
            소셜 계정으로 가입
          </button>
        </SignupPaperHeading>

        <SignupPaperLine line={LINE.nickname}>
          <NicknameLine check={nicknameCheck} />
        </SignupPaperLine>

        <SignupPaperLine line={LINE.birthDate}>
          <BirthDateLine value={birthDate} onChange={setBirthDate} />
        </SignupPaperLine>

        <SignupPaperLine line={LINE.gender}>
          <GenderLine value={gender} onSelect={setGender} />
        </SignupPaperLine>

        <SignupPaperLine line={LINE.email}>
          <EmailLine value={email} onChange={setEmail} />
        </SignupPaperLine>

        <SignupPaperLine line={LINE.password}>
          <PasswordLine value={password} onChange={setPassword} />
        </SignupPaperLine>

        <SignupPaperLine line={LINE.passwordConfirm}>
          <PasswordConfirmLine value={passwordConfirm} onChange={setPasswordConfirm} />
        </SignupPaperLine>

        <SignupPaperLine line={LINE.submit}>
          {failure ? (
            <p className={styles.failure} role="alert">
              {failure}
            </p>
          ) : null}

          <Button type="submit" disabled={pending}>
            {pending ? '가입 중…' : '가입하기'}
          </Button>
        </SignupPaperLine>
      </SignupPaper>
    </>
  );
}

/*
 * 줄마다 작은 컴포넌트를 두는 것은 `useSignupPaper`가 `SignupPaper` 아래에서만 불릴 수
 * 있기 때문이다. 입력이 생기면 다음 줄을 연다.
 */

function NicknameLine({ check }: { check: NicknameCheck }) {
  const { feed } = useSignupPaper();

  return <NicknameField check={check} onInput={() => feed(LINE.birthDate)} />;
}

function BirthDateLine({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { feed } = useSignupPaper();

  return (
    <TextField
      label="생년월일"
      type="date"
      placeholder="생년월일"
      autoComplete="bday"
      value={value}
      onChange={(event) => {
        onChange(event.target.value);
        feed(LINE.gender);
      }}
    />
  );
}

function GenderLine({
  value,
  onSelect,
}: {
  value: Gender | null;
  onSelect: (gender: Gender) => void;
}) {
  const { feed, type } = useSignupPaper();

  return (
    <GenderField
      value={value}
      onSelect={(gender, label) => {
        onSelect(gender);
        type(label[0]);
        feed(LINE.email);
      }}
    />
  );
}

function EmailLine({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { feed } = useSignupPaper();

  return (
    <TextField
      label="이메일"
      type="email"
      placeholder="이메일"
      autoComplete="email"
      value={value}
      onChange={(event) => {
        onChange(event.target.value);
        feed(LINE.password);
      }}
    />
  );
}

function PasswordLine({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { feed } = useSignupPaper();

  return (
    <TextField
      label="비밀번호"
      type="password"
      placeholder="비밀번호"
      autoComplete="new-password"
      hint={PASSWORD_HINT}
      value={value}
      onChange={(event) => {
        onChange(event.target.value);
        feed(LINE.passwordConfirm);
      }}
    />
  );
}

function PasswordConfirmLine({
  value,
  onChange,
}: {
  value: string;
  onChange: (v: string) => void;
}) {
  const { feed } = useSignupPaper();

  return (
    <TextField
      label="비밀번호 재확인"
      type="password"
      placeholder="비밀번호 재확인"
      autoComplete="new-password"
      value={value}
      onChange={(event) => {
        onChange(event.target.value);
        feed(LINE.submit);
      }}
    />
  );
}
