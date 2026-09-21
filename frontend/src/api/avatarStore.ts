/*
 * 프로필 사진의 기기 저장소 (업무 S15P21E106-740).
 *
 * **서버에 자리가 없어서 이 기기에 둔다.** API-014 · 015 · 062 어디에도 사진 필드가 없고
 * 업로드 API · 이미지 저장소도 아직 없다(업무 -616). 한때 사진을 `mocks/sessionStore.ts`의
 * 메모리에만 두어 새로고침하거나 앱을 닫았다 열면 사라졌다.
 *
 * - `localStorage`에 **계정별 키**(`feelm.avatar.{userId}`)로 둔다. 다른 계정으로 로그인하면
 *   앞 계정의 사진이 보이지 않고, 같은 계정으로 돌아오면 다시 보인다
 * - 값은 7.2가 잘라 만든 512px JPEG `data URL`이다(`utils/avatarImage.ts`). 약 100KB라
 *   저장소 한도에 여유가 있다
 * - **이 기기에서만 보인다.** 다른 기기로 로그인하거나 다른 사람이 공개 프로필 · 파티에서
 *   보는 사진은 서버 저장이 생겨야 한다. 그때 이 파일을 지우고 `api/users.ts`가 서버를 부른다
 *
 * 쓰기는 실패를 올린다. 사생활 보호 모드나 저장소가 가득 찬 기기에서 조용히 넘어가면, 저장된
 * 줄 알았던 사진이 다음에 열 때 사라진다. 7.2는 실패하면 기존 사진을 두고 `다시 시도`를 준다.
 */

const KEY_PREFIX = 'feelm.avatar.';

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

/** 저장한 사진. 저장한 적 없거나 읽을 수 없으면 null이다 */
export function loadAvatar(userId: number): string | null {
  try {
    return storage()?.getItem(KEY_PREFIX + userId) || null;
  } catch {
    return null;
  }
}

/** 사진을 저장한다. `null`이면 지워 기본 이미지로 돌아간다. 저장하지 못하면 던진다 */
export function saveAvatar(userId: number, dataUrl: string | null): void {
  const store = storage();

  if (!store) throw new Error('이 기기에 사진을 저장할 수 없어요');

  if (dataUrl === null) store.removeItem(KEY_PREFIX + userId);
  else store.setItem(KEY_PREFIX + userId, dataUrl);
}
