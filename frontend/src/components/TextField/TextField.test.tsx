/*
 * 날짜 칸의 placeholder (업무 S15P21E106-851)
 *
 * 브라우저는 `type="date"`에서 placeholder를 무시하고 `연도-월-일`을 대신 보여 준다.
 * 값이 없는 동안에는 칸 이름을 겹쳐 놓아 무엇을 받는 칸인지 보이게 한다.
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import TextField from './index';

describe('TextField', () => {
  it('값이 없는 날짜 칸에 칸 이름을 겹쳐 보여 준다', () => {
    render(<TextField label="생년월일" type="date" placeholder="생년월일" value="" readOnly />);

    /* 라벨이 이미 이름을 읽어 주므로 겹친 글자는 보조기술에서 감춘다 */
    const overlay = screen.getByText('생년월일', { selector: '[aria-hidden="true"]' });

    expect(overlay).toBeInTheDocument();
    expect(screen.getByLabelText('생년월일')).toHaveAttribute('data-empty', 'true');
  });

  it('값이 있으면 겹친 글자를 걷는다', () => {
    render(
      <TextField label="생년월일" type="date" placeholder="생년월일" value="1999-03-14" readOnly />,
    );

    expect(screen.queryByText('생년월일', { selector: '[aria-hidden="true"]' })).toBeNull();
    expect(screen.getByLabelText('생년월일')).not.toHaveAttribute('data-empty');
  });

  it('날짜가 아닌 칸은 브라우저 placeholder를 그대로 쓴다', () => {
    render(<TextField label="닉네임" placeholder="닉네임" value="" readOnly />);

    expect(screen.queryByText('닉네임', { selector: '[aria-hidden="true"]' })).toBeNull();
    expect(screen.getByLabelText('닉네임')).toHaveAttribute('placeholder', '닉네임');
  });
});
