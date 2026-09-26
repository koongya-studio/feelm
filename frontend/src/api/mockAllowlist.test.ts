/*
 * API 계층의 목업 승인 목록 (업무 S15P21E106-778).
 *
 * **`src/api/`에서 목업을 쓰는 곳은 여기 적힌 것뿐이다.** 한때 파티 추천(API-058)은 백엔드가
 * 생긴 뒤에도 함수가 목업을 돌려주었고, 주석은 여전히 "백엔드에 없다"였다(업무 -705에서 정리).
 * 어느 함수가 목업인지가 파일마다의 주석에만 있어서, 백엔드가 생겨도 알아챌 길이 없었다.
 *
 * 이 시험은 두 방향으로 막는다.
 * - **목록에 없는 목업이 새로 생기면 실패한다.** `./mockTransport`나 `../mocks/*`를 새로
 *   import하려면 여기에 사유와 함께 적어야 한다
 * - **목업을 걷어냈는데 목록에 남아 있으면 실패한다.** 실제 API로 바꾼 사람이 목록에서도
 *   지우게 해, 이 표가 낡지 않게 한다
 *
 * 사유에는 **무엇이 없어서 목업인지**와 그것을 풀 업무를 적는다. 백엔드가 그 업무를 끝내면
 * 이 줄이 지워질 차례다.
 *
 * 시험 파일(`*.test.ts`)과 목업 전송 계층 자신(`mockTransport.ts`)은 보지 않는다.
 */
import { describe, expect, it } from 'vitest';

type Allowed = {
  /** 목업을 쓰는 까닭. 무엇이 없어서인지 적는다 */
  reason: string;
  /** 이 목업을 걷어낼 업무. 대응 API 자체가 계약에 없으면 비운다 */
  until?: string;
};

/** 파일 → import 경로 → 사유 */
const ALLOWED: Record<string, Record<string, Allowed>> = {
  'mockAccount.ts': {
    '../mocks/user': {
      reason: '개발 서버 전용 목업 계정(`feelm.mockAccount`). 배포 빌드에서는 켜지지 않는다',
    },
  },
  'users.ts': {
    '../mocks/publicProfiles': {
      reason:
        '내 프로필(7.1 · 7.2 · 7.2-A)의 키워드 · 필름 · 소개 기준값. 내 publicId를 알려 주는 API가 없어 내 공개 프로필(API-062)을 부를 수 없다(업무 -810)',
    },
    '../mocks/sessionStore': {
      reason: '자기소개 · 항목별 공개 여부가 계약에 아예 없어 세션에만 둔다(업무 -616)',
    },
    './mockTransport': {
      reason:
        '개발용 목업 계정의 내 프로필 응답과, 계약이 없는 소개 · 공개 항목 저장의 검증 오류(업무 -616)',
    },
  },
  'taste.ts': {
    '../mocks/taste': {
      reason:
        '개발 서버 전용 목업 계정의 취향 분석. 백엔드 없이 7.1 `내 취향` 구역을 볼 수 있게 한다. 목업 세션이 아니면 실제 API-040을 부른다(업무 -919)',
    },
    './mockTransport': {
      reason: '위 목업 취향 응답을 목업 전송 계층으로 돌려주기 위해서다(업무 -919)',
    },
  },
  'watch.ts': {
    '../mocks/watch': {
      reason: '2.6 `이번 주 +N`에 대응하는 응답 필드가 없어 고정 기준값을 쓴다(업무 -599)',
    },
  },
};

/** 목업 계층을 가리키는 import 경로인지 */
function isMockSpecifier(specifier: string): boolean {
  return specifier === './mockTransport' || specifier.startsWith('../mocks/');
}

/** 한 파일이 import하는 목업 경로. 정적 import와 동적 `import()`를 모두 본다 */
function mockImports(source: string): string[] {
  const specifiers = [
    ...source.matchAll(/\bfrom\s+'([^']+)'/g),
    ...source.matchAll(/\bimport\(\s*'([^']+)'\s*\)/g),
  ].map((match) => match[1]);

  return [...new Set(specifiers.filter(isMockSpecifier))].sort();
}

const sources = import.meta.glob<string>(['./*.ts', '!./*.test.ts', '!./mockTransport.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

/** 실제로 목업을 import하는 파일 → 경로들 */
const actual = Object.fromEntries(
  Object.entries(sources)
    .map(([path, source]) => [path.replace('./', ''), mockImports(source)] as const)
    .filter(([, specifiers]) => specifiers.length > 0),
);

describe('API 계층의 목업 승인 목록', () => {
  it('파일을 실제로 읽는다 — 비어 있으면 아래 단정은 아무것도 지키지 않는다', () => {
    expect(Object.keys(sources)).toContain('./client.ts');
    expect(Object.keys(sources)).not.toContain('./mockTransport.ts');
    expect(Object.keys(sources)).not.toContain('./client.test.ts');
  });

  it('목업을 쓰는 곳이 승인 목록과 정확히 같다', () => {
    const allowed = Object.fromEntries(
      Object.entries(ALLOWED).map(([file, entries]) => [file, Object.keys(entries).sort()]),
    );

    // 다르면: 새 목업이면 ALLOWED에 사유와 함께 적고, 목업을 걷어냈으면 ALLOWED에서 지운다
    expect(actual).toEqual(allowed);
  });

  it('승인된 목업마다 사유가 적혀 있다', () => {
    for (const entries of Object.values(ALLOWED)) {
      for (const { reason } of Object.values(entries)) {
        expect(reason.trim()).not.toBe('');
      }
    }
  });
});

describe('목업 import 찾기', () => {
  it('여러 줄 import · 동적 import를 잡고, 목업이 아닌 경로는 거른다', () => {
    const source = [
      "import { request } from './client';",
      'import {',
      '  mockA,',
      '  mockB,',
      "} from '../mocks/parties';",
      "const lazy = () => import('./mockTransport');",
    ].join('\n');

    expect(mockImports(source)).toEqual(['../mocks/parties', './mockTransport']);
  });
});
