/*
 * `backdrop-filter` 선언 순서 (업무 S15P21E106-868)
 *
 * 한 규칙에 `backdrop-filter`를 먼저, `-webkit-backdrop-filter`를 뒤에 쓰면 배포 빌드의 CSS
 * 압축(lightningcss)이 뒤의 접두사 선언이 앞을 덮는다고 보고 **접두사 없는 쪽을 지운다.** Chrome은
 * 접두사 선언을 읽지 않으므로 배포 화면에서만 흐림이 `none`이 된다. 개발 서버는 압축하지 않아
 * 화면으로는 드러나지 않는다 — 2.2 OTT 메뉴 뒤로 아래 카드 글자가 비친 것이 이것이었다.
 *
 * 그래서 `-webkit-backdrop-filter`를 먼저 쓰는지를 소스에서 고정한다.
 */
import { describe, expect, it } from 'vitest';

const sources = import.meta.glob<string>(['../**/*.css'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

/** 선언 블록마다 두 속성의 순서를 본다. 둘 다 있는데 접두사 없는 쪽이 앞이면 어긋난 것이다 */
function misordered(source: string): string[] {
  const found: string[] = [];

  for (const match of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const [, selector, body] = match;
    const unprefixed = body.search(/(^|[;\s])backdrop-filter\s*:/);
    const prefixed = body.search(/-webkit-backdrop-filter\s*:/);

    if (unprefixed !== -1 && prefixed !== -1 && unprefixed < prefixed) {
      found.push(selector.replace(/\/\*[\s\S]*?\*\//g, '').trim());
    }
  }

  return found;
}

describe('backdrop-filter 선언 순서', () => {
  it('CSS 파일을 실제로 읽는다 — 비어 있으면 아래 단정은 아무것도 지키지 않는다', () => {
    expect(Object.keys(sources)).toContain('../components/Menu/Menu.module.css');
  });

  it('두 속성을 함께 쓰는 규칙은 -webkit- 접두사를 먼저 쓴다', () => {
    const offenders = Object.entries(sources).flatMap(([path, source]) =>
      misordered(source).map((selector) => `${path} ${selector}`),
    );

    expect(offenders).toEqual([]);
  });

  it('어긋난 순서를 실제로 잡아낸다', () => {
    expect(
      misordered('.a { backdrop-filter: var(--b); -webkit-backdrop-filter: var(--b); }'),
    ).toEqual(['.a']);
    expect(
      misordered('.a { -webkit-backdrop-filter: var(--b); backdrop-filter: var(--b); }'),
    ).toEqual([]);
  });
});
