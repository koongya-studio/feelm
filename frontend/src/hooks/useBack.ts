/*
 * 뒤로 가기 (업무 S15P21E106-610).
 *
 * 화면의 뒤로 가기는 `navigate(-1)`이었다. 앱 안에서 이동해 왔으면 맞지만, 새 탭이나 외부
 * 링크(알림 · 공유)로 바로 들어온 사람에게는 이전 항목이 앱 밖이거나 `about:blank`라서
 * 뒤로 가기가 앱을 떠나 버렸다.
 *
 * react-router는 자기가 만든 히스토리 항목마다 `history.state.idx`를 매긴다(첫 항목이 0).
 * 그 값이 0보다 크면 앞에 **이 앱의** 항목이 있다는 뜻이라 히스토리로 돌아가고, 아니면
 * 화면이 정한 대체 경로로 간다. `history.length`는 보지 않는다 — 다른 사이트의 항목까지
 * 세므로 외부에서 들어온 경우를 가르지 못한다.
 *
 * 대체 경로는 `replace`다. 직접 들어온 항목 위에 하나 더 쌓으면 브라우저 뒤로 가기가 그
 * 화면으로 되돌아온다.
 */
import { useCallback } from 'react';
import { useNavigate } from 'react-router';

/** 앞에 이 앱의 히스토리 항목이 있는지 */
export function canGoBack(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }

  const idx = (window.history.state as { idx?: unknown } | null)?.idx;

  /*
   * `BrowserRouter`는 첫 항목부터 `idx`를 매기므로 실제 앱에서는 늘 숫자다. 숫자가
   * 아닌 것은 라우터가 브라우저 히스토리를 쓰지 않는 경우(`MemoryRouter` 시험)뿐이라,
   * 그때는 판정할 근거가 없으니 예전처럼 히스토리로 돌아간다.
   */
  if (typeof idx !== 'number') {
    return true;
  }

  return idx > 0;
}

/**
 * 뒤로 가기 핸들러.
 *
 * @param fallbackTo 이 앱의 이전 항목이 없을 때 갈 경로. 화면이 자기 자리를 안다 —
 *   상세는 그 목록으로, 설정 하위는 설정으로, 알림은 MY로.
 */
export function useBack(fallbackTo: string): () => void {
  const navigate = useNavigate();

  return useCallback(() => {
    if (canGoBack()) {
      void navigate(-1);
    } else {
      void navigate(fallbackTo, { replace: true });
    }
  }, [navigate, fallbackTo]);
}
