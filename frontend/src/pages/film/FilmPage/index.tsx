/*
 * 3.1 취향 필름 메인
 *
 * 요소와 순서는 와이어프레임 3.1 그대로다(screen-spec.md 7장 3.1).
 * 제목·카운트 · 가로 필름 스트립 · 요즘 취향 흐름 · 최근 추가된 영화 · 취향 리포트 보기
 *
 * 연동 API는 API-042(프레임 목록) · API-040(취향 키워드) · API-041(팝콘 알 수)다.
 *
 * 계약에 출처가 없는 값(CR-16)은 `getTasteFlow`로 따로 가져온다. `요즘 취향 흐름`
 * 문장과 키워드 근거 문장이 그렇다. 자세한 이유는 types/unspecified.ts에 있다.
 *
 * 팀 결정 2026-09-07
 * - `최근 추가된 영화`의 시각은 상대 시각(`어제` · `지난주`) 대신 절대 날짜로 쓴다.
 *   변환 규칙이 계약에 없다
 * - 와이어프레임에 없는 `내 취향 팝콘 보기` 버튼을 추가해 3.2로 가는 진입점을 만든다.
 *   screen-spec.md 3.1이 `100알의 취향 보기` 진입 요소가 명시되지 않았다고 적어 둔
 *   항목이다(`[!]`)
 *
 * `[?]` `취향 리포트 보기`는 7.3이 범위 밖이라 비활성으로 둔다(2.1과 같은 처리).
 * `[?]` 스트립이 보여줄 프레임 개수와 페이지네이션은 미확정이다. 첫 페이지만 쓰고
 * `+ 계속 감기는 중` 타일은 와이어프레임대로 조작할 수 없는 안내로 둔다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import FilmStrip from '../../../components/FilmStrip';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { getMyFrames, getPopcornBucket, getTasteFlow } from '../../../api/taste';
import { path } from '../../../routes/paths';
import { formatDate } from '../../../utils/date';
import styles from './FilmPage.module.css';
import type { FrameList } from '../../../types/taste';
import type { TasteFlowView } from '../../../types/unspecified';

/** 와이어프레임 3.1의 `최근 추가된 영화`는 3편이다 */
const RECENT_COUNT = 3;

export default function FilmPage() {
  const navigate = useNavigate();

  const [frames, setFrames] = useState<FrameList | null>(null);
  const [flow, setFlow] = useState<TasteFlowView | null>(null);
  const [popcornCount, setPopcornCount] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMyFrames()
      .then((list) => {
        if (active) {
          setFrames(list);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    // 흐름 카드와 팝콘 수는 실패하면 그 자리만 비운다. 이 화면의 본문은 필름 목록이다
    getTasteFlow()
      .then((value) => {
        if (active) {
          setFlow(value);
        }
      })
      .catch(() => undefined);

    getPopcornBucket()
      .then((bucket) => {
        if (active) {
          setPopcornCount(bucket.totalCount);
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [reloadCount]);

  if (failed) {
    return (
      <>
        <ScreenHeader title="취향 필름" />

        <StatusView title="취향 필름을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  // 평가가 하나도 없으면 프레임이 없다. 와이어프레임에 그 상태가 없다
  if (frames && frames.totalCount === 0) {
    return (
      <>
        <ScreenHeader title="취향 필름" />

        <StatusView
          variant="empty"
          title="아직 감긴 프레임이 없어요"
          description="영화를 평가하면 프레임 1개와 팝콘 1알이 필름에 쌓여요"
        >
          <Button onClick={() => void navigate(path.recommendations())}>추천 영화 보기</Button>
        </StatusView>
      </>
    );
  }

  // 스트립과 최근 목록은 같은 목록(API-042)을 쓴다. 최근 목록은 앞 3개다
  const items = frames?.items ?? [];
  const recent = items.slice(0, RECENT_COUNT);

  return (
    <>
      <ScreenHeader
        title="취향 필름"
        action={
          /* 받기 전에는 그리지 않는다. `0프레임`으로 보이면 없는 정보를 말하는 셈이다 */
          frames && popcornCount !== null ? (
            <span className={styles.counts}>
              필름 <b className={styles.countStrong}>{frames.totalCount}프레임</b> · 팝콘{' '}
              {popcornCount}알
            </span>
          ) : null
        }
      />

      <div className={styles.page}>
        <div className={styles.stripArea}>
          <FilmStrip orientation="horizontal" tone="dark">
            {items.map((frame) => (
              <button
                key={frame.frameId}
                type="button"
                className={styles.frameButton}
                onClick={() => void navigate(path.frameDetail(frame.frameId))}
              >
                <Poster
                  width={118}
                  height={166}
                  radius={4}
                  src={frame.movie.posterUrl ?? undefined}
                  title=""
                />
                <VisuallyHidden>{frame.movie.title} 프레임 상세</VisuallyHidden>
              </button>
            ))}

            {/* 와이어프레임의 안내 타일. 조작할 수 없다 */}
            <span className={styles.moreTile}>+ 계속 감기는 중</span>
          </FilmStrip>

          {/* 필름 릴 장식 */}
          <span className={styles.reel} aria-hidden="true" />
          <span className={styles.reelHub} aria-hidden="true" />
        </div>

        <div className={styles.content}>
          {/* 요즘 취향 흐름 — 계약에 출처가 없다 (CR-16) */}
          {flow ? (
            <section className={styles.flowCard}>
              <h2 className={styles.flowLabel}>요즘 취향 흐름</h2>
              <p className={styles.flowSentence}>{flow.flowSentence}</p>

              <ul className={styles.keywordList}>
                {flow.keywords.map((keyword) => (
                  <li key={keyword.code} className={styles.keywordItem}>
                    <Chip>{keyword.label}</Chip>
                    <span className={styles.keywordRationale}>{keyword.rationale}</span>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          <section className={styles.recentSection}>
            <h2 className={styles.sectionTitle}>최근 추가된 영화</h2>

            <ul className={styles.recentList}>
              {recent.map((frame) => (
                <li key={frame.frameId}>
                  <button
                    type="button"
                    className={styles.recentItem}
                    onClick={() => void navigate(path.movieDetail(frame.movie.movieId))}
                  >
                    <Poster
                      width={34}
                      height={48}
                      radius={5}
                      src={frame.movie.posterUrl ?? undefined}
                      title=""
                    />

                    <span className={styles.recentInfo}>
                      <span className={styles.recentTitle}>{frame.movie.title}</span>
                      <span className={styles.recentMeta}>
                        {formatDate(frame.createdAt)} · ★ {frame.score.toFixed(1)}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>

          {/* 7.3 취향 리포트는 범위 밖이라 비활성이다 */}
          <Button disabled>취향 리포트 보기</Button>

          {/* 와이어프레임에 없는 요소다. 3.2 진입점으로 추가했다 (팀 결정 2026-09-07) */}
          <Button variant="secondary" onClick={() => void navigate(path.popcornBucket())}>
            내 취향 팝콘 보기
          </Button>
        </div>
      </div>
    </>
  );
}
