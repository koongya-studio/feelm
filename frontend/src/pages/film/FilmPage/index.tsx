/*
 * 3.1 취향 필름 메인
 *
 * 구성은 사용자 시안 2026-09-17(업무 -458)을 따른다. 시안이 이 화면에 요구하는 것은
 * 하나다. **필름이 화면의 중심이고, 정보와 카드는 그 아래로 물러난다.**
 *
 * 공통 헤더(워드마크 · 검색 · 알림, `AppHeader`) / 제목 / 필름 / 팝콘 요약 / 취향 리포트 / 평가·추천 진입점
 *
 * 이전 구성에서 바뀐 것
 * - 필름이 밝은 반투명 판(`FilmStrip` paper)에서 **검정에 주황빛이 도는 반투명 릴**로
 *   바뀌었다(`FilmReel`). 아이스 화이트 팝콘 버킷의 필름 무늬와 한 세트로 만든 것이다
 * - 제목이 필름 위로 올라왔다. 필름이 먼저 보이되 무엇의 필름인지 한 줄이 먼저 말한다
 * - **팝콘 범례를 요약으로 줄였다.** 맛 8종을 다 늘어놓던 것을 대표 몇 알과 총 개수로
 *   바꿨다. 분포는 3.2가 보여주는 화면이고 여기서는 "얼마나 모였나"만 말한다
 * - 팝콘을 색 점이 아니라 3D 스프라이트로 그린다. 8색은 밝은 면에서 4종이 판별되지
 *   않아(`design-concept.md` 2.5) 밝은 벽 위 요약에 색 점을 쓸 수 없다
 * - 평가와 추천을 명확히 나눈다(업무 -885). 모바일은 두 버튼을 나란히 놓고,
 *   데스크톱은 제목 줄의 평가 버튼과 아래 오늘의 추천을 함께 보여준다
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
 * ─────────────────────────── 데스크톱 시안 (업무 -783)
 *
 * 1024px 이상에서는 2026-09-21 시안의 배치를 쓴다. **모바일 화면은 한 줄도 바뀌지 않는다** —
 * 폭에 따라 그리는 것 자체가 갈린다(`hooks/useShellSize`).
 *
 * - 제목 오른쪽에 `‹ 1 – 6 / 73 ›`가 붙는다. 넓은 릴에서는 한 번에 여섯 칸쯤 보이므로
 *   모바일의 `6 / 73`으로는 어디까지 봤는지 말이 되지 않는다
 * - 필름 아래에 `오늘의 추천`이 선다(`TodayRecommendations`). 2.2 추천 영화 화면과 같은
 *   것(API-045)이며, 시안이 그 앞자리 몇 장을 여기서 먼저 보여준다
 * - 팝콘 요약 · 취향 리포트 줄이 우측 보조 패널로 간다(`FilmAside`). 본문에서는 뺀다 —
 *   같은 것을 두 자리에 두면 무엇을 눌러야 하는지가 흐려진다
 * - **모바일의 행동 버튼과 `옆으로 넘겨보세요` 힌트를 넓은 화면에서 뺀다**(사용자 결정
 *   2026-09-22). 2.2로 가는 길은 `오늘의 추천`의 `맞춤 N : 확장 M`이 맡는다.
 *   마우스로 끄는 것과 좌우 화살표가 있어 넘기는 방법도 힌트 없이 드러난다
 *
 * **필름을 끝까지 넘기면 다음 장을 이어 받는다**(업무 -656). API-042는 한 장에 20개를 주고
 * 나머지는 `nextCursor`로 온다. 첫 장만 그리던 동안 `73개의 프레임을 넘겨보세요`라고 말하면서
 * 20개만 넘길 수 있었다. 릴 끝에 다음 장 칸을 두고, 그 칸이 보일 즈음 다음 장을 부른다
 * (4.2 검색과 같은 방식 — `IntersectionObserver`, 실패 뒤와 관찰이 안 되는 환경은 버튼).
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import AppHeader from '../../../components/AppHeader';
import Button from '../../../components/Button';
import DesktopAside from '../../../components/DesktopAside';
import FilmReel from '../../../components/FilmReel';
import GuideCallout from '../../../components/GuideCallout';
import { ChevronLeftIcon, ChevronRightIcon } from '../../../components/Icon';
import Poster from '../../../components/Poster';
import PosterCue from '../../../components/PosterCue';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { kernelSprite } from '../../../components/PopcornBucket/sprites';
import FilmAside from './FilmAside';
import TodayRecommendations from './TodayRecommendations';
import { getLatestReadyReport } from '../../../api/reports';
import { getMyFrames, getPopcornBucket } from '../../../api/taste';
import { useShellSize } from '../../../hooks/useShellSize';
import { useAuth } from '../../../contexts/authContext';
import { useDesktopAsideSlot } from '../../../contexts/desktopAsideContext';
import { path } from '../../../routes/paths';
import {
  FILM_GUIDE,
  PARTY_GUIDE,
  completeGuide,
  hasSeenGuide,
  readGuideProgress,
  restartGuide,
  setGuideProgress,
} from '../../../utils/userGuides';
import styles from './FilmPage.module.css';
import type { FrameList, PopcornBucket } from '../../../types/taste';

/*
 * 필름 프레임 한 칸. 포스터 그대로의 2:3이다.
 *
 * **프레임 수와 무관하게 고정이다.** 375px 셸의 릴 안쪽 폭(375 − 좌우 12 = 351)에서
 * 두 칸 반이 보이는 값이라, 오른쪽이 잘린 모습이 "더 있다"를 말해 준다.
 *
 * 높이는 375×812에서 이 화면 전체가 스크롤 없이 들어오는 상한이다. 측정해서 맞췄고
 * (본문 632px), 여기서 더 키우면 행동 버튼이 하단 내비 밑으로 내려간다.
 */
const FRAME_WIDTH = 110;
const FRAME_HEIGHT = 165;

/*
 * 넓은 화면의 프레임 한 칸 (업무 -783). 중앙 단(920px)에서 여섯 칸이 보이고 일곱째가 잘리는
 * 크기다 — 2026-09-21 시안의 릴과 같은 밀도이며, 잘린 오른쪽이 여기서도 "더 있다"를 말한다.
 */
const DESKTOP_FRAME_WIDTH = 132;
const DESKTOP_FRAME_HEIGHT = 198;

/** 요약에 올릴 맛 수. 8종을 다 늘어놓으면 요약이 아니라 표가 된다 */
const SUMMARY_FLAVOR_COUNT = 4;

/** 스크롤로 다음 장을 잇는 수단이 있는 환경인지. 없으면 다음 장 칸이 버튼이 된다 */
const SUPPORTS_OBSERVER = typeof IntersectionObserver !== 'undefined';

/**
 * 다음 장을 미리 부르는 거리. 다음 장 칸이 릴 오른쪽 끝에서 이만큼 남았을 때 부른다.
 * 프레임 세 칸쯤이라 넘기는 손이 끝에 닿기 전에 채워진다
 */
const PREFETCH_MARGIN = '0px 360px 0px 0px';

/** 지금 릴에 보이는 프레임 구간(1부터, 양끝 포함) */
type FrameRange = { first: number; last: number };

/**
 * 릴에서 온전히 보이는 프레임이 몇 번째부터 몇 번째인지(1부터). 모바일 `N / 전체`의 N은
 * `last`이고(업무 -680), 넓은 화면의 `1 – 6 / 73`은 두 수를 모두 쓴다(업무 -783).
 *
 * `last`는 왼쪽 끝이 아니라 오른쪽 끝을 센다. 끝까지 넘기면 `전체 / 전체`가 되어 "다 봤다"가
 * 맞게 읽힌다. 왼쪽 끝을 세면 마지막 칸에 닿아도 `22 / 24`에서 멈춘다. 칸 간격은 첫 두 칸의
 * 위치 차로 잰다 — 칸 폭 · 간격 · 테두리가 CSS에 있어 여기서 다시 계산하면 어긋난다.
 */
function visibleFrameRange(track: HTMLElement, frameCount: number): FrameRange {
  const [first, second] = track.children as unknown as HTMLElement[];

  if (!first || frameCount <= 1)
    return { first: Math.min(frameCount, 1), last: Math.min(frameCount, 1) };

  const step = second ? second.offsetLeft - first.offsetLeft : first.offsetWidth;

  // 레이아웃이 없는 환경(테스트)은 폭이 0이다. 첫 칸이 보인다고 본다
  if (step <= 0) return { first: 1, last: 1 };

  const visibleRight = track.scrollLeft + track.clientWidth;
  const count = Math.floor((visibleRight - first.offsetLeft - first.offsetWidth) / step) + 1;
  const last = Math.min(Math.max(count, 1), frameCount);
  // 한 번에 몇 칸이 들어오는지. 모바일처럼 두 칸 반만 보이면 1이 된다
  const fits = Math.max(Math.floor(track.clientWidth / step), 1);

  return { first: Math.max(last - fits + 1, 1), last };
}

/** 다음 장 칸의 상태. `failed`면 자동으로 다시 부르지 않고 버튼으로 잇는다 */
type MoreState = 'idle' | 'loading' | 'failed';

/** 빈 필름에 남겨 두는 자리. 시안 01이 두 칸을 보여준다 */
const EMPTY_SLOT_LABELS = ['첫 번째 프레임', '두 번째 프레임'];

type FilmGuideStep = {
  id: string;
  title: string;
  description: string;
  targetSelector: string;
  placement?: 'top' | 'bottom';
};

const FILM_GUIDE_STEPS: FilmGuideStep[] = [
  {
    id: 'reel',
    title: '감상 기록을 넘겨보세요',
    description:
      '처음 고른 영화부터 앞으로 평가할 영화까지 필름에 쌓여요. 옆으로 넘기며 다시 볼 수 있어요.',
    targetSelector: "[data-guide-target='film-reel']",
  },
  {
    id: 'popcorn',
    title: '나의 취향 팝콘',
    description:
      '평가한 영화가 맛별 팝콘으로 모여요. 누르면 어떤 취향이 많은지 자세히 볼 수 있어요.',
    targetSelector: "[aria-label^='나의 취향 팝콘']",
  },
  {
    id: 'report',
    title: '취향 리포트',
    description: '영화 감상 기록을 분석한 리포트를 확인하는 곳이에요.',
    targetSelector: "[aria-label='취향 리포트 보기'],[aria-label='새 취향 리포트 받기']",
  },
  {
    id: 'notifications',
    title: '새 소식을 확인하세요',
    description: '새 추천이나 리포트 같은 소식은 알림에서 확인할 수 있어요.',
    targetSelector: "a[aria-label^='알림']",
  },
  {
    id: 'rate',
    title: '본 영화를 평가해 보세요',
    description:
      '이 버튼과 상단 검색 모두 검색 화면으로 가요. 본 영화를 찾아 원할 때 평가하면 필름이 늘어나요.',
    targetSelector: "[data-guide-target='film-rate']",
    placement: 'top',
  },
  {
    id: 'recommendations',
    title: '영화 추천을 받아보세요',
    description: '지금 취향에 맞는 추천 영화를 보러 갈 수 있어요.',
    targetSelector: "[data-guide-target='film-recommendations']",
    placement: 'top',
  },
];

export default function FilmPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [guideDismissed, setGuideDismissed] = useState(false);
  const showGuide = Boolean(user && !guideDismissed && !hasSeenGuide(user.userId, FILM_GUIDE));
  const [guideStep, setGuideStep] = useState<{ userId: number; id: string } | null>(null);
  const finishGuide = () => {
    if (user) completeGuide(user.userId, FILM_GUIDE);
    setGuideDismissed(true);
  };
  const desktop = useShellSize() !== 'mobile';
  const asideSlot = useDesktopAsideSlot();

  const [frames, setFrames] = useState<FrameList | null>(null);
  const [more, setMore] = useState<MoreState>('idle');
  const moreRef = useRef<HTMLSpanElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  /** 지금 릴에 보이는 프레임 구간. 모바일은 `last`만, 넓은 화면은 둘 다 쓴다 */
  const [range, setRange] = useState<FrameRange>({ first: 1, last: 1 });
  const [bucket, setBucket] = useState<PopcornBucket | null>(null);
  const [bucketSettled, setBucketSettled] = useState(false);
  /** 아직 3.3에서 받지 않은 새 리포트. 있으면 리포트 줄이 발급(3.3)으로 간다 */
  const [newReportId, setNewReportId] = useState<number | null>(null);
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
      .catch(() => undefined)
      .finally(() => {
        if (active) setBucketSettled(true);
      });

    // 새 리포트를 확인하지 못해도 리포트 줄은 목록(7.3)으로 가는 원래 동작을 한다
    getLatestReadyReport()
      .then((latest) => {
        if (active) {
          setNewReportId(latest?.needsIntro ? latest.reportId : null);
        }
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
   * 넘길 때마다 `N / 전체`의 N을 다시 잰다. 한 프레임에 한 번만 잰다 — 스크롤 이벤트는 그보다
   * 잦다. 다음 장이 붙어 칸이 늘거나 릴의 폭이 바뀌어도 다시 잰다.
   *
   * **창 크기가 아니라 릴 자체를 지켜본다**(`ResizeObserver`, 업무 -783). 우측 보조 패널은
   * 페이지가 내용을 보낸 뒤에야 자리를 차지하므로(`components/DesktopAside`) 창 크기가 그대로인
   * 채로 중앙 단이 좁아진다. 창의 `resize`만 듣던 동안에는 그때 잰 값이 그대로 남아, 1280px에서
   * 넉 장만 보이는데 `1 – 6`이라고 적혀 있었다(2026-09-22 브라우저 확인).
   */
  const loadedFrameCount = frames?.items.length ?? 0;

  useEffect(() => {
    const track = trackRef.current;

    if (!track) return;

    let frame = 0;
    const measure = () => {
      frame = 0;
      setRange(visibleFrameRange(track, loadedFrameCount));
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(measure);
    };

    measure();
    track.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule);

    // 없는 환경(테스트)에서는 스크롤과 창 크기만으로 잰다
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);

    observer?.observe(track);

    return () => {
      cancelAnimationFrame(frame);
      observer?.disconnect();
      track.removeEventListener('scroll', schedule);
      window.removeEventListener('resize', schedule);
    };
  }, [loadedFrameCount]);

  /**
   * 넓은 화면의 `‹ ›`. 한 번에 한 화면씩 넘긴다(업무 -783).
   *
   * 칸 수가 아니라 보이는 폭만큼 옮긴다 — 칸 폭과 간격이 CSS에 있어 여기서 다시 세면 어긋난다.
   */
  function scrollFrames(direction: 1 | -1) {
    const track = trackRef.current;

    if (!track) return;

    track.scrollBy({ left: track.clientWidth * direction, behavior: 'smooth' });
  }

  if (failed) {
    return (
      <>
        <AppHeader />

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
  const showFilmGuide = showGuide && frames !== null && bucketSettled;
  const hasAside = !desktop || asideSlot !== null;
  const guideSteps = showFilmGuide
    ? FILM_GUIDE_STEPS.filter((step) => {
        if (step.title === '나의 취향 팝콘') {
          return !isEmpty && hasAside && summaryFlavors.length > 0;
        }
        if (step.title === '취향 리포트') return !isEmpty && hasAside;
        return true;
      }).map((step) =>
        step.title === '영화 추천을 받아보세요' && desktop
          ? {
              ...step,
              targetSelector: "nav[aria-label='주요 화면'] a[href$='/recommendations']",
            }
          : step,
      )
    : [];
  const savedGuideStep = user
    ? guideStep?.userId === user.userId
      ? guideStep.id
      : readGuideProgress(user.userId, FILM_GUIDE)
    : null;
  const activeGuideIndex = Math.max(
    0,
    guideSteps.findIndex((step) => step.id === savedGuideStep),
  );
  const activeGuideStep = guideSteps[activeGuideIndex];
  const guidePlacement =
    !desktop && (activeGuideStep?.id === 'popcorn' || activeGuideStep?.id === 'report')
      ? 'top'
      : activeGuideStep?.placement;
  const moveGuideTo = (id: string) => {
    if (!user) return;
    setGuideProgress(user.userId, FILM_GUIDE, id);
    setGuideStep({ userId: user.userId, id });
  };
  const continueAfterRate = () => {
    if (showGuide && guideSteps[activeGuideIndex]?.id === 'rate') {
      const next = guideSteps[activeGuideIndex + 1];
      if (next) moveGuideTo(next.id);
    }
  };

  return (
    <>
      <AppHeader />

      <div className={styles.page}>
        {/*
          제목 영역. 넓은 화면은 오른쪽에 `‹ 1 – 6 / 73 ›`가 붙어 가로로 놓이고, 모바일은
          지금까지처럼 세로 한 단이다. 둘을 한 덩어리로 묶지 않는 것은 `.head`의 세로 간격이
          자식 사이에 걸려 있어 감싸는 요소를 하나 끼우면 간격이 무너지기 때문이다.
        */}
        {desktop ? (
          <header className={styles.desktopHead}>
            <div className={styles.head}>
              <p className={styles.eyebrow}>YOUR TASTE ON FILM</p>
              <h1 className={styles.title}>나의 취향 필름</h1>

              {frames ? (
                <p className={styles.subtitle}>
                  {isEmpty ? '아직 첫 프레임 전이에요' : `${totalCount}개의 프레임이 감겼어요`}
                </p>
              ) : null}
            </div>

            {!isEmpty ? (
              <Link
                to={path.search()}
                className={styles.desktopRate}
                data-guide-target="film-rate"
                onClick={continueAfterRate}
              >
                본 영화 평가하기
              </Link>
            ) : null}

            {!isEmpty && items.length > 1 ? (
              <div className={styles.pager}>
                <button
                  type="button"
                  className={styles.pagerButton}
                  aria-label="이전 프레임 보기"
                  disabled={range.first <= 1}
                  onClick={() => scrollFrames(-1)}
                >
                  <ChevronLeftIcon />
                </button>

                <p className={styles.pagerLabel}>
                  <span aria-hidden="true">
                    {range.first} – {range.last} / {totalCount}
                  </span>
                  <VisuallyHidden>
                    전체 {totalCount}개 중 {range.first}번째부터 {range.last}번째 프레임까지 보고
                    있어요
                  </VisuallyHidden>
                </p>

                <button
                  type="button"
                  className={styles.pagerButton}
                  aria-label="다음 프레임 보기"
                  disabled={range.last >= totalCount}
                  onClick={() => scrollFrames(1)}
                >
                  <ChevronRightIcon />
                </button>
              </div>
            ) : null}
          </header>
        ) : (
          <header className={styles.head}>
            <p className={styles.eyebrow}>YOUR TASTE ON FILM</p>
            <h1 className={styles.title}>나의 취향 필름</h1>

            {frames ? (
              <p className={styles.subtitle}>
                {isEmpty ? '아직 첫 프레임 전이에요' : `${totalCount}개의 프레임이 감겼어요`}
              </p>
            ) : null}
          </header>
        )}

        <div className={styles.reelArea} data-guide-target="film-reel">
          {/* 넓은 화면의 필름은 주황빛이 없는 검정이다(2026-09-21 PC 웹 시안) */}
          <FilmReel
            tone={desktop ? 'plain' : 'warm'}
            frameWidth={desktop ? DESKTOP_FRAME_WIDTH : FRAME_WIDTH}
            frameHeight={desktop ? DESKTOP_FRAME_HEIGHT : FRAME_HEIGHT}
            label={isEmpty ? '아직 비어 있는 취향 필름' : '취향 필름 프레임'}
            trackRef={trackRef}
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
                    onClick={() => {
                      finishGuide();
                      void navigate(path.movieDetail(frame.movie.movieId));
                    }}
                  >
                    <PosterCue>
                      <Poster
                        width={desktop ? DESKTOP_FRAME_WIDTH : FRAME_WIDTH}
                        height={desktop ? DESKTOP_FRAME_HEIGHT : FRAME_HEIGHT}
                        radius={0}
                        src={frame.movie.posterUrl ?? undefined}
                        title=""
                        seed={frame.movie.movieId}
                      />
                    </PosterCue>
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

          {/*
           * 지금 위치와 넘기는 방향(업무 -680, UI/UX 분석 리포트 P1-02). 잘린 오른쪽이 "더 있다"는
           * 말해 주지만, 옆으로 넘긴다는 것과 몇 번째까지 왔는지는 말해 주지 않았다.
           *
           * 끝까지 넘기면 화살표 힌트를 거둔다 — 더 넘길 곳이 없는데 오른쪽을 가리키면 틀린 말이다.
           * 위치는 넘길 때마다 바뀌므로 읽기 도구에 매번 알리지 않는다(`aria-live` 없음).
           *
           * **넓은 화면에는 두지 않는다**(업무 -783). 같은 것을 제목 옆 `‹ 1 – 6 / 73 ›`가
           * 말하고, 시안에도 이 줄이 없다.
           */}
          {!desktop && !isEmpty && items.length > 1 ? (
            <p className={styles.reelHint}>
              <span className={styles.reelCounter}>
                <span aria-hidden="true">
                  {range.last} / {totalCount}
                </span>
                <VisuallyHidden>
                  전체 {totalCount}개 중 {range.last}번째 프레임까지 보고 있어요
                </VisuallyHidden>
              </span>

              {range.last < totalCount ? (
                <span className={styles.reelSwipe}>
                  옆으로 넘겨보세요
                  <ChevronRightIcon />
                </span>
              ) : null}
            </p>
          ) : null}
        </div>

        {/*
          넓은 화면에서는 팝콘 · 리포트 줄이 우측 보조 패널로 가고(`FilmAside`) 이 자리에
          `오늘의 추천`이 선다(업무 -783). 모바일은 지금까지와 같다.
        */}
        {isEmpty ? (
          <div className={styles.empty}>
            <h3 className={styles.emptyTitle}>아직 필름에 담긴 영화가 없어요</h3>
            <p className={styles.emptyText}>본 영화를 평가하면 필름에 한 장씩 추가돼요</p>
            <div className={styles.actionButtons}>
              <Button
                variant="primary"
                data-guide-target="film-rate"
                onClick={() => {
                  continueAfterRate();
                  void navigate(path.search());
                }}
              >
                본 영화 평가하기
              </Button>
              <Button
                variant="brand"
                data-guide-target="film-recommendations"
                onClick={() => void navigate(path.recommendations())}
              >
                영화 추천받기
              </Button>
            </div>
          </div>
        ) : desktop ? (
          <TodayRecommendations />
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

            {/* 검색에서 본 영화를 골라 평가하고, 추천은 기존 2.2로 보낸다(업무 -885). */}
            <div className={styles.action}>
              <p className={styles.actionHint}>본 영화를 평가하면 필름에 한 장씩 추가돼요</p>
              <div className={styles.actionButtons}>
                <Button
                  variant="primary"
                  data-guide-target="film-rate"
                  onClick={() => {
                    continueAfterRate();
                    void navigate(path.search());
                  }}
                >
                  본 영화 평가하기
                </Button>
                <Button
                  variant="brand"
                  data-guide-target="film-recommendations"
                  onClick={() => void navigate(path.recommendations())}
                >
                  영화 추천받기
                </Button>
              </div>
            </div>
          </div>
        )}
      </div>

      {/*
        우측 보조 패널. 1280px 이상에서만 자리가 있고, 좁으면 `DesktopAside`가 아무것도
        그리지 않는다(업무 -782). 필름이 비어 있으면 보여줄 요약도 없어 보내지 않는다.
      */}
      {desktop && !isEmpty ? (
        <DesktopAside>
          <FilmAside bucket={bucket} frames={items} newReportId={newReportId} />
        </DesktopAside>
      ) : null}
      {showGuide && guideSteps.length > 0 ? (
        <GuideCallout
          step={activeGuideIndex + 1}
          totalSteps={guideSteps.length}
          targetSelector={guideSteps[activeGuideIndex].targetSelector}
          placement={guidePlacement}
          title={guideSteps[activeGuideIndex].title}
          description={guideSteps[activeGuideIndex].description}
          onSkip={finishGuide}
          action={
            activeGuideIndex < guideSteps.length - 1
              ? { label: '다음', onClick: () => moveGuideTo(guideSteps[activeGuideIndex + 1].id) }
              : { label: '둘러보기', onClick: finishGuide }
          }
          secondaryAction={
            activeGuideIndex === guideSteps.length - 1
              ? {
                  label: '파티 가이드 보기',
                  onClick: () => {
                    finishGuide();
                    if (user) restartGuide(user.userId, PARTY_GUIDE);
                    void navigate(path.party());
                  },
                }
              : undefined
          }
        />
      ) : null}
    </>
  );
}
