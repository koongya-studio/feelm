/*
 * 2.6 평가 영화 필름 추가 중 · 2.7 필름 추가 완료
 *
 * 한 라우트의 두 단계다(frontend-structure.md 4.2). 2.6은 추가 **전** 수치를,
 * 2.7은 추가 **후** 수치를 보여준다.
 *
 * 전환은 1.2초 후 자동이다. 와이어프레임에 버튼도 시간도 없어 임의로 정한 값이며
 * (팀 결정 2026-09-07) 확정되면 상수만 바꾼다. `[?]` screen-spec.md 2.6 미확정 항목.
 *
 * 평가 결과와 추가 전 수치는 2.5가 Router state로 넘긴다. 새로 고침이나 URL 직접 접근이면
 * 복원할 수 없으므로 `/film`으로 보낸다(frontend-structure.md 6.3).
 *
 * 평가 결과는 API-036 실제 응답이다(업무 -491). 프레임은 `frame`으로 만들어졌고 팝콘은
 * `popcorn`이 말한다. **영화에 대표 맛이 없으면 `popcorn.flavorCode`가 `null`이고 팝콘이
 * 어느 맛에도 담기지 않는다**(API-041 처리 규칙). 그때는 프레임만 추가됐다고 말하고 맛 ·
 * 버킷 줄을 그리지 않는다 — `null 맛 +1알` · `99알 → 100알`은 거짓이다.
 *
 * 맛 이름은 API-044의 `displayName`을 쓰고 장르명을 하드코딩하지 않는다
 * (screen-spec.md 2.7). 응답의 `flavorCode`가 실제 코드이므로 기준정보도 실제 API-044를
 * 부른다. 기준정보가 비어 있거나 실패하면 맛 이름 없이 `팝콘 +1알`로 적는다.
 *
 * `[?]` API-036 응답의 `popcorn.isNewFlavor`에 대응하는 표시가 와이어프레임에 없어
 * 사용하지 않는다.
 */
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import FilmStrip from '../../../components/FilmStrip';
import Poster from '../../../components/Poster';
import { HeartIcon, ResultBellIcon } from '../../../components/Icon';
import { getPopcornFlavors } from '../../../api/taste';
import { path } from '../../../routes/paths';
import styles from './RatingResultPage.module.css';
import type { PopcornFlavor } from '../../../types/taste';
import type { Rating } from '../../../types/watch';

/** 2.6 → 2.7 자동 전환 시간(ms). 와이어프레임에 값이 없어 임의로 정했다 */
const TRANSITION_MS = 1200;

type ResultState = {
  rating: Rating;
  counts: { frameCount: number; popcornCount: number; weeklyFrameDelta: number };
  movieTitle: string;
  posterUrl: string | null;
};

export default function RatingResultPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state as ResultState | null;

  const [done, setDone] = useState(false);
  const [flavors, setFlavors] = useState<PopcornFlavor[]>([]);

  // Router state가 없으면 수치를 복원할 수 없다
  useEffect(() => {
    if (!state) {
      void navigate(path.film(), { replace: true });
    }
  }, [state, navigate]);

  useEffect(() => {
    // 맛 이름에만 쓰이므로 실패하면 이름 없이 그린다
    getPopcornFlavors()
      .then(setFlavors)
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    if (!state) {
      return;
    }

    const timer = setTimeout(() => setDone(true), TRANSITION_MS);

    return () => {
      clearTimeout(timer);
    };
  }, [state]);

  if (!state) {
    return null;
  }

  const { rating, counts, posterUrl } = state;
  const frameCount = done ? counts.frameCount + 1 : counts.frameCount;
  const weekly = done ? counts.weeklyFrameDelta + 1 : counts.weeklyFrameDelta;
  // 대표 맛이 없는 영화의 평가는 팝콘을 만들지 않는다(API-036 · API-041)
  const hasPopcorn = rating.popcorn.flavorCode !== null;
  const flavorName = flavors.find(
    (flavor) => flavor.code === rating.popcorn.flavorCode,
  )?.displayName;

  return (
    <div className={styles.page}>
      <div className={styles.stage} data-done={done}>
        <div className={styles.header}>
          <p className={styles.title}>취향 필름</p>
          <p className={styles.counts}>
            {frameCount}프레임 · 이번 주 +{weekly}
          </p>
        </div>

        <div className={styles.stripArea}>
          <FilmStrip width={226}>
            <span className={styles.emptyFrame} aria-hidden="true" />

            <span className={styles.newFrame}>
              <Poster
                width={166}
                height={160}
                radius={4}
                src={posterUrl ?? undefined}
                title=""
                seed={rating.movieId}
              />
              {!done ? (
                <span className={styles.newBadge} aria-hidden="true">
                  <HeartIcon />
                </span>
              ) : null}
            </span>

            <span className={styles.emptyFrame} aria-hidden="true" />
          </FilmStrip>
        </div>

        {!done ? (
          <p className={styles.progress} role="status">
            평가 완료!
            <br />
            영화 프레임이 취향 필름에 추가됩니다
          </p>
        ) : null}
      </div>

      {done ? (
        <>
          <span className={styles.scrim} aria-hidden="true" />

          <div className={styles.result} role="status">
            <span className={styles.resultIcon} aria-hidden="true">
              <ResultBellIcon />
            </span>

            <p className={styles.resultTitle}>
              {hasPopcorn ? '프레임 1개 · 팝콘 1알이 추가됐어요' : '프레임 1개가 추가됐어요'}
            </p>
            <p className={styles.resultText}>영화 프레임이 취향 필름에 추가됐어요</p>

            {hasPopcorn ? (
              <div className={styles.resultRow}>
                <span className={styles.resultFlavor}>
                  {flavorName ? `${flavorName} 맛 +1알` : '팝콘 +1알'}
                </span>
                <span className={styles.resultBucket}>
                  팝콘 버킷 {counts.popcornCount}알 → {counts.popcornCount + 1}알
                </span>
              </div>
            ) : null}

            <div className={styles.resultActions}>
              <Button onClick={() => void navigate(path.recommendations())}>
                다음 추천 보러 가기
              </Button>

              <Button variant="text" onClick={() => void navigate(path.film())}>
                취향 필름 확인
              </Button>
            </div>
          </div>
        </>
      ) : null}
    </div>
  );
}
