/*
 * 4.2 결과 화면 되찾기 (업무 S15P21E106-634)
 *
 * 결과에서 2.4 상세로 가기 직전에 **받아 둔 결과와 스크롤 위치**를 적어 둔다. 뒤로 오면
 * 주소의 조건이 같을 때만 그대로 되살린다 — 다시 부르지 않으므로 `더 보기`로 이어 받은
 * 뒷장까지 남고, 스크롤도 보던 자리로 돌아간다.
 *
 * 탭 안에서만 산다(`sessionStorage`). 저장소를 쓸 수 없는 환경이면 조용히 건너뛰고, 그때는
 * 주소의 조건으로 첫 장을 다시 받는다. 한 번 되살리면 지운다 — 같은 화면에 다시 들어온 것이
 * 아니라 뒤로 온 것일 때만 쓰기 위해서다.
 */
const KEY = 'feelm.search.snapshot';

type Snapshot<T> = {
  /** 저장할 때의 주소 질의. 뒤로 온 주소와 같아야 되살린다 */
  query: string;
  state: T;
  scrollTop: number;
};

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

export function saveSnapshot<T>(query: string, state: T, scrollTop: number): void {
  try {
    storage()?.setItem(KEY, JSON.stringify({ query, state, scrollTop } satisfies Snapshot<T>));
  } catch {
    // 저장소가 가득 찼거나 막혀 있으면 되찾기만 포기한다
  }
}

/** 주소 질의가 같으면 꺼내고 지운다. 다르면 `null`이다 */
export function takeSnapshot<T>(query: string): { state: T; scrollTop: number } | null {
  try {
    const raw = storage()?.getItem(KEY);

    if (!raw) return null;

    storage()?.removeItem(KEY);

    const saved = JSON.parse(raw) as Snapshot<T>;

    return saved.query === query ? { state: saved.state, scrollTop: saved.scrollTop } : null;
  } catch {
    return null;
  }
}
