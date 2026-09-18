/*
 * 3.1 취향 필름 메인
 *
 * 구성은 사용자 시안 2026-09-17(업무 -458)을 따른다. 시안이 이 화면에 요구하는 것은
 * 하나다. **필름이 화면의 중심이고, 정보와 카드는 그 아래로 물러난다.**
 *
 * 워드마크 · 검색 · 알림 / 제목 / 필름 / 팝콘 요약 / 취향 리포트 / 필름 늘리기
 *
 * 이전 구성에서 바뀐 것
 * - 필름이 밝은 반투명 판(`FilmStrip` paper)에서 **검정에 주황빛이 도는 반투명 릴**로
 *   바뀌었다(`FilmReel`). 아이스 화이트 팝콘 버킷의 필름 무늬와 한 세트로 만든 것이다
 * - 제목이 필름 위로 올라왔다. 필름이 먼저 보이되 무엇의 필름인지 한 줄이 먼저 말한다
 * - **팝콘 범례를 요약으로 줄였다.** 맛 8종을 다 늘어놓던 것을 대표 몇 알과 총 개수로
 *   바꿨다. 분포는 3.2가 보여주는 화면이고 여기서는 "얼마나 모였나"만 말한다
 * - 팝콘을 색 점이 아니라 3D 스프라이트로 그린다. 8색은 밝은 면에서 4종이 판별되지
 *   않아(`design-concept.md` 2.5) 밝은 벽 위 요약에 색 점을 쓸 수 없다
 * - **주 버튼이 `필름 늘리기`다.** 이전에는 리포트가 검정 알약이고 필름 늘리기가 글자
 *   버튼이었다. 시안은 다음 영화를 만나는 것을 이 화면의 주 동작으로 본다
 * - 리포트는 작은 메뉴 줄로 내렸다
 * - 주 버튼이 로고와 같은 빨강이다(`Button` brand). 이 화면과 2.4만 쓴다
 *
 * 연동 API는 API-042(프레임 목록) · API-041(팝콘 버킷)이며 그대로다.
 * 초기 취향 설정에서 **좋아한다고 고른 영화만** 첫 필름이 된다 — 프레임을 만드는 것은
 * 서버(API-024)이고 이 화면은 API-042가 준 목록을 그린다.
 *
 * **프레임 수와 팝콘 수를 같다고 가정하지 않는다.** 대표 맛이 없는 영화는 팝콘을
 * 만들지 않으므로(API-036 · API-041) 두 수는 어긋난다. 각자 자기 API의 총수를 쓴다.
 *
 * **필름을 끝까지 넘기면 다음 장을 이어 받는다**(업무 -656). API-042는 한 장에 20개를 주고
 * 나머지는 `nextCursor`로 온다. 첫 장만 그리던 동안 `73개의 프레임을 넘겨보세요`라고 말하면서
 * 20개만 넘길 수 있었다. 릴 끝에 다음 장 칸을 두고, 그 칸이 보일 즈음 다음 장을 부른다
 * (4.2 검색과 같은 방식 — `IntersectionObserver`, 실패 뒤와 관찰이 안 되는 환경은 버튼).
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import FilmReel from '../../../components/FilmReel';
import { BellIcon, ChevronRightIcon, SearchIcon } from '../../../components/Icon';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import Wordmark from '../../../components/Wordmark';
import { kernelSprite } from '../../../components/PopcornBucket/sprites';
import { getLatestReadyReport } from '../../../api/reports';
import { getUnreadNotificationCount } from '../../../api/notifications';
import { getMyFrames, getPopcornBucket } from '../../../api/taste';
import { path } from '../../../routes/paths';
import styles from './FilmPage.module.css';
import type { FrameList, PopcornBucket } from '../../../types/taste';

/*
 * 필름 프레임 한 칸. 포스터 그대로의 2:3이다.
 *
 * **프레임 수와 무관하게 고정이다.** 375px 셸의 릴 안쪽 폭(375 − 좌우 12 = 351)에서
 * 두 칸 반이 보이는 값이라, 오른쪽이 잘린 모습이 "더 있다"를 말해 준다.
 *
 * 높이는 375×812에서 이 화면 전체가 스크롤 없이 들어오는 상한이다. 측정해서 맞췄고
 * (본문 632px), 여기서 더 키우면 `필름 늘리기`가 하단 내비 밑으로 내려간다.
 */
const FRAME_WIDTH = 110;
const FRAME_HEIGHT = 165;

/** 요약에 올릴 맛 수. 8종을 다 늘어놓으면 요약이 아니라 표가 된다 */
const SUMMARY_FLAVOR_COUNT = 4;

/** 스크롤로 다음 장을 잇는 수단이 있는 환경인지. 없으면 다음 장 칸이 버튼이 된다 */
const SUPPORTS_OBSERVER = typeof IntersectionObserver !== 'undefined';

/**
 * 다음 장을 미리 부르는 거리. 다음 장 칸이 릴 오른쪽 끝에서 이만큼 남았을 때 부른다.
 * 프레임 세 칸쯤이라 넘기는 손이 끝에 닿기 전에 채워진다
 */
const PREFETCH_MARGIN = '0px 360px 0px 0px';

/** 다음 장 칸의 상태. `failed`면 자동으로 다시 부르지 않고 버튼으로 잇는다 */
type MoreState = 'idle' | 'loading' | 'failed';

/** 빈 필름에 남겨 두는 자리. 시안 01이 두 칸을 보여준다 */
const EMPTY_SLOT_LABELS = ['첫 번째 프레임', '두 번째 프레임'];

export default function FilmPage() {
  const navigate = useNavigate();

  const [frames, setFrames] = useState<FrameList | null>(null);
  const [more, setMore] = useState<MoreState>('idle');
  const moreRef = useRef<HTMLSpanElement>(null);
  const [bucket, setBucket] = useState<PopcornBucket | null>(null);
  /** 아직 3.3에서 받지 않은 새 리포트. 있으면 리포트 줄이 발급(3.3)으로 간다 */
  const [newReportId, setNewReportId] = useState<number | null>(null);
  const [unreadNotificationCount, setUnreadNotificationCount] = useState(0);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMyFrames()
      .then((list) => {
        if (active) {
          setFrames(list);
          setMore('idle');
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    // 팝콘 요약은 실패하면 그 자리만 비운다. 이 화면의 본문은 필름이다
    getPopcornBucket()
      .then((value) => {
        if (active) {
          setBucket(value);
        }
      })
      .catch(() => undefined);

    // 새 리포트를 확인하지 못해도 리포트 줄은 목록(7.3)으로 가는 원래 동작을 한다
    getLatestReadyReport()
      .then((latest) => {
        if (active) {
          setNewReportId(latest?.needsIntro ? latest.reportId : null);
        }
      })
      .catch(() => undefined);

    // 배지는 보조 정보라 실패해도 필름 화면은 그대로 둔다.
    getUnreadNotificationCount()
      .then((count) => {
        if (active) setUnreadNotificationCount(count);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [reloadCount]);

  /**
   * 다음 장을 이어 받는다.
   *
   * 실패해도 이미 그린 프레임은 두고 다음 장 칸만 `다시 불러오기`로 바꾼다. 같은 프레임이
   * 두 번 오면(그 사이 새 평가로 커서 경계가 밀린 경우) 한 번만 그린다.
   */
  const loadMore = useCallback(() => {
    if (!frames?.hasNext || !frames.nextCursor || more === 'loading') return;

    setMore('loading');

    getMyFrames(frames.nextCursor)
      .then((page) => {
        setFrames((prev) => {
          if (!prev) return prev;

          const seen = new Set(prev.items.map((frame) => frame.frameId));

          return {
            ...page,
            items: [...prev.items, ...page.items.filter((frame) => !seen.has(frame.frameId))],
          };
        });
        setMore('idle');
      })
      .catch(() => setMore('failed'));
  }, [frames, more]);

  /*
   * 다음 장 칸이 릴 안에서 보일 즈음 다음 장을 부른다. 릴은 스스로 가로 스크롤하므로
   * 관찰 기준은 화면이 아니라 릴의 트랙(칸의 부모)이다.
   *
   * 실패한 뒤에는 자동으로 다시 부르지 않는다 — 끝에 머문 채 요청이 끝없이 반복된다.
   */
  const canAutoLoad = SUPPORTS_OBSERVER && Boolean(frames?.hasNext) && more === 'idle';

  useEffect(() => {
    const node = moreRef.current;

    if (!canAutoLoad || !node) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) loadMore();
      },
      { root: node.parentElement, rootMargin: PREFETCH_MARGIN },
    );

    observer.observe(node);

    return () => observer.disconnect();
  }, [canAutoLoad, loadMore]);

  /*
   * 헤더 오른쪽. **검색 → 알림 순서다**(사용자 지시 2026-09-16, 업무 -461).
   *
   * 빈 상태 · 실패 상태에서도 같이 선다. 필름은 앱의 시작 화면이라 프레임이 없다고
   * 검색과 알림으로 가는 길까지 막으면 안 된다.
   */
  const headerActions = (
    <div className={styles.headerActions}>
      <Link to={path.search()} className={styles.headerAction} aria-label="검색">
        <SearchIcon />
      </Link>

      <Link
        to={path.notifications()}
        className={styles.headerAction}
        aria-label={
          unreadNotificationCount > 0 ? `알림, 읽지 않은 알림 ${unreadNotificationCount}개` : '알림'
        }
      >
        <BellIcon />
        {unreadNotificationCount > 0 ? <span className={styles.notificationDot} /> : null}
      </Link>
    </div>
  );

  if (failed) {
    return (
      <>
        <ScreenHeader title={<Wordmark />} action={headerActions} />

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

  const items = frames?.items ?? [];
  const totalCount = frames?.totalCount ?? 0;
  // 받기 전에는 빈 상태로 단정하지 않는다. `0개`로 보이면 없는 정보를 말하는 셈이다
  const isEmpty = frames !== null && totalCount === 0;

  // 많이 나온 맛부터. 0알인 맛은 아직 발견하지 않은 맛이라 뺀다
  const flavors = bucket
    ? [...bucket.flavors]
        .filter((flavor) => flavor.popcornCount > 0)
        .sort((a, b) => b.popcornCount - a.popcornCount)
    : [];
  const summaryFlavors = flavors.slice(0, SUMMARY_FLAVOR_COUNT);
  const restCount = summaryFlavors.reduce(
    (rest, flavor) => rest - flavor.popcornCount,
    bucket?.totalCount ?? 0,
  );

  return (
    <>
      <ScreenHeader title={<Wordmark />} action={headerActions} />

      <div className={styles.page}>
        <header className={styles.head}>
          <p className={styles.eyebrow}>YOUR TASTE ON FILM</p>
          <h2 className={styles.title}>나의 취향 필름</h2>

          {frames ? (
            <p className={styles.subtitle}>
              {isEmpty ? '아직 첫 프레임 전이에요' : `${totalCount}개의 프레임이 감겼어요`}
            </p>
          ) : null}
        </header>

        <div className={styles.reelArea}>
          <FilmReel
            frameWidth={FRAME_WIDTH}
            frameHeight={FRAME_HEIGHT}
            label={isEmpty ? '아직 비어 있는 취향 필름' : '취향 필름 프레임'}
          >
            {isEmpty
              ? EMPTY_SLOT_LABELS.map((label) => (
                  <span key={label} className={styles.emptySlot}>
                    <span className={styles.emptySlotMark} aria-hidden="true" />
                    <span className={styles.emptySlotLabel}>{label}</span>
                  </span>
                ))
              : items.map((frame) => (
                  <button
                    key={frame.frameId}
                    type="button"
                    className={styles.frameButton}
                    onClick={() => void navigate(path.movieDetail(frame.movie.movieId))}
                  >
                    <Poster
                      width={FRAME_WIDTH}
                      height={FRAME_HEIGHT}
                      radius={0}
                      src={frame.movie.posterUrl ?? undefined}
                      title=""
                      seed={frame.movie.movieId}
                    />
                    <VisuallyHidden>{frame.movie.title} 상세 보기</VisuallyHidden>
                  </button>
                ))}

            {/*
             * 다음 장 칸. 더 받을 프레임이 있을 때만 릴 끝에 선다. 받는 동안은 빈 칸이고,
             * 실패했거나 스크롤로 이을 수 없는 환경이면 누르는 칸이 된다.
             */}
            {!isEmpty && frames?.hasNext ? (
              <span ref={moreRef} className={styles.moreSlot}>
                {more === 'failed' || !SUPPORTS_OBSERVER ? (
                  <button type="button" className={styles.moreButton} onClick={loadMore}>
                    {more === 'failed' ? '다시 불러오기' : '프레임 더 보기'}
                  </button>
                ) : (
                  <span className={styles.moreLoading} role="status">
                    프레임을 불러오는 중
                  </span>
                )}
              </span>
            ) : null}
          </FilmReel>

          {/* 잘린 오른쪽이 이미 "더 있다"를 말하지만, 넘길 수 있다는 것까지는 말해 주지 않는다 */}
          {!isEmpty && items.length > 1 ? (
            <p className={styles.reelHint}>{totalCount}개의 프레임을 넘겨보세요</p>
          ) : null}
        </div>

        {isEmpty ? (
          <div className={styles.empty}>
            <h3 className={styles.emptyTitle}>아직 필름에 담긴 영화가 없어요</h3>
            <p className={styles.emptyText}>영화를 만나며 취향 필름을 채워보세요</p>

            <Button variant="brand" onClick={() => void navigate(path.recommendations())}>
              추천 영화 둘러보기
            </Button>
          </div>
        ) : (
          <div className={styles.content}>
            {/*
             * 팝콘 요약이자 3.2 진입점. **대표 몇 알과 총 개수만** 말한다.
             * 맛별 분포를 다 늘어놓는 것은 3.2가 할 일이다(시안 2026-09-17).
             *
             * 총수는 API-041의 값이다. 프레임 수와 같다고 가정하지 않는다.
             */}
            {bucket && summaryFlavors.length > 0 ? (
              <button
                type="button"
                className={styles.popcorn}
                /*
                 * 이름을 직접 준다. 안 주면 맛 이름과 개수가 죽 이어 붙은 한 줄이
                 * 버튼 이름이 되어 무엇을 하는 버튼인지 들리지 않는다.
                 */
                aria-label={`나의 취향 팝콘 ${bucket.totalCount}알 자세히 보기`}
                onClick={() => void navigate(path.popcornBucket())}
              >
                <span className={styles.rowHead}>
                  <span className={styles.rowTitle}>나의 취향 팝콘</span>
                  <span className={styles.rowValue}>
                    {bucket.totalCount}알
                    <ChevronRightIcon />
                  </span>
                </span>

                <span className={styles.kernels} data-testid="popcorn-summary-kernels">
                  {summaryFlavors.map((flavor) => (
                    <span
                      key={flavor.flavorCode}
                      className={styles.kernel}
                      data-flavor={flavor.flavorCode}
                    >
                      <img
                        className={styles.kernelSprite}
                        src={kernelSprite(flavor.flavorCode)}
                        alt=""
                      />
                      <span className={styles.kernelName}>{flavor.displayName}</span>
                      <span className={styles.kernelCount}>{flavor.popcornCount}</span>
                    </span>
                  ))}

                  {restCount > 0 ? (
                    <span className={styles.kernelRest}>+ 다른 맛 {restCount}알</span>
                  ) : null}
                </span>
              </button>
            ) : null}

            {/*
             * 취향 리포트. 작은 메뉴 줄이다.
             *
             * 아직 받지 않은 새 리포트가 있으면 3.3 발급으로, 없으면 7.3 목록으로 간다.
             * 홈과 같은 규칙(`getLatestReadyReport`)이고 이동표에 3.3으로 들어오는
             * 화살표가 없어 정한 길이다.
             */}
            <button
              type="button"
              className={styles.report}
              /* 팝콘 줄과 같은 이유로 이름을 직접 준다 */
              aria-label={newReportId !== null ? '새 취향 리포트 받기' : '취향 리포트 보기'}
              onClick={() =>
                void navigate(
                  newReportId !== null ? path.reportIssued(newReportId) : path.tasteReports(),
                )
              }
            >
              <span className={styles.rowHead}>
                <span className={styles.rowTitle}>취향 리포트</span>
                <span className={styles.rowValue} data-tone={newReportId !== null ? 'new' : null}>
                  {newReportId !== null ? '새 리포트 받기' : '리포트 보기'}
                  <ChevronRightIcon />
                </span>
              </span>

              <span className={styles.reportText}>지금까지 본 영화로 만든 나만의 취향 분석</span>
            </button>

            {/*
             * 이 화면의 주 동작. 다음 영화를 만나는 것이 필름을 늘리는 유일한 길이다.
             * **홈에서 오늘의 추천을 빼면서 이 자리가 2.2로 가는 상시 진입점이 됐다**(업무 -461).
             */}
            <div className={styles.action}>
              <p className={styles.actionHint}>다음 영화를 추천받아요</p>

              <Button variant="brand" onClick={() => void navigate(path.recommendations())}>
                필름 늘리기
              </Button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
