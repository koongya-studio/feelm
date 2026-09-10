/*
 * 0.4 이메일 회원가입 정보 입력
 *
 * 이동표
 * 0.4 → 0.5 이메일 회원가입 완료
 * 0.4 ⇢ 0.3 소셜 계정으로 가입 선택
 *
 * 입력 순서는 와이어프레임 0.4 그대로다. 닉네임 · 나이 · 성별 · 이메일(인증요청) ·
 * 인증번호 6자리(확인) · 비밀번호 · 비밀번호 재확인 · 소셜 계정으로 가입.
 *
 * **와이어프레임 0.4는 이전 팔레트로 그려져 있다.** 옥스블러드(`#4A0D0D`) 테두리와
 * 원형 소셜 표식은 폐기된 「암실」 방향의 잔재이고(`design-concept.md` 4장), 화면
 * 아래쪽은 벡터가 아니라 이미지 한 장이라 제출 버튼이 그 안에 들어 있다. 구성과 문구는
 * 그대로 두고 표현만 확정 방향으로 옮겼으며, 제출 버튼은 이동표의 `이메일 회원가입 완료`
 * 를 문구로 삼아 검정 알약으로 두었다.
 *
 * `[?]` 유효성 규칙은 와이어프레임에 없다. 인증번호 자리수(6)와 0.7-A가 명시한 비밀번호
 * 규칙만 반영했고 나머지는 검사하지 않는다. 계약(API)이 정해지면 그때 맞춘다.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { path } from '../../../routes/paths';
import { PASSWORD_HINT } from '../password';
import styles from './EmailSignupPage.module.css';

/** 와이어프레임 0.4의 성별 선택지 */
const GENDERS = ['여성', '남성', '선택 안 함'] as const;
type Gender = (typeof GENDERS)[number];

/** 와이어프레임 0.4 `인증번호 6자리` */
const CODE_LENGTH = 6;

export default function EmailSignupPage() {
  const navigate = useNavigate();
  const [gender, setGender] = useState<Gender | null>(null);
  const [codeRequested, setCodeRequested] = useState(false);

  return (
    <>
      <ScreenHeader title="회원가입" showBack />

      <form
        className={styles.page}
        onSubmit={(event) => {
          event.preventDefault();
          void navigate(path.signupComplete());
        }}
      >
        <TextField label="닉네임" placeholder="닉네임" autoComplete="nickname" />

        <TextField label="나이" placeholder="나이" inputMode="numeric" />

        <fieldset className={styles.genders}>
          <legend className={styles.legend}>성별</legend>

          {GENDERS.map((option) => (
            <button
              key={option}
              type="button"
              className={styles.gender}
              aria-pressed={gender === option}
              onClick={() => setGender(option)}
            >
              {option}
            </button>
          ))}
        </fieldset>

        <TextField
          label="이메일"
          type="email"
          placeholder="이메일"
          autoComplete="email"
          action={{ label: '인증요청', onClick: () => setCodeRequested(true) }}
        />

        <TextField
          label="인증번호"
          inputMode="numeric"
          maxLength={CODE_LENGTH}
          placeholder={`인증번호 ${CODE_LENGTH}자리`}
          disabled={!codeRequested}
          action={{ label: '확인', disabled: !codeRequested }}
        />

        <TextField
          label="비밀번호"
          type="password"
          placeholder="비밀번호"
          autoComplete="new-password"
          hint={PASSWORD_HINT}
        />

        <TextField
          label="비밀번호 재확인"
          type="password"
          placeholder="비밀번호 재확인"
          autoComplete="new-password"
        />

        <button
          type="button"
          className={styles.social}
          onClick={() => void navigate(path.signupSocial())}
        >
          소셜 계정으로 가입
        </button>

        <div className={styles.submit}>
          <Button type="submit">가입하기</Button>
        </div>
      </form>
    </>
  );
}
