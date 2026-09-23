/*
 * 5.7 수락자 추정 규칙 (업무 S15P21E106-304)
 *
 * **계약이 없어 내가 정한 규칙이다**(`joinedMember.ts`). 그런 규칙일수록 고정해 두어야
 * 알림 계약(8.1)이 생겼을 때 무엇을 바꿔야 하는지 드러난다.
 */
import { describe, expect, it } from 'vitest';
import { findJoinedMember } from './joinedMember';
import type { PartyMember } from '../../../types/party';

const ME = 1024;

function member(userId: number, nickname: string, role: 'OWNER' | 'MEMBER'): PartyMember {
  return { userId, nickname, avatarUrl: null, role, joinedAt: '2026-09-01T00:00:00Z' };
}

describe('findJoinedMember', () => {
  it('나를 뺀 마지막 사람을 방금 들어온 사람으로 본다', () => {
    const members = [
      member(2048, '무비버디', 'OWNER'),
      member(ME, '김난다', 'MEMBER'),
      member(2049, '필름러버', 'MEMBER'),
    ];

    expect(findJoinedMember(members, ME)?.nickname).toBe('필름러버');
  });

  it('내가 마지막에 있어도 나를 고르지 않는다', () => {
    const members = [member(2048, '무비버디', 'OWNER'), member(ME, '김난다', 'MEMBER')];

    expect(findJoinedMember(members, ME)?.nickname).toBe('무비버디');
  });

  it('나뿐이면 없다', () => {
    // 파티를 막 만들고 아무도 수락하지 않은 상태다. 화면은 `파티가 준비됐어요!`로 바꾼다
    expect(findJoinedMember([member(ME, '김난다', 'OWNER')], ME)).toBeNull();
  });

  it('빈 목록이면 없다', () => {
    expect(findJoinedMember([], ME)).toBeNull();
  });
});
