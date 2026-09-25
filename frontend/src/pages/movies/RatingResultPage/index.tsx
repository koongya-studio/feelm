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
 *
 * **추가 후 수치는 서버에서 읽은 실측이다**(업무 -599). 예전에는 이 화면이 추가 전
 * 수치에 `+1`을 더해 추가 후를 지어냈다. 계약은 평가 한 건에 프레임 1개 · 팝콘 1알이지만
 * (대표 맛이 없으면 0알), 팝콘 집계는 `대표 맛이 있는 영화의 평가`를 세므로 영화에 대표 맛이
 * 붙기만 해도 과거 평가가 뒤늦게 버킷에 들어온다. 그러면 실제 팝콘은 두 알이 느는데 화면만
 * 한 알을 단언하게 된다(2026-09-17 여정 QA 관측). 그래서 수치도 문구도 **실제 증가량**으로
 * 적는다.
 *
 * 그 실측은 **이 화면이 2.6을 띄운 동안 읽는다**(업무 -635). 전에는 2.5가 저장 뒤 재조회까지
 * 마치고 넘어와 2.6이 늦게 떴다. 2.6은 추가 전 수치만 그리므로 기다릴 이유가 없다. 2.7은
 * 전환 시간이 지나고 **실측이 도착한 뒤에** 띄운다. 읽은 값은 Router state에 적어 두어,
 * 뒤로 가기로 다시 열려도 그 뒤의 평가가 섞인 값을 새로 읽지 않는다.
 *
 * 추가 후 수치를 읽지 못했으면(`after`가 `null`) 계약이 정한 증가량으로 물러선다. 응답이
 * 프레임 한 개와 팝콘 한 알(대표 맛이 있을 때)을 보장하므로 그만큼은 언제나 참이고, 같은
 * 이유로 실측 증가량이 그보다 작게 읽혀도 그 아래로는 내려가지 않는다.
 *
 * `이번 주 +N`은 대응 필드가 없는 고정값이라 이 업무의 대상이 아니다(업무 -599 설명).
 *
 * **새 프레임은 내 필름의 기존 프레임에 이어 붙어 보인다**(업무 -633). 전에는 Router state의
 * 포스터 한 장만 그려 방금 평가한 영화가 필름의 전부처럼 보였다. 2.6이 떠 있는 동안 내 필름
 * (API-042)을 읽어 새 프레임 아래에 기존 프레임을 최신순으로 잇는다 — 3.1 릴이 최신 프레임을
 * 맨 앞에 두는 순서와 같다. 목록은 이미 저장된 새 프레임을 포함하므로 그것과 그 뒤에 생긴
 * 프레임(뒤로 가기로 다시 연 경우)은 뺀다. 읽지 못하면 새 프레임 한 장과 빈 칸으로 그린다.
 *
 * **새 프레임은 무엇이 추가됐는지 말해야 한다**(업무 -770, 2026-09-21 배포본 점검). 포스터가
 * 빈 회색 칸으로 남아 화면만으로는 어떤 영화가 감겼는지 알 수 없었다. 두 가지를 고쳤다.
 * - 포스터는 **곧바로 받는다**(`loading="eager"`). 지연 로딩은 요청을 레이아웃 뒤로 미뤄
 *   1.2초짜리 2.6이 빈 칸으로 지나갈 수 있다
 * - 포스터가 없거나 받지 못한 영화는 자리표시자에 **제목을 적는다**(`caption`). 필름의
 *   기존 프레임도 같다
 *
 * 완료 화면에서는 새 프레임을 필름의 첫 칸에 둔다. 다음 평가용 빈 칸이 위에 있으면
 * 방금 추가한 영화가 한 칸 밀려 보인다.
 */
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import FilmReel from '../../../components/FilmReel';
import Poster from '../../../components/Poster';
import { HeartIcon, ResultBellIcon } from '../../../components/Icon';
import { getMyFrames, getPopcornFlavors } from '../../../api/taste';
import { getRatingCounts } from '../../../api/watch';
import { path } from '../../../routes/paths';
import styles from './RatingResultPage.module.css';
import type { RatingCounts } from '../../../api/watch';
import type { FrameSummary, PopcornFlavor } from '../../../types/taste';
import type { Rating } from '../../../types/watch';

/** 2.6 → 2.7 자동 전환 시간(ms). 와이어프레임에 값이 없어 임의로 정했다 */
const TRANSITION_MS = 1200;

/*
 * 필름 칸 크기. **3.1과 같은 값이다**(FilmPage) — 방금 감긴 프레임이 곧 3.1에서 보게 될 그
 * 칸이어야 한다. 필름은 3.1의 릴을 세로로 세운 것이다(`FilmReel`, 업무 -609). 그전의
 * 밝은 판(`FilmStrip`)은 3.1의 필름이 확정된 뒤 다른 재질로 보였다.
 */
const FRAME_WIDTH = 110;
const FRAME_HEIGHT = 165;

/** 새 프레임 아래에 잇는 기존 프레임 수. 화면에 보이는 것은 한두 칸이고 나머지는 잘린다 */
const PREVIOUS_FRAME_LIMIT = 3;

type ResultState = {
  rating: Rating;
  /** 저장 전 수치. 2.6이 그린다 */
  counts: RatingCounts;
  /** 저장 후 실측 수치. 아직 읽기 전이면 없고, 읽지 못했으면 `null`이다 */
  after?: RatingCounts | null;
  movieTitle: string;
  posterUrl: string | null;
};

export default function RatingResultPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state as ResultState | null;

  const [elapsed, setElapsed] = useState(false);
  const [flavors, setFlavors] = useState<PopcornFlavor[]>([]);
  /** 새 프레임 앞서 쌓인 내 필름 프레임. 읽기 전이거나 못 읽었으면 빈 배열이다 */
  const [previousFrames, setPreviousFrames] = useState<FrameSummary[]>([]);
  const hasState = state !== null;
  const measured = state?.after !== undefined;

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

  // 실측을 적으며 state가 다시 만들어져도 다시 읽지 않도록 값으로 의존한다
  const newFrameId = state?.rating.frame.frameId;
  const newFrameCreatedAt = state?.rating.frame.createdAt;

  useEffect(() => {
    if (newFrameId === undefined || newFrameCreatedAt === undefined) {
      return;
    }

    let active = true;
    const createdAt = Date.parse(newFrameCreatedAt);

    // 기존 프레임은 보여 주기만 하므로 실패하면 새 프레임 한 장으로 그린다
    getMyFrames()
      .then((list) => {
        if (active) {
          setPreviousFrames(
            list.items
              .filter(
                (frame) => frame.frameId !== newFrameId && Date.parse(frame.createdAt) <= createdAt,
              )
              .slice(0, PREVIOUS_FRAME_LIMIT),
          );
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [newFrameId, newFrameCreatedAt]);

  useEffect(() => {
    if (!state || measured) {
      return;
    }

    let active = true;

    // 저장은 이미 끝났으므로 읽지 못해도 되돌리지 않는다. 2.7이 계약의 증가량으로 물러선다
    getRatingCounts()
      .catch(() => null)
      .then((after) => {
        if (active) {
          void navigate(location.pathname, { replace: true, state: { ...state, after } });
        }
      });

    return () => {
      active = false;
    };
  }, [state, measured, location.pathname, navigate]);

  // 실측을 적느라 state가 바뀌어도 전환 시간을 다시 재지 않는다
  useEffect(() => {
    if (!hasState) {
      return;
    }

    const timer = setTimeout(() => setElapsed(true), TRANSITION_MS);

    return () => {
      clearTimeout(timer);
    };
  }, [hasState]);

  if (!state) {
    return null;
  }

  const { rating, counts, after = null, posterUrl, movieTitle } = state;
  const done = elapsed && measured;
  // 대표 맛이 없는 영화의 평가는 팝콘을 만들지 않는다(API-036 · API-041)
  const hasPopcorn = rating.popcorn.flavorCode !== null;
  /*
   * 실제 증가량. 저장 후 수치를 읽었으면 서버가 실제로 움직인 만큼이고, 못 읽었으면 응답이
   * 보장하는 만큼이다. 응답에 프레임이 들어 있고 대표 맛이 있으면 팝콘 한 알이 그 맛에
   * 담기므로, 그 아래로는 내려가지 않는다.
   */
  const frameDelta = Math.max(after ? after.frameCount - counts.frameCount : 1, 1);
  const popcornDelta = Math.max(
    after ? after.popcornCount - counts.popcornCount : Number(hasPopcorn),
    Number(hasPopcorn),
  );
  const frameCount = done ? counts.frameCount + frameDelta : counts.frameCount;
  // `이번 주 +N`은 고정값이라 실측이 없다(업무 -599 범위 밖)
  const weekly = done ? counts.weeklyFrameDelta + 1 : counts.weeklyFrameDelta;
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
          <FilmReel
            orientation="vertical"
            frameWidth={FRAME_WIDTH}
            frameHeight={FRAME_HEIGHT}
            label="취향 필름에 감기는 프레임"
          >
            <span className={styles.newFrame}>
              <Poster
                width={FRAME_WIDTH}
                height={FRAME_HEIGHT}
                radius={0}
                src={posterUrl ?? undefined}
                title={movieTitle}
                seed={rating.movieId}
                loading="eager"
                caption={movieTitle}
              />
              {!done ? (
                <span className={styles.newBadge} aria-hidden="true">
                  <HeartIcon />
                </span>
              ) : null}
            </span>

            {previousFrames.length > 0 ? (
              previousFrames.map((frame) => (
                <span key={frame.frameId} className={styles.previousFrame}>
                  <Poster
                    width={FRAME_WIDTH}
                    height={FRAME_HEIGHT}
                    radius={0}
                    src={frame.movie.posterUrl ?? undefined}
                    title={frame.movie.title}
                    seed={frame.movie.movieId}
                    loading="eager"
                    caption={frame.movie.title}
                  />
                </span>
              ))
            ) : (
              <span className={styles.emptyFrame} aria-hidden="true" />
            )}
          </FilmReel>
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
              {popcornDelta > 0
                ? `프레임 ${frameDelta}개 · 팝콘 ${popcornDelta}알이 추가됐어요`
                : `프레임 ${frameDelta}개가 추가됐어요`}
            </p>
            <p className={styles.resultText}>영화 프레임이 취향 필름에 추가됐어요</p>

            {/* 버킷이 움직였을 때만 적는다. 맛 줄은 이 평가가 팝콘을 만들었을 때만이다 */}
            {popcornDelta > 0 ? (
              <div className={styles.resultRow}>
                {/* 이 평가가 담긴 맛은 언제나 한 알이다. 버킷 총합과 다를 수 있다 */}
                {hasPopcorn ? (
                  <span className={styles.resultFlavor}>
                    {flavorName ? `${flavorName} 맛 +1알` : '팝콘 +1알'}
                  </span>
                ) : null}
                <span className={styles.resultBucket}>
                  팝콘 버킷 {counts.popcornCount}알 → {counts.popcornCount + popcornDelta}알
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
