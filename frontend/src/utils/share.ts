/*
 * 주소 · 파일 공유 (업무 S15P21E106-767 · -849).
 *
 * 공유 API가 계약에 없어 **브라우저가 한다.** 주소는 7.1 `프로필 공유` · 7.3 `공유하기`가,
 * 파일은 3.5 취향 리포트 `공유하기`가 쓴다.
 *
 * - **기기의 공유 메뉴는 휴대폰에서만 연다.** 데스크톱 Chrome · Edge에도 `navigator.share`가
 *   있어서, 한때 그것을 믿고 불렀다가 데스크톱에서는 메뉴도 안내도 없이 끝났다(2026-09-21 배포본
 *   점검). 운영체제 공유 창이 뜨지 않거나 곧바로 거절되는데 그 실패를 `취소`와 함께 삼켰다.
 *   손가락이 주 입력인 기기(`pointer: coarse`)만 메뉴를 쓰고, 나머지는 주소를 복사한다
 * - **메뉴가 취소가 아닌 이유로 실패하면 복사로 넘어간다.** 휴대폰에서도 권한 · 정책으로
 *   거절될 수 있다. 사용자가 닫은 것(`AbortError`)만 조용히 끝낸다
 * - **결과는 늘 돌려준다.** 되는 것처럼 보이고 아무 일도 없는 상태를 만들지 않는다 — 부른
 *   화면이 결과를 알린다
 */

/**
 * - `shared` 기기의 공유 메뉴로 보냈다
 * - `cancelled` 사용자가 공유 메뉴를 닫았다. 알리지 않는다
 * - `copied` 주소를 클립보드에 복사했다
 * - `failed` 공유도 복사도 못 했다
 */
export type ShareResult = 'shared' | 'cancelled' | 'copied' | 'failed';

/** 기기의 공유 메뉴를 쓸 기기인지. 휴대폰 · 태블릿이다 */
function prefersShareSheet(): boolean {
  if (typeof navigator.share !== 'function') return false;

  return typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches;
}

async function copy(url: string): Promise<ShareResult> {
  try {
    await navigator.clipboard.writeText(url);

    return 'copied';
  } catch {
    // 보안 문맥이 아니면 `clipboard`가 없고, 권한이 막혀도 여기로 온다
    return 'failed';
  }
}

/** 주소를 공유한다. 휴대폰은 기기의 공유 메뉴, 그 밖에는 복사다 */
export async function shareLink({
  title,
  url,
}: {
  title: string;
  url: string;
}): Promise<ShareResult> {
  if (prefersShareSheet()) {
    try {
      await navigator.share({ title, url });

      return 'shared';
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return 'cancelled';
    }
  }

  return copy(url);
}

/**
 * - `shared` 기기의 공유 메뉴로 보냈다
 * - `cancelled` 사용자가 공유 메뉴를 닫았다
 * - `blocked` 브라우저가 공유를 거절했다(`NotAllowedError`). 파일을 만드는 동안 누른 동작의
 *   효력이 끝났을 수 있어, 부른 화면이 한 번 더 눌러 달라고 할 수 있다
 * - `unavailable` 파일 공유를 쓸 수 없다. 부른 화면이 내려받기로 넘어간다
 */
export type FileShareResult = 'shared' | 'cancelled' | 'blocked' | 'unavailable';

/**
 * 파일을 기기의 공유 메뉴로 보낸다(업무 -849, 취향 리포트 이미지).
 *
 * 주소 공유와 같이 **휴대폰에서만 메뉴를 연다.** 데스크톱은 곧바로 `unavailable`이라 부른
 * 화면이 파일을 내려받게 한다. 파일을 받지 못하는 브라우저도 `canShare`로 걸러 같은 길로 간다.
 */
export async function shareFiles({
  title,
  files,
}: {
  title: string;
  files: File[];
}): Promise<FileShareResult> {
  if (!prefersShareSheet()) return 'unavailable';
  if (typeof navigator.canShare !== 'function' || !navigator.canShare({ files })) {
    return 'unavailable';
  }

  try {
    await navigator.share({ title, files });

    return 'shared';
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return 'cancelled';
    if (error instanceof DOMException && error.name === 'NotAllowedError') return 'blocked';

    return 'unavailable';
  }
}
