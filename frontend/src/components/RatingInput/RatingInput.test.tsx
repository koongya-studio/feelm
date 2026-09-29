import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import RatingInput from './index';

function RatingFixture({ disabled = false }: { disabled?: boolean }) {
  const [score, setScore] = useState(0);
  return <RatingInput value={score} onChange={setScore} disabled={disabled} />;
}

describe('RatingInput stars', () => {
  it('selects half stars by position and updates while dragging', () => {
    const { container } = render(<RatingFixture />);
    const stars = container.querySelector('span[class*="visual"]') as HTMLSpanElement;
    stars.getBoundingClientRect = () => ({ left: 100 }) as DOMRect;
    const slider = screen.getByRole('slider', { name: '별점' });

    fireEvent.pointerDown(stars, { pointerId: 1, pointerType: 'touch', clientX: 110 });
    expect(slider).toHaveValue('0.5');

    fireEvent.pointerMove(stars, { pointerId: 1, pointerType: 'touch', clientX: 125 });
    expect(slider).toHaveValue('1');

    fireEvent.pointerMove(stars, { pointerId: 1, pointerType: 'touch', clientX: 165 });
    expect(slider).toHaveValue('1.5');

    fireEvent.pointerMove(stars, { pointerId: 1, pointerType: 'touch', clientX: 500 });
    expect(slider).toHaveValue('5');

    fireEvent.pointerUp(stars, { pointerId: 1 });
    fireEvent.pointerMove(stars, { pointerId: 1, pointerType: 'touch', clientX: 110 });
    expect(slider).toHaveValue('5');
  });

  it('does not change a disabled rating', () => {
    const { container } = render(<RatingFixture disabled />);
    const stars = container.querySelector('span[class*="visual"]') as HTMLSpanElement;
    stars.getBoundingClientRect = () => ({ left: 0 }) as DOMRect;
    fireEvent.pointerDown(stars, { pointerId: 1, pointerType: 'touch', clientX: 125 });
    expect(screen.getByRole('slider', { name: '별점' })).toHaveValue('0');
  });
});
