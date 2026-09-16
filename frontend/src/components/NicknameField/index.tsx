/*
 * 닉네임 입력과 중복 확인 버튼.
 *
 * 상태는 `useNicknameCheck`가 들고 이 컴포넌트는 그 결과를 보여 준다. 0.4 이메일
 * 회원가입과 0.3-A 소셜 가입 정보 입력이 같은 모양을 쓴다(업무 S15P21E106-499).
 */
import TextField from '../TextField';
import type { NicknameCheck, NicknameResult } from '../../hooks/useNicknameCheck';

/** 형식 규칙은 가입(API-001 · API-008) · 확인(API-016)과 같다 */
const NICKNAME_HINT = '한글·영문·숫자·_ 2~10자';

const MESSAGE: Record<NicknameResult, string> = {
  available: '사용할 수 있는 닉네임이에요.',
  duplicated: '이미 사용 중인 닉네임이에요.',
  invalid: `닉네임은 ${NICKNAME_HINT}로 입력해 주세요.`,
  failed: '확인하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

type Props = {
  check: NicknameCheck;
  /** 입력이 생겼을 때. 종이의 다음 줄을 여는 데 쓴다 */
  onInput?: () => void;
};

export default function NicknameField({ check, onInput }: Props) {
  const { nickname, setNickname, result, checking } = check;

  return (
    <TextField
      label="닉네임"
      placeholder="닉네임"
      autoComplete="nickname"
      value={nickname}
      onChange={(event) => {
        setNickname(event.target.value);
        onInput?.();
      }}
      action={{
        label: checking ? '확인 중…' : '중복 확인',
        onClick: () => void check.check(),
        disabled: checking || nickname === '' || result === 'available',
      }}
      hint={result === 'available' ? MESSAGE.available : NICKNAME_HINT}
      error={result && result !== 'available' ? MESSAGE[result] : undefined}
    />
  );
}
