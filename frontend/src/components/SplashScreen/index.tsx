/*
 * 스플래시 화면 (업무 S15P21E106-331).
 *
 * 라우트가 아니라 **첫 마운트에 한 번 뜨는 덮개**다. 홈이 `/`이므로 스플래시를
 * 라우트로 두면 진입 경로를 하나 빼앗기고, `/movies/5` 같은 딥링크로 들어온
 * 사람을 홈으로 돌려보내게 된다. 덮개로 두면 아래에서 원래 목적지가 그려지는
 * 동안 사진이 그것을 가린다.
 *
 * 사라지는 조건은 둘을 모두 만족할 때다.
 *
 * - **준비**: 배경 사진이 디코드되고 서체가 로드됐다. 스플래시는 로딩을 가리는
 *   화면이므로 자기가 로딩을 만들면 안 된다. 사진이 뜨기 전에 걷히면 회색 판이
 *   보이고, 서체 전에 걷히면 아래 화면 글자가 한 번 갈린다
 * - **최소 표시 시간**: 준비가 순간에 끝나도(두 번째 방문은 전부 캐시에 있다)
 *   화면이 깜빡이고 지나가지 않게 한다
 *
 * 사진 로드가 실패해도 준비된 것으로 본다. 배경이 없다고 앱 진입을 막을 수는
 * 없다.
 */
import { useEffect, useRef, useState } from 'react';
import splashPhoto from '../../assets/splash/feelm-splash.webp';
import styles from './SplashScreen.module.css';

/** 최소 표시 시간. 브랜드가 한 번 읽히는 길이이자 깜빡임을 막는 하한이다 */
const MIN_VISIBLE_MS = 1200;

export default function SplashScreen() {
  const [photoSettled, setPhotoSettled] = useState(false);
  const [fontsSettled, setFontsSettled] = useState(false);
  const [minElapsed, setMinElapsed] = useState(false);
  const [removed, setRemoved] = useState(false);
  const nodeRef = useRef<HTMLDivElement>(null);

  const leaving = photoSettled && fontsSettled && minElapsed;

  useEffect(() => {
    const timer = window.setTimeout(() => setMinElapsed(true), MIN_VISIBLE_MS);

    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    let cancelled = false;

    // 실패해도 진입을 막지 않는다. 서체는 fallback으로 그려진다
    void document.fonts.ready.then(
      () => {
        if (!cancelled) setFontsSettled(true);
      },
      () => {
        if (!cancelled) setFontsSettled(true);
      },
    );

    return () => {
      cancelled = true;
    };
  }, []);

  /*
   * 페이드가 끝난 뒤에 DOM에서 뺀다. 걷히는 시간은 CSS가 정하므로
   * (`--motion-emphasized`, reduced-motion에서 0ms) 여기서 숫자를 다시 적지 않고
   * 실제 적용된 값을 읽는다. 같은 값을 두 곳에 적으면 한쪽만 바뀐다.
   */
  useEffect(() => {
    if (!leaving) return;

    const node = nodeRef.current;
    const seconds =
      node === null ? 0 : Number.parseFloat(getComputedStyle(node).transitionDuration);
    const fadeMs = Number.isFinite(seconds) ? seconds * 1000 : 0;
    const timer = window.setTimeout(() => setRemoved(true), fadeMs);

    return () => window.clearTimeout(timer);
  }, [leaving]);

  if (removed) return null;

  return (
    <div ref={nodeRef} className={styles.splash} data-leaving={leaving} data-testid="splash">
      <img
        className={styles.photo}
        src={splashPhoto}
        alt="Feelm — YOUR TASTE ON FILM"
        fetchPriority="high"
        onLoad={() => setPhotoSettled(true)}
        onError={() => setPhotoSettled(true)}
      />
    </div>
  );
}
