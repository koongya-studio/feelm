/*
 * 0.4 이메일 회원가입 정보 입력
 *
 * 이동표
 * 0.4 → 0.5 이메일 회원가입 완료
 * 0.4 ⇢ 0.3 소셜 계정으로 가입 선택
 *
 * 입력은 닉네임 · 생년월일 · 성별 · 이메일(+ 인증번호) · 비밀번호다
 * (screen-spec-auth.md 0.4, CR-20).
 *
 * **와이어프레임의 `인증요청`·`인증번호 6자리`·`확인`이 되살아났다.** CR-19로 지웠던
 * 요소인데, 가입 계약이 메일 링크에서 인증번호로 바뀌면서(S15P21E106-601, API-078 · 076)
 * 이 화면이 다시 인증을 맡는다. 이메일 소유를 먼저 확인하고 받은 `emailVerificationToken`
 * 으로 가입하므로 **요청 본문에 이메일을 보내지 않는다** — 서버는 토큰 안의 주소만
 * 믿는다(API-001 처리 규칙). `나이`가 `생년월일`인 것은 그대로다(CR-20).
 *
 * 인증 단계는 셋이다. 번호를 받기 전(`idle`) · 번호를 받은 뒤(`sent`) · 확인을 마친
 * 뒤(`verified`). 확인을 마치면 이메일 칸을 잠근다. 토큰이 그 주소로 발급됐으므로 주소만
 * 고치면 보내는 값과 실제 가입 주소가 어긋난다.
 *
 * **와이어프레임 0.4는 이전 팔레트로 그려져 있다.** 옥스블러드(`#4A0D0D`) 테두리와 원형
 * 소셜 표식은 폐기된 이전 방향의 잔재다(`design-concept.md` 4장). 표현만 확정 방향으로
 * 옮겼다.
 *
 * 가입에 성공하면 곧바로 로그인 상태가 된다(API-001 응답이 세션을 함께 내린다,
 * S15P21E106-603). 세션은 `api/auth`가 보관하는 Access Token만으로는 부족하고 인증
 * Context까지 올라가야 한다(`completeEmailSignup`). 라우트 보호가 그 상태를 보기 때문이다.
 * 0.5는 완료만 알리고 `시작하기`가 온보딩으로 이어진다.
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
import { sendEmailVerificationCode, verifyEmailVerificationCode } from '../../../api/auth';
import { useAuth } from '../../../contexts/authContext';
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
  EMAIL_VERIFICATION_TOKEN_INVALID: '이메일 인증이 만료됐어요. 인증번호를 다시 받아 주세요.',
};

/**
 * 인증 단계의 실패 안내.
 *
 * 만료 · 불일치 · 소진을 구분해야 사용자가 다음에 할 일을 안다. 불일치는 다시 입력하면
 * 되고 나머지 둘은 번호를 다시 받아야 한다(API-079 오류 응답). 시도 상한을 넘긴 `429`도
 * 번호가 폐기된 상태라 같은 안내를 쓴다.
 */
const CODE_MESSAGE: Record<string, string> = {
  VERIFICATION_CODE_MISMATCH: '인증번호가 달라요. 다시 확인해 주세요.',
  VERIFICATION_CODE_EXPIRED: '인증번호가 만료됐어요. 다시 받아 주세요.',
  VERIFICATION_CODE_EXHAUSTED: '확인 횟수를 넘겼어요. 인증번호를 다시 받아 주세요.',
  RATE_LIMITED: '확인 횟수를 넘겼어요. 인증번호를 다시 받아 주세요.',
  VALIDATION_FAILED: '인증번호는 6자리 숫자예요.',
};

/** 발송 단계의 실패 안내. 이 경로는 가입 여부를 숨기지 않는다(API-078 처리 규칙) */
const SEND_MESSAGE: Record<string, string> = {
  EMAIL_DUPLICATED: '이미 가입된 이메일이에요. 로그인하거나 다른 이메일을 써 주세요.',
  RATE_LIMITED: '인증번호 요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.',
  VALIDATION_FAILED: '이메일 형식을 확인해 주세요.',
};

/** 종이의 줄 순서. 닉네임 줄(1)은 처음부터 나와 있다 */
const LINE = {
  nickname: 1,
  birthDate: 2,
  gender: 3,
  email: 4,
  code: 5,
  password: 6,
  passwordConfirm: 7,
  submit: 8,
} as const;

/**
 * 이메일 인증 진행 상태.
 *
 * `verified`만 주소와 토큰을 함께 들고 있다. 확인을 마친 주소가 무엇이었는지 알아야 이메일
 * 칸을 잠그고, 가입은 그 토큰으로 보낸다.
 */
type Verification =
  { step: 'idle' } | { step: 'sent' } | { step: 'verified'; email: string; token: string };

export default function EmailSignupPage() {
  const navigate = useNavigate();
  const nicknameCheck = useNicknameCheck();
  // 가입 응답의 세션을 Context까지 올려야 다음 화면이 로그인 상태로 열린다
  const { completeEmailSignup } = useAuth();

  const [birthDate, setBirthDate] = useState('');
  const [gender, setGender] = useState<Gender | null>(null);
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [verification, setVerification] = useState<Verification>({ step: 'idle' });
  const [codeFailure, setCodeFailure] = useState<string | null>(null);
  const [codeNotice, setCodeNotice] = useState<string | null>(null);
  const [codePending, setCodePending] = useState(false);
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const verified = verification.step === 'verified';

  /** API-078. 다시 누르면 새 번호가 오고 이전 번호는 서버에서 무효화된다 */
  const requestCode = async () => {
    if (!email.trim()) {
      setCodeFailure('이메일을 입력해 주세요.');

      return;
    }

    setCodePending(true);
    setCodeFailure(null);
    setCodeNotice(null);

    try {
      await sendEmailVerificationCode(email.trim());
      setCode('');
      setVerification({ step: 'sent' });
      setCodeNotice('인증번호를 보냈어요. 10분 안에 입력해 주세요.');
    } catch (error) {
      setCodeFailure(messageOf(error, SEND_MESSAGE, '인증번호를 보내지 못했어요.'));
    } finally {
      setCodePending(false);
    }
  };

  /** API-079. 통과하면 가입에 쓸 토큰을 받아 두고 이메일 칸을 잠근다 */
  const confirmCode = async () => {
    setCodePending(true);
    setCodeFailure(null);
    setCodeNotice(null);

    const target = email.trim();

    try {
      const { emailVerificationToken } = await verifyEmailVerificationCode(target, code.trim());

      setVerification({ step: 'verified', email: target, token: emailVerificationToken });
      setCodeNotice('이메일 인증을 마쳤어요.');
    } catch (error) {
      setCodeFailure(messageOf(error, CODE_MESSAGE, '인증번호를 확인하지 못했어요.'));
    } finally {
      setCodePending(false);
    }
  };

  const submit = async () => {
    if (nicknameCheck.result !== 'available') {
      setFailure('닉네임 중복 확인을 해 주세요.');

      return;
    }

    /*
     * 가입은 토큰으로만 보낼 수 있다(API-001). 인증을 마치지 않았으면 서버가 거절하기
     * 전에 화면에서 막아 무엇이 남았는지 알려 준다.
     */
    if (verification.step !== 'verified') {
      setFailure('이메일 인증을 먼저 완료해 주세요.');

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
      await completeEmailSignup({
        emailVerificationToken: verification.token,
        password,
        nickname: nicknameCheck.nickname,
        birthDate,
        gender,
      });

      // 0.5는 이 표시가 있을 때만 그린다(업무 -610). 가입과 동시에 로그인된 상태다
      markSignupCompleted({ kind: 'email' });
      void navigate(path.signupComplete());
    } catch (error) {
      const failureCode = error instanceof ApiError ? error.code : '';

      // 확인과 가입 사이에 누가 먼저 가져갔다. 확인을 풀어 다시 고르게 한다
      if (failureCode === 'NICKNAME_DUPLICATED') {
        nicknameCheck.markDuplicated();
      }

      /*
       * 토큰이 만료·소진됐으면 인증부터 다시 해야 한다. 이메일 칸을 풀어 주지 않으면
       * 사용자가 같은 화면에서 할 수 있는 일이 없다.
       */
      if (failureCode === 'EMAIL_VERIFICATION_TOKEN_INVALID') {
        setVerification({ step: 'idle' });
        setCode('');
        setCodeNotice(null);
      }

      setFailure(messageOf(error, MESSAGE, '가입하지 못했어요. 잠시 후 다시 시도해 주세요.'));
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
          <EmailLine
            value={email}
            locked={verified}
            pending={codePending}
            onChange={(next) => {
              setEmail(next);
              // 주소를 고치면 앞서 받은 번호는 그 주소의 것이 아니다
              setVerification({ step: 'idle' });
              setCode('');
              setCodeFailure(null);
              setCodeNotice(null);
            }}
            onRequestCode={() => void requestCode()}
          />
        </SignupPaperLine>

        <SignupPaperLine line={LINE.code}>
          <CodeLine
            value={code}
            onChange={setCode}
            onConfirm={() => void confirmCode()}
            verified={verified}
            pending={codePending}
            failure={codeFailure}
            notice={codeNotice}
          />
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

function EmailLine({
  value,
  locked,
  pending,
  onChange,
  onRequestCode,
}: {
  value: string;
  locked: boolean;
  pending: boolean;
  onChange: (v: string) => void;
  onRequestCode: () => void;
}) {
  const { feed } = useSignupPaper();

  return (
    <TextField
      label="이메일"
      type="email"
      placeholder="이메일"
      autoComplete="email"
      value={value}
      readOnly={locked}
      action={{
        label: locked ? '인증 완료' : '인증요청',
        disabled: locked || pending,
        onClick: () => {
          onRequestCode();
          feed(LINE.code);
        },
      }}
      onChange={(event) => {
        onChange(event.target.value);
        feed(LINE.code);
      }}
    />
  );
}

/**
 * 인증번호 줄.
 *
 * 확인을 마치면 입력과 `확인`을 잠근다. 같은 번호로 토큰을 두 번 받을 수 없고(API-079),
 * 다시 확인할 이유도 없다. 안내는 성공(`status`)과 실패(`alert`)를 나눠 보조기술이 구분해
 * 읽게 한다.
 */
function CodeLine({
  value,
  onChange,
  onConfirm,
  verified,
  pending,
  failure,
  notice,
}: {
  value: string;
  onChange: (v: string) => void;
  onConfirm: () => void;
  verified: boolean;
  pending: boolean;
  failure: string | null;
  notice: string | null;
}) {
  const { feed } = useSignupPaper();

  return (
    <>
      <TextField
        label="인증번호"
        type="text"
        inputMode="numeric"
        maxLength={6}
        placeholder="인증번호 6자리"
        autoComplete="one-time-code"
        value={value}
        readOnly={verified}
        error={failure ?? undefined}
        action={{
          label: '확인',
          disabled: verified || pending || value.trim().length === 0,
          onClick: () => {
            onConfirm();
            feed(LINE.password);
          },
        }}
        onChange={(event) => {
          onChange(event.target.value);
          feed(LINE.password);
        }}
      />

      {notice ? (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      ) : null}
    </>
  );
}

/** 서버가 준 코드에 화면 문구가 있으면 그것을, 없으면 서버 문구를, 그것도 없으면 기본값을 쓴다 */
function messageOf(error: unknown, table: Record<string, string>, fallback: string): string {
  if (!(error instanceof ApiError)) {
    return fallback;
  }

  return table[error.code] ?? error.message ?? fallback;
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
