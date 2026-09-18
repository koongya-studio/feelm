/*
 * 프로필 사진 고르기 · 자르기 계산 (업무 S15P21E106-616)
 *
 * 자르기 화면은 jsdom에서 사진을 그리지 못해 화면 시험으로는 잘린 결과를 볼 수 없다.
 * 원 안에 보이는 곳이 원본의 어디인지는 이 계산이 정하므로 여기서 고정한다.
 */
import { describe, expect, it } from 'vitest';
import { AVATAR_MAX_BYTES, avatarFileProblem, clampCrop, cropSourceRect } from './avatarImage';

const STAGE = 256;

function file(type: string, size = 1024) {
  const value = new File(['x'], 'photo', { type });
  Object.defineProperty(value, 'size', { value: size });

  return value;
}

describe('avatarFileProblem', () => {
  it('JPG · PNG · WEBP만 받는다', () => {
    expect(avatarFileProblem(file('image/jpeg'))).toBeNull();
    expect(avatarFileProblem(file('image/png'))).toBeNull();
    expect(avatarFileProblem(file('image/webp'))).toBeNull();
    expect(avatarFileProblem(file('image/heic'))).toMatch(/JPG, PNG, WEBP/);
    expect(avatarFileProblem(file('application/pdf'))).toMatch(/JPG, PNG, WEBP/);
  });

  it('10MB를 넘으면 거절한다', () => {
    expect(avatarFileProblem(file('image/png', AVATAR_MAX_BYTES))).toBeNull();
    expect(avatarFileProblem(file('image/png', AVATAR_MAX_BYTES + 1))).toMatch(/10MB/);
  });
});

describe('자르기 계산', () => {
  it('처음에는 짧은 변이 원을 채우고 가운데를 자른다', () => {
    // 600x400 가로 사진. 세로 400이 원 지름이 되고 가로는 가운데 400만 남는다
    expect(cropSourceRect({ zoom: 1, offsetX: 0, offsetY: 0 }, 600, 400, STAGE)).toEqual({
      x: 100,
      y: 0,
      size: 400,
    });
  });

  it('두 배로 키우면 가운데의 절반만 남는다', () => {
    const rect = cropSourceRect({ zoom: 2, offsetX: 0, offsetY: 0 }, 400, 400, STAGE);

    expect(rect).toEqual({ x: 100, y: 100, size: 200 });
  });

  it('원 밖으로 비게 옮길 수 없다', () => {
    // 1배에서 세로는 여유가 없고 가로는 (600 * 0.64 - 256) / 2 = 64px까지다
    const clamped = clampCrop({ zoom: 1, offsetX: 500, offsetY: -500 }, 600, 400, STAGE);

    expect(clamped.offsetX).toBeCloseTo(64);
    expect(clamped.offsetY).toBeCloseTo(0);
    // 끝까지 옮기면 원본의 왼쪽 끝부터 잘린다
    expect(cropSourceRect(clamped, 600, 400, STAGE).x).toBeCloseTo(0);
  });
});
