/*
 * 3.1 취향 필름 메인
 *
 * 구성은 확정 시안을 따른다(`docs/screens/design-concept.md`). 시안은 이 화면이
 * 한 가지만 말하게 한다. **내 필름이 몇 칸 찼는가.**
 *
 * 워드마크 · 검색 · 필름 스트립 · 프레임 수 문장 · 팝콘 범례 · 취향 리포트 보기
 *
 * 연동 API는 API-042(프레임 목록) · API-041(팝콘 버킷)이다.
 *
 * 팀 결정 2026-09-09 (업무 -100)
 * - 와이어프레임 3.1의 `요즘 취향 흐름`(태그 칩)과 `최근 추가된 영화` 목록을 뺐다.
 *   시안에 없고, 한 화면이 세 가지를 동시에 말하면 어느 것도 읽히지 않는다.
 *   두 블록은 다른 화면으로 옮기지 않고 삭제했다
 * - 헤더 제목을 `취향 필름`에서 워드마크로 바꿨다. 홈과 같은 헤더다
 * - 프레임 수는 헤더 우측 카운트가 아니라 본문의 큰 문장이 말한다
 * - 와이어프레임의 `+ 계속 감기는 중` 타일 대신 빈 칸을 점선으로 둔다. 아직 채울
 *   자리가 남았다는 뜻이며, 조작할 수 없는 것은 같다
 *
 * 팝콘 범례는 어두운 판 위에 둔다. 8색은 밝은 면에서 절반이 판별되지 않는다
 * (`docs/screens/design-concept.md` 2.5). 이 판이 3.2로 가는 진입점이다.
 *
 * `[?]` 스트립이 보여줄 프레임 개수와 페이지네이션은 미확정이다. 첫 페이지만 쓴다.
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import FilmStrip from '../../../components/FilmStrip';
import { SearchIcon } from '../../../components/Icon';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import Wordmark from '../../../components/Wordmark';
import { getMyFrames, getPopcornBucket } from '../../../api/taste';
import { path } from '../../../routes/paths';
import styles from './FilmPage.module.css';
import type { FrameList, PopcornBucket } from '../../../types/taste';

/*
 * 필름 프레임 한 칸. 포스터보다 세로로 길다.
 *
 * 시안은 300px 폭에서 60×104를 썼고, 375px 기준으로 환산하면 75×130이다.
 * 그 비율을 지키면서 판 안쪽 폭 319px에 네 칸이 딱 들어가는 값이 73×126이다
 * (73×4 + 간격 8×3 = 316). CSS의 `.emptyFrame`이 같은 값을 써야 줄이 흔들리지 않는다.
 */
const FRAME_WIDTH = 73;
const FRAME_HEIGHT = 126;

/** 범례에 올릴 맛 수. 8종을 다 늘어놓으면 판이 표가 된다. 나머지는 3.2에서 본다 */
const LEGEND_COUNT = 4;

export default function FilmPage() {
  const navigate = useNavigate();

  const [frames, setFrames] = useState<FrameList | null>(null);
  const [bucket, setBucket] = useState<PopcornBucket | null>(null);
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

    // 팝콘 범례는 실패하면 그 자리만 비운다. 이 화면의 본문은 필름 목록이다
    getPopcornBucket()
      .then((value) => {
        if (active) {
          setBucket(value);
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
        <ScreenHeader title={<Wordmark />} />

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
        <ScreenHeader title={<Wordmark />} />

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

  const items = frames?.items ?? [];

  // 많이 나온 맛부터 보여준다. 0알인 맛은 아직 발견하지 않은 맛이라 뺀다
  const legend = bucket
    ? [...bucket.flavors]
        .filter((flavor) => flavor.popcornCount > 0)
        .sort((a, b) => b.popcornCount - a.popcornCount)
        .slice(0, LEGEND_COUNT)
    : [];

  return (
    <>
      <ScreenHeader
        title={<Wordmark />}
        action={
          <Link to={path.search()} className={styles.headerAction} aria-label="검색">
            <SearchIcon />
          </Link>
        }
      />

      <div className={styles.page}>
        <div className={styles.stripArea}>
          <FilmStrip orientation="horizontal" tone="paper">
            {items.map((frame) => (
              <button
                key={frame.frameId}
                type="button"
                className={styles.frameButton}
                onClick={() => void navigate(path.frameDetail(frame.frameId))}
              >
                <Poster
                  width={FRAME_WIDTH}
                  height={FRAME_HEIGHT}
                  radius={4}
                  src={frame.movie.posterUrl ?? undefined}
                  title=""
                  seed={frame.movie.movieId}
                />
                <VisuallyHidden>{frame.movie.title} 프레임 상세</VisuallyHidden>
              </button>
            ))}

            {/* 아직 채울 자리가 남았다는 표시. 조작할 수 없다 */}
            <span className={styles.emptyFrame} aria-hidden="true" />
          </FilmStrip>
        </div>

        <div className={styles.content}>
          <p className={styles.eyebrow}>MY · FILM</p>

          {/* 받기 전에는 그리지 않는다. `0개`로 보이면 없는 정보를 말하는 셈이다 */}
          {frames ? (
            <h2 className={styles.headline}>
              {frames.totalCount}개의 프레임이
              <br />
              감겼어요
            </h2>
          ) : null}

          {/*
           * 팝콘 범례이자 3.2 진입점. 어두운 판에 담는다. 팝콘 8색은 밝은 면에서
           * 절반이 판별 불가라, 팝콘이 나오는 자리는 예외 없이 어두운 면 위에 둔다
           * (`docs/screens/design-concept.md` 2.5).
           *
           * 색은 API-044가 내려주는 값을 그대로 쓴다. 화면이 맛 색을 알지 않는다.
           */}
          {bucket ? (
            <button
              type="button"
              className={styles.bucket}
              onClick={() => void navigate(path.popcornBucket())}
            >
              <span className={styles.bucketLabel}>{bucket.totalCount} · KERNELS</span>

              <ul className={styles.legend}>
                {legend.map((flavor) => (
                  <li key={flavor.flavorCode} className={styles.legendItem}>
                    <span
                      className={styles.kernel}
                      style={{ background: flavor.color }}
                      aria-hidden="true"
                    />
                    {flavor.displayName} {flavor.popcornCount}
                  </li>
                ))}
              </ul>
            </button>
          ) : null}

          {/*
           * 이 화면의 주 동작. 검정 알약은 한 화면에 하나뿐이고 그 하나가 여기다
           * (`docs/screens/design-concept.md` 2.4).
           * 3.1 ⇢ 7.3 (업무 -308)
           */}
          <Button onClick={() => void navigate(path.tasteReports())}>취향 리포트 보기</Button>
        </div>
      </div>
    </>
  );
}
