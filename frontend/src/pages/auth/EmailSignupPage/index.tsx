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
 * **닉네임은 `중복 확인`(API-016)을 통과해야 가입할 수 있다.** 확인한 뒤 닉네임을 고치면
 * 확인이 풀린다. 결과를 확인한 닉네임과 함께 들고 있다가 지금 값과 다르면 없는 것으로 본다.
 * 팀 결정 2026-09-14(S15P21E106-319).
 *
 * **타자기에서 종이가 나오는 연출** (팀 결정 2026-09-14, S15P21E106-319). 화면 아래에
 * 코드로 그린 타자기가 있고 입력 칸은 그 롤러에서 올라오는 종이 위에 있다.
 * - 처음에는 제목과 닉네임 줄만 나와 있다. 한 줄에 무엇이든 입력하면 다음 줄이 롤러에서
 *   밀려 나온다. 나온 줄은 입력을 지워도 다시 들어가지 않는다
 * - 글자를 칠 때마다 종이가 살짝 튀고 타자기 키 하나가 눌린다
 * - 색과 배경은 확정 톤 그대로다. 참고 시안의 와인색은 쓰지 않는다(`design-concept.md` 3장)
 */
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { signup } from '../../../api/auth';
import { checkNicknameAvailability, isValidNickname } from '../../../api/users';
import { path } from '../../../routes/paths';
import { PASSWORD_HINT } from '../password';
import styles from './EmailSignupPage.module.css';
import Typewriter from './Typewriter';
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

type NicknameResult = 'available' | 'duplicated' | 'invalid' | 'failed';

/** 형식 규칙은 가입(API-001) · 확인(API-016)과 같다 */
const NICKNAME_HINT = '한글·영문·숫자·_ 2~10자';

const NICKNAME_MESSAGE: Record<NicknameResult, string> = {
  available: '사용할 수 있는 닉네임이에요.',
  duplicated: '이미 사용 중인 닉네임이에요.',
  invalid: `닉네임은 ${NICKNAME_HINT}로 입력해 주세요.`,
  failed: '확인하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

/** 종이의 줄 순서. 닉네임 줄(1)은 처음부터 나와 있다 */
const LINE = {
  birthDate: 2,
  gender: 3,
  email: 4,
  password: 5,
  passwordConfirm: 6,
  submit: 7,
} as const;

/** 줄이 열리는 시간. `--motion-emphasized`와 같다 */
const FEED_MS = 420;

function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export default function EmailSignupPage() {
  const navigate = useNavigate();

  const scrollerRef = useRef<HTMLDivElement>(null);
  const paperRef = useRef<HTMLFormElement>(null);
  const releaseKey = useRef<number | undefined>(undefined);

  const [fed, setFed] = useState(1);
  const [typed, setTyped] = useState<string | null>(null);

  /* 나온 줄은 다시 들어가지 않는다 */
  const feed = (line: number) => setFed((current) => Math.max(current, line));

  /* 글자 하나를 친다. 키가 눌리고 종이가 롤러에 한 번 튄다 */
  const type = (data: string | null) => {
    if (!data) {
      return;
    }

    setTyped(data[data.length - 1]);
    window.clearTimeout(releaseKey.current);
    releaseKey.current = window.setTimeout(() => setTyped(null), 110);

    if (!prefersReducedMotion()) {
      // jsdom에는 Web Animations가 없다
      paperRef.current?.animate?.(
        [{ transform: 'translateY(-3px)' }, { transform: 'translateY(0)' }],
        { duration: 140, easing: 'cubic-bezier(0.2, 0, 0, 1)' },
      );
    }
  };

  useEffect(() => () => window.clearTimeout(releaseKey.current), []);

  /*
   * 줄이 나오면 롤러가 제자리에 있도록 아래 끝을 따라간다. 종이가 화면보다 길어진 뒤에도
   * 새 줄이 올라오는 모습이 보여야 한다.
   */
  useEffect(() => {
    if (fed === 1) {
      return;
    }

    const timer = window.setTimeout(() => {
      const scroller = scrollerRef.current;

      scroller?.scrollTo?.({
        top: scroller.scrollHeight,
        behavior: prefersReducedMotion() ? 'auto' : 'smooth',
      });
    }, FEED_MS);

    return () => window.clearTimeout(timer);
  }, [fed]);

  const [nickname, setNickname] = useState('');
  const [nicknameCheck, setNicknameCheck] = useState<{
    nickname: string;
    result: NicknameResult;
  } | null>(null);
  const [checkingNickname, setCheckingNickname] = useState(false);
  const [birthDate, setBirthDate] = useState('');
  const [gender, setGender] = useState<Gender | null>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  /* 확인한 뒤 닉네임을 고쳤으면 그 결과는 지금 값의 것이 아니다 */
  const nicknameResult = nicknameCheck?.nickname === nickname ? nicknameCheck.result : null;

  const checkNickname = async () => {
    const target = nickname;

    // 형식이 틀린 값은 서버도 `INVALID_FORMAT`으로 답하므로 부르지 않는다
    if (!isValidNickname(target)) {
      setNicknameCheck({ nickname: target, result: 'invalid' });

      return;
    }

    setCheckingNickname(true);

    try {
      const { available, reason } = await checkNicknameAvailability(target);
      const result: NicknameResult = available
        ? 'available'
        : reason === 'INVALID_FORMAT'
          ? 'invalid'
          : 'duplicated';

      setNicknameCheck({ nickname: target, result });
    } catch {
      setNicknameCheck({ nickname: target, result: 'failed' });
    } finally {
      setCheckingNickname(false);
    }
  };

  const submit = async () => {
    if (nicknameResult !== 'available') {
      setFailure('닉네임 중복 확인을 해 주세요.');

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
      await signup({ email, password, nickname, birthDate, gender });

      // 0.5가 인증 메일 안내와 재발송에 쓴다
      void navigate(path.signupComplete(), { state: { email } });
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';

      // 확인과 가입 사이에 누가 먼저 가져갔다. 확인을 풀어 다시 고르게 한다
      if (code === 'NICKNAME_DUPLICATED') {
        setNicknameCheck({ nickname, result: 'duplicated' });
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
      <ScreenHeader title="회원가입" showBack />

      <div ref={scrollerRef} className={styles.page}>
        <form
          ref={paperRef}
          className={styles.paper}
          onInput={(event) => type((event.nativeEvent as InputEvent).data)}
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <div className={styles.heading}>
            <p className={styles.eyebrow}>CREATE YOUR FEELM</p>
            <h2 className={styles.title}>지금 당신의 필름을 시작하세요</h2>
            <button
              type="button"
              className={styles.social}
              onClick={() => void navigate(path.signupSocial())}
            >
              소셜 계정으로 가입
            </button>
          </div>

          <Line fed>
            <TextField
              label="닉네임"
              placeholder="닉네임"
              autoComplete="nickname"
              value={nickname}
              onChange={(event) => {
                setNickname(event.target.value);
                feed(LINE.birthDate);
              }}
              action={{
                label: checkingNickname ? '확인 중…' : '중복 확인',
                onClick: () => void checkNickname(),
                disabled: checkingNickname || nickname === '' || nicknameResult === 'available',
              }}
              hint={nicknameResult === 'available' ? NICKNAME_MESSAGE.available : NICKNAME_HINT}
              error={
                nicknameResult && nicknameResult !== 'available'
                  ? NICKNAME_MESSAGE[nicknameResult]
                  : undefined
              }
            />
          </Line>

          <Line fed={fed >= LINE.birthDate}>
            <TextField
              label="생년월일"
              type="date"
              placeholder="생년월일"
              autoComplete="bday"
              value={birthDate}
              onChange={(event) => {
                setBirthDate(event.target.value);
                feed(LINE.gender);
              }}
            />
          </Line>

          <Line fed={fed >= LINE.gender}>
            <fieldset className={styles.genders}>
              <legend className={styles.legend}>성별</legend>

              {GENDERS.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className={styles.gender}
                  aria-pressed={gender === option.value}
                  onClick={() => {
                    setGender(option.value);
                    type(option.label[0]);
                    feed(LINE.email);
                  }}
                >
                  {option.label}
                </button>
              ))}
            </fieldset>
          </Line>

          <Line fed={fed >= LINE.email}>
            <TextField
              label="이메일"
              type="email"
              placeholder="이메일"
              autoComplete="email"
              value={email}
              onChange={(event) => {
                setEmail(event.target.value);
                feed(LINE.password);
              }}
            />
          </Line>

          <Line fed={fed >= LINE.password}>
            <TextField
              label="비밀번호"
              type="password"
              placeholder="비밀번호"
              autoComplete="new-password"
              hint={PASSWORD_HINT}
              value={password}
              onChange={(event) => {
                setPassword(event.target.value);
                feed(LINE.passwordConfirm);
              }}
            />
          </Line>

          <Line fed={fed >= LINE.passwordConfirm}>
            <TextField
              label="비밀번호 재확인"
              type="password"
              placeholder="비밀번호 재확인"
              autoComplete="new-password"
              value={passwordConfirm}
              onChange={(event) => {
                setPasswordConfirm(event.target.value);
                feed(LINE.submit);
              }}
            />
          </Line>

          <Line fed={fed >= LINE.submit}>
            {failure ? (
              <p className={styles.failure} role="alert">
                {failure}
              </p>
            ) : null}

            <Button type="submit" disabled={pending}>
              {pending ? '가입 중…' : '가입하기'}
            </Button>
          </Line>
        </form>

        <Typewriter typed={typed} />
      </div>
    </>
  );
}

/**
 * 종이의 한 줄. 아직 나오지 않은 줄은 높이가 0이고 `inert`라 초점도 가지 않는다.
 *
 * 높이는 `grid-template-rows` 0fr → 1fr로 연다. 줄마다 높이를 재지 않아도 오류 문구가
 * 붙어 줄이 길어지면 그대로 따라간다.
 */
function Line({ fed, children }: { fed: boolean; children: ReactNode }) {
  return (
    <div className={styles.line} data-fed={fed} inert={!fed}>
      <div className={styles.lineClip}>
        <div className={styles.lineBody}>{children}</div>
      </div>
    </div>
  );
}
