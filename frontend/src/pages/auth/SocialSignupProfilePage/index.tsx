/*
 * 0.3-A 소셜 가입 정보 입력
 *
 * 이동표
 * 콜백 ⇢ 0.3-A 연결된 계정이 없어 가입이 남은 경우
 * 0.3-A → 0.5 소셜 가입 완료
 *
 * **와이어프레임에 없이 새로 만든 화면이다(CR-23).** 이동표는 0.2 · 0.3에서 0.5로 곧장
 * 가지만 API-008이 닉네임 · 생년월일 · 성별을 필수로 받는다. 받을 자리가 없으면 가입을
 * 마칠 수 없어 한 단계를 만들었다(팀 결정 2026-09-16, S15P21E106-499).
 *
 * **닉네임은 빈 칸에서 시작한다**(업무 -851). API-007이 주는 `suggestedNickname`을 채워
 * 두었더니, 고치지 않고 중복 확인만 누른 사용자는 다음 줄(생년월일)이 열리지 않아 가입을
 * 마칠 수 없었다. 줄은 입력해야 열리므로 제안값을 쓰지 않는다.
 *
 * 이메일과 제공자 식별자는 `signupToken` 안에 있어 여기서 받지 않는다. 그래서 0.4와
 * 달리 이메일 · 비밀번호 줄이 없고, 종이는 세 줄로 끝난다.
 *
 * 토큰은 유효기간이 짧고(설정값 `feelm.jwt.signup-token-ttl`) 새로고침하면 사라지는
 * 값이라 Router state로만 들고 온다. 직접 주소를 열어 들어온 경우에는 가입을 이어 갈 수
 * 없으므로 0.2로 돌려보낸다(frontend-structure.md 6.3).
 */
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import GenderField from '../../../components/GenderField';
import NicknameField from '../../../components/NicknameField';
import ScreenHeader from '../../../components/ScreenHeader';
import SignupPaper, { SignupPaperHeading, SignupPaperLine } from '../../../components/SignupPaper';
import { useSignupPaper } from '../../../components/SignupPaper/paperContext';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { useAuth } from '../../../contexts/authContext';
import { useNicknameCheck } from '../../../hooks/useNicknameCheck';
import type { NicknameCheck } from '../../../hooks/useNicknameCheck';
import { path } from '../../../routes/paths';
import { SOCIAL_PROVIDER_LABEL } from '../../../utils/oauth';
import { toDateInputValue } from '../../../utils/date';
import { markSignupCompleted } from '../../../utils/signupCompletion';
import styles from './SocialSignupProfilePage.module.css';
import type { Gender, SocialProvider } from '../../../types/auth';

/** 콜백이 넘겨 주는 값 */
type State = {
  provider?: SocialProvider;
  signupToken?: string;
} | null;

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  SIGNUP_TOKEN_INVALID: '가입 시간이 지났어요. 소셜 로그인부터 다시 해 주세요.',
  EMAIL_DUPLICATED:
    '이미 같은 이메일로 가입된 계정이 있어요. 이메일로 로그인한 뒤 소셜 계정을 연결해 주세요.',
  NICKNAME_DUPLICATED: '이미 사용 중인 닉네임이에요.',
  OAUTH_ACCOUNT_ALREADY_LINKED: '이 소셜 계정은 이미 다른 계정에 연결돼 있어요.',
};

/** 종이의 줄 순서. 닉네임 줄(1)은 처음부터 나와 있다 */
const LINE = {
  nickname: 1,
  birthDate: 2,
  gender: 3,
  submit: 4,
} as const;

export default function SocialSignupProfilePage() {
  const navigate = useNavigate();
  const { state } = useLocation() as { state: State };
  const { completeSocialSignup } = useAuth();

  const signupToken = state?.signupToken;
  const provider = state?.provider;

  const nicknameCheck = useNicknameCheck();
  const [birthDate, setBirthDate] = useState('');
  const [gender, setGender] = useState<Gender | null>(null);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  /* 토큰 없이 들어오면 가입을 이어 갈 수 없다. 빈 화면을 보여 주지 않고 되돌린다 */
  useEffect(() => {
    if (!signupToken) {
      void navigate(path.signupMethod(), { replace: true });
    }
  }, [navigate, signupToken]);

  if (!signupToken) {
    return null;
  }

  const submit = async () => {
    if (nicknameCheck.result !== 'available') {
      setFailure('닉네임 중복 확인을 해 주세요.');

      return;
    }

    if (!birthDate) {
      setFailure('생년월일을 입력해 주세요.');

      return;
    }

    if (!gender) {
      setFailure('성별을 선택해 주세요.');

      return;
    }

    setPending(true);
    setFailure(null);

    try {
      await completeSocialSignup({
        signupToken,
        nickname: nicknameCheck.nickname,
        birthDate,
        gender,
      });

      /*
       * 0.5는 가입이 실제로 끝났다는 표시가 있을 때만 그려진다(업무 -610). 소셜 가입은
       * 곧바로 `ACTIVE`라 인증 안내 없이 `시작하기`를 보여 준다(API-008 처리 규칙).
       */
      markSignupCompleted({ kind: 'social' });
      void navigate(path.signupComplete(), { replace: true });
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
      <ScreenHeader title="소셜 계정으로 가입" showBack fallbackTo={path.signupMethod()} />

      <SignupPaper onSubmit={() => void submit()}>
        <SignupPaperHeading eyebrow="CREATE YOUR FEELM" title="조금만 더 알려 주세요">
          <p className={styles.lead}>
            {provider ? `${SOCIAL_PROVIDER_LABEL[provider]} 계정으로 가입해요. ` : ''}
            이메일과 비밀번호는 필요하지 않아요.
          </p>
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
      max={toDateInputValue()}
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
        feed(LINE.submit);
      }}
    />
  );
}
