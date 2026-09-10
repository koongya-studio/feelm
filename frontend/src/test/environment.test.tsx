/*
 * 테스트 환경이 화면과 같게 동작하는지 확인한다 (업무 S15P21E106-333).
 *
 * 이 파일은 화면 로직이 아니라 **환경 자체**를 검증한다. 완료 조건이 요구하는
 * 세 가지(CSS Modules · `import.meta.env` · react-router)가 실제로 동작하는지
 * 확인하는 자리다. 셋 중 하나라도 깨지면 다른 테스트들이 이유를 알기 어려운
 * 형태로 실패하므로 여기서 먼저 걸리게 한다.
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Link, useLocation } from 'react-router';
import { withRouter } from './render';
import styles from '../components/Button/Button.module.css';

describe('테스트 환경', () => {
  it('CSS Modules가 클래스 이름을 내보낸다', () => {
    /*
     * `css: true`가 없으면 이 import가 빈 객체가 되어, 클래스에 의존하는 조회가
     * 조용히 실패한다. 해시된 이름이라 값 자체는 단정하지 않고 존재만 본다.
     */
    expect(styles).toBeTypeOf('object');
    expect(Object.keys(styles).length).toBeGreaterThan(0);
    expect(styles.button).toBeTypeOf('string');
    expect(styles.button.length).toBeGreaterThan(0);
  });

  it('import.meta.env를 읽을 수 있다', () => {
    // Vite가 주입하는 값이다. Jest라면 별도 트랜스폼 없이는 여기서 터진다
    expect(import.meta.env).toBeTypeOf('object');
    expect(import.meta.env.MODE).toBeTypeOf('string');
    /*
     * Vitest는 테스트 실행 시 `TEST`를 켠다. 다만 **문자열 `'true'`로 들어온다**
     * (boolean이 아니다). 화면 코드에서 이 값을 조건으로 쓸 때 `=== true`로 비교하면
     * 항상 거짓이 되므로 여기에 적어 둔다.
     */
    expect(import.meta.env.TEST).toBe('true');
  });

  it('react-router의 Link와 useLocation이 동작한다', () => {
    function Probe() {
      const location = useLocation();

      return (
        <>
          <span data-testid="path">{location.pathname}</span>
          <Link to="/film">필름으로</Link>
        </>
      );
    }

    render(withRouter(<Probe />, '/home'));

    expect(screen.getByTestId('path')).toHaveTextContent('/home');
    expect(screen.getByRole('link', { name: '필름으로' })).toHaveAttribute('href', '/film');
  });

  it('jsdom에 없는 브라우저 기능을 setup이 채운다', () => {
    // `src/test/setup.ts`가 넣어 둔다. 없으면 화면 코드가 여기서 터진다
    expect(window.matchMedia).toBeTypeOf('function');
    expect(window.matchMedia('(prefers-reduced-motion: reduce)').matches).toBe(false);
    expect(window.ResizeObserver).toBeTypeOf('function');
  });
});
