/*
 * 1.1 영화 취향 초기 설정
 *
 * 요소와 문구는 와이어프레임 1.1 그대로다(screen-spec.md 4장 1.1). 다만 건너뛰기 문구는
 * 계약을 따라 `차차 찾아갈래요`다(FR-02, screen-spec.md 8.1 #12).
 *
 * 카드 중심이 점선 원 안이면 LIKE, 밖이면 DISLIKE다. 처음 받은 카드도 원 밖의
 * DISLIKE로 시작한다.
 *
 * 조작은 드래그 한 가지다. 카드를 원 안팎에 놓으면 LIKE/DISLIKE가 정해지고, 보지 않은
 * 영화는 `⊖`로 목록에서 뺀다. 카드 탭 선택 버튼은 S15P21E106-542에서 제거했다.
 *
 * 모든 카드는 원 밖의 DISLIKE로 시작해 그대로 제출 대상이다. `⊖`로 제거한 카드만
 * 보내지 않는다(API-024 처리 규칙).
 *
 * 와이어프레임 하단에는 건너뛰기 버튼만 있고 완료 버튼이 없다. 새 요소를 추가하지 않기
 * 위해 같은 자리의 문구를 상태에 따라 바꾼다(팀 결정 2026-09-07).
 * 주 버튼은 `취향 설정 완료 (좋아요 n개 · 싫어요 n개)`로 현재 판정을 보여주고,
 * `차차 찾아갈래요`는 아래의 작은 글자 버튼으로 항상 둔다.
 *
 * 팀 결정 2026-09-10 (업무 -98)
 * - **주 동작을 검정 알약으로 세웠다.** 직접 만든 버튼이 밝은 면에 얇은 테두리였다.
 *   시안 2.4장은 주 버튼을 검정 알약으로 정하고 한 화면에 하나만 둔다. 공통 `Button`을
 *   쓰고, 고른 것이 없을 때의 건너뛰기는 글자 버튼으로 내렸다 — 할 행동이 없는 상태가
 *   알약 자리를 차지할 이유가 없다
 * - **가운데 표식(나)을 낮췄다.** 2px 검정 테두리에 흰 면이라 카드와 무게를 다퉜다.
 *   검정은 알약 하나뿐이어야 한다
 * - **점선 원을 얇게 하고 안쪽 면을 한 톤 낮췄다.** 공간이 포스터보다 강하게 읽혔다.
 *   이 화면의 주인공은 포스터다(시안 2.2장 — 색이 보이면 그것은 콘텐츠)
 *
 * 카드 개수는 10장으로 정했다(팀 결정 2026-09-15 · `CARD_COUNT`). 서버는
 * S15P21E106-542에서 확정한 10편을 기획 순서로 내려주고, 화면은 받은 순서를 그대로
 * 늘어놓는다(API-023 처리 규칙).
 *
 * 모든 카드에 처음부터 판정값이 있으므로 별도 최소 선택 개수 제한을 두지 않는다.
 * 초기 배치 위치는 와이어프레임의 카드 6장 좌표에 네 자리를 더해 쓴다.
 *
 * 팀 결정 2026-09-15 (업무 -449) — **이 화면은 카드를 받기 전과 빈 목록에 상태를 둔다.**
 * `StatusView`는 `로딩 상태는 만들지 않는다`(팀 결정 2026-09-08)를 따르고 있고 다른
 * 화면은 데이터를 받기 전에 아무것도 그리지 않아도 된다. 이 화면만 다른 이유는 **빈
 * 화면이 아니라 빈 공간이 남기 때문**이다. 점선 원과 가운데 표식은 카드와 무관하게
 * 항상 그려지므로, 카드가 없으면 사용자는 `아직 안 왔다`가 아니라 `이 화면은 고장났다`를
 * 본다. 그래서 카드가 없는 동안에는 배치 영역 자체를 세우지 않는다.
 *
 * 빈 목록은 실패가 아니다. 서버는 대표 맛이 배정된 영화로만 카드를 만들고(API-023
 * 처리 규칙 · ADR-0020), 배정이 아직 없으면 `200 {"items": []}`다. 오류 안내를 세우면
 * 정상 응답을 결함처럼 보여주므로 `empty`로 두고, 대신 **온보딩을 끝낼 길을 남긴다** —
 * 고를 카드가 없어도 건너뛰기로 1.2에 갈 수 있어야 한다.
 */
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { PersonIcon } from '../../../components/Icon';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import TasteCard from './TasteCard';
import { ApiError } from '../../../api/apiError';
import { getOnboardingCards, submitOnboardingPreferences } from '../../../api/onboarding';
import { useOnboarding } from '../../../contexts/onboardingContext';
import { path } from '../../../routes/paths';
import styles from './TasteOnboardingPage.module.css';
import type { PointerEvent } from 'react';
import type { OnboardingCard, OnboardingPreference } from '../../../types/onboarding';
import type { TasteValue } from './tasteValue';

const CARD_WIDTH = 88;
const CARD_HEIGHT = 124;

/** 점선 원. 와이어프레임 실측값 — 지름 292px, 중심은 배치 영역의 (50%, 47%) */
const RING_DIAMETER = 292;
const RING_CENTER_Y_RATIO = 0.47;

/**
 * 서버에 요청할 확정 카드 장수(API-023 `size`. 기본·최대 10).
 *
 * 확정 목록은 팝콘 맛 여덟 가지를 모두 포함한다. 10장보다 적게 요청하면 목록의
 * 뒷부분이 빠져 맛 커버리지가 깨질 수 있으므로 화면은 항상 전체를 요청한다.
 *
 * 화면이 늘어놓을 수 있는 장수이기도 하다. 초기 좌표가 이 수만큼 있어야 카드가 겹쳐
 * 쌓이지 않는다.
 */
const CARD_COUNT = 10;

/*
 * 카드 초기 좌표.
 *
 * 와이어프레임의 배치 영역은 412 x 653이다(892에서 헤더와 하단 영역을 뺀 값).
 * 실제 영역 높이는 헤더 · 하단 높이에 따라 달라지므로 좌표를 비율로 바꿔 둔다.
 * 그러지 않으면 영역이 짧을 때 카드가 가운데 표식과 겹친다.
 *
 * 88x124 카드 10장을 점선 원을 감싸는 10각형 형태로 놓는다. 412px 화면에도 가로로
 * 최대 네 장만 들어가는 크기다. 최초 카드는 원 밖의 흐린 DISLIKE이며, 점선 안은
 * 선명한 LIKE로 드래그 중에도 즉시 바뀐다.
 */
const WIREFRAME_BOARD = { width: 412, height: 653 };

/** `⊖`가 카드 밖으로 나가는 크기. 카드를 영역 안에 가둘 때 쓴다 */
const REMOVE_OVERHANG = 8;

/**
 * 카드가 화면 좌우 끝에 붙지 않게 두는 여백(업무 -389). 배치 영역은 화면 폭을 다 쓰므로,
 * 없으면 오른쪽 끝 카드의 `⊖`가 화면 가장자리에 닿아 320 · 344px 폭에서 잘려 보였다.
 */
const EDGE_INSET = 8;

const INITIAL_POSITIONS = [
  { x: 162, y: 8 },
  { x: 256, y: 58 },
  { x: 316, y: 178 },
  { x: 316, y: 312 },
  { x: 256, y: 448 },
  { x: 162, y: 513 },
  { x: 68, y: 448 },
  { x: 8, y: 312 },
  { x: 8, y: 178 },
  { x: 68, y: 58 },
].map((position) => ({
  xRatio: position.x / (WIREFRAME_BOARD.width - CARD_WIDTH - REMOVE_OVERHANG),
  yRatio: (position.y - REMOVE_OVERHANG) / (WIREFRAME_BOARD.height - CARD_HEIGHT - REMOVE_OVERHANG),
}));

/** 포인터 흔들림으로 상태가 바뀌지 않도록 이 거리를 넘은 뒤부터 드래그로 본다 */
const TAP_THRESHOLD = 5;

/**
 * 카드 요청의 진행 상태.
 *
 * `loading`과 `ready`를 가르지 않으면 요청이 끝나기 전과 카드가 0장인 뒤가 같은 화면이
 * 된다. 둘 다 `cards`가 빈 배열이기 때문이다.
 */
type LoadState = 'loading' | 'ready' | 'failed';

type CardState = {
  card: OnboardingCard;
  value: TasteValue;
  /** 처음 놓인 자리(`INITIAL_POSITIONS`의 번호). 키보드로 원 밖으로 되돌릴 때 쓴다 */
  slot: number;
  /** 카드가 움직일 수 있는 가로 거리의 비율 (0~1) */
  xRatio: number;
  /** 카드가 움직일 수 있는 세로 거리의 비율 (0~1) */
  yRatio: number;
};

type DragState = {
  movieId: number;
  pointerId: number;
  /** 카드 왼쪽 위와 포인터의 간격 */
  offsetX: number;
  offsetY: number;
  startX: number;
  startY: number;
  moved: boolean;
};

export default function TasteOnboardingPage() {
  const navigate = useNavigate();
  const { completeTaste } = useOnboarding();

  const boardRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<DragState | null>(null);
  const [cards, setCards] = useState<CardState[]>([]);
  /** 카드 요청의 진행 상태. `loading`으로 시작해 화면이 빈 공간을 먼저 그리지 않게 한다 */
  const [loadState, setLoadState] = useState<LoadState>('loading');
  /** 서버가 카드를 0장 준 경우. `⊖`로 사용자가 전부 지운 것과 구분한다 */
  const [emptyList, setEmptyList] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [draggingId, setDraggingId] = useState<number | null>(null);
  /** 마지막으로 집은 카드는 손을 뗀 뒤에도 겹친 카드들보다 위에 둔다 */
  const [frontCardId, setFrontCardId] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [board, setBoard] = useState({ width: 0, height: 0 });

  /*
   * 배치 영역을 세우는 조건. 카드를 받기 전과 서버가 0장을 준 뒤에는 점선 원과 가운데
   * 표식만 남으므로 영역 자체를 그리지 않고 안내를 세운다.
   *
   * `⊖`로 전부 지워 `cards`가 빈 경우는 여기에 걸리지 않는다. 사용자가 직접 한 일이고
   * 하단에 건너뛰기가 그대로 있으므로 안내로 덮을 자리가 아니다.
   */
  const showBoard = loadState === 'ready' && !emptyList;

  /*
   * 배치 영역은 카드가 있을 때만 그리므로(`showBoard`) 이 효과도 그때 붙어야 한다.
   * 빈 deps로 두면 첫 렌더에 `boardRef.current`가 `null`이라 관측이 시작되지 않고,
   * 카드가 도착해도 `board.width`가 0에 머물러 한 장도 그려지지 않는다.
   */
  useEffect(() => {
    const element = boardRef.current;

    if (!element) {
      return;
    }

    const observer = new ResizeObserver(([entry]) => {
      setBoard({ width: entry.contentRect.width, height: entry.contentRect.height });
    });

    observer.observe(element);

    return () => {
      observer.disconnect();
    };
  }, [showBoard]);

  useEffect(() => {
    let active = true;

    getOnboardingCards(CARD_COUNT)
      .then((items) => {
        if (!active) {
          return;
        }

        setLoadState('ready');
        setEmptyList(items.length === 0);
        setCards(
          items.map((card, index) => ({
            card,
            value: 'DISLIKE' as TasteValue,
            slot: index % INITIAL_POSITIONS.length,
            ...INITIAL_POSITIONS[index % INITIAL_POSITIONS.length],
          })),
        );
      })
      .catch((error: unknown) => {
        if (!active) {
          return;
        }

        /*
         * 이미 온보딩을 마친 사용자다(API-023 `409`). 로그인은 `onboardingCompleted`로
         * 갈라 보내므로 여기로 오는 것은 주소를 직접 열었거나 다른 기기에서 마친
         * 경우다. 다시 할 수 없는 화면에 실패 안내를 세워 두지 않고 홈으로 보낸다.
         */
        if (error instanceof ApiError && error.code === 'ONBOARDING_ALREADY_COMPLETED') {
          void navigate(path.film(), { replace: true });

          return;
        }

        setLoadState('failed');
      });

    return () => {
      active = false;
    };
  }, [reloadCount, navigate]);

  /** 비율 좌표를 현재 화면에서 카드가 실제로 놓일 왼쪽 위 좌표로 바꾼다. */
  function positionFromRatio(xRatio: number, yRatio: number) {
    const minY = REMOVE_OVERHANG;
    const minX = EDGE_INSET;
    const maxX = Math.max(minX, board.width - CARD_WIDTH - REMOVE_OVERHANG - EDGE_INSET);
    const maxY = Math.max(minY, board.height - CARD_HEIGHT);

    return {
      x: minX + xRatio * (maxX - minX),
      y: minY + yRatio * (maxY - minY),
    };
  }

  /** 카드 중심이 점선 원 안인지 */
  function valueFromRatio(xRatio: number, yRatio: number): TasteValue {
    const centerX = board.width / 2;
    const centerY = board.height * RING_CENTER_Y_RATIO;
    const position = positionFromRatio(xRatio, yRatio);
    const cardCenterX = position.x + CARD_WIDTH / 2;
    const cardCenterY = position.y + CARD_HEIGHT / 2;
    const distance = Math.hypot(cardCenterX - centerX, cardCenterY - centerY);

    return distance <= RING_DIAMETER / 2 ? 'LIKE' : 'DISLIKE';
  }

  /**
   * 카드 왼쪽 위 좌표(px)를 영역 안으로 가둔 비율로 바꾼다.
   * `⊖`가 카드 오른쪽 위로 8px 넘어가므로 그만큼 여유를 둔다.
   */
  function toRatio(x: number, y: number) {
    const minY = REMOVE_OVERHANG;
    const minX = EDGE_INSET;
    const maxX = Math.max(minX, board.width - CARD_WIDTH - REMOVE_OVERHANG - EDGE_INSET);
    const maxY = Math.max(minY, board.height - CARD_HEIGHT);

    return {
      xRatio: maxX === minX ? 0 : (Math.min(Math.max(x, minX), maxX) - minX) / (maxX - minX),
      yRatio: maxY === minY ? 0 : (Math.min(Math.max(y, minY), maxY) - minY) / (maxY - minY),
    };
  }

  function handlePointerDown(event: PointerEvent<HTMLButtonElement>, state: CardState) {
    const element = boardRef.current;

    if (!element) {
      return;
    }

    const boardRect = element.getBoundingClientRect();
    const position = positionFromRatio(state.xRatio, state.yRatio);

    dragRef.current = {
      movieId: state.card.movieId,
      pointerId: event.pointerId,
      offsetX: event.clientX - boardRect.left - position.x,
      offsetY: event.clientY - boardRect.top - position.y,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
    };

    event.currentTarget.setPointerCapture(event.pointerId);
    setFrontCardId(state.card.movieId);
    setDraggingId(state.card.movieId);
  }

  function handlePointerMove(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    const element = boardRef.current;

    if (!drag || !element || drag.pointerId !== event.pointerId) {
      return;
    }

    if (!drag.moved) {
      if (Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) <= TAP_THRESHOLD) {
        return;
      }

      drag.moved = true;
    }

    const boardRect = element.getBoundingClientRect();
    const next = toRatio(
      event.clientX - boardRect.left - drag.offsetX,
      event.clientY - boardRect.top - drag.offsetY,
    );

    setCards((previous) =>
      previous.map((item) =>
        item.card.movieId === drag.movieId
          ? { ...item, ...next, value: valueFromRatio(next.xRatio, next.yRatio) }
          : item,
      ),
    );
  }

  function handlePointerUp(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;

    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }

    dragRef.current = null;
    setDraggingId(null);

    // 위치와 LIKE/DISLIKE는 pointermove에서 함께 갱신한다. 손을 떼기 전에도 경계를
    // 넘나드는 즉시 포스터 투명도와 접근성 라벨이 바뀌어야 하기 때문이다.
  }

  /**
   * 키보드로 원 안팎을 오간다(업무 -610). 드래그와 같은 판정 함수를 쓴다.
   *
   * 원 안으로 넣을 때는 이미 안에 있는 카드 수만큼 조금씩 비껴 놓아 겹치지 않게 하고,
   * 밖으로 뺄 때는 처음 놓였던 자리로 돌려보낸다.
   */
  function toggleCard(movieId: number) {
    setCards((previous) => {
      const target = previous.find((item) => item.card.movieId === movieId);

      if (!target) {
        return previous;
      }

      if (target.value === 'LIKE') {
        const home = INITIAL_POSITIONS[target.slot];

        return previous.map((item) =>
          item.card.movieId === movieId ? { ...item, ...home, value: 'DISLIKE' } : item,
        );
      }

      const insideCount = previous.filter((item) => item.value === 'LIKE').length;
      const angle = insideCount * 2.4;
      const spread = Math.min(insideCount, 3) * 14;
      const next = toRatio(
        board.width / 2 - CARD_WIDTH / 2 + Math.cos(angle) * spread,
        board.height * RING_CENTER_Y_RATIO - CARD_HEIGHT / 2 + Math.sin(angle) * spread,
      );

      return previous.map((item) =>
        item.card.movieId === movieId
          ? { ...item, ...next, value: valueFromRatio(next.xRatio, next.yRatio) }
          : item,
      );
    });
    setFrontCardId(movieId);
  }

  /** 방향키로 조금 옮긴다. 경계를 넘으면 드래그와 같이 판정이 바뀐다 */
  function nudgeCard(movieId: number, dx: number, dy: number) {
    setCards((previous) =>
      previous.map((item) => {
        if (item.card.movieId !== movieId) {
          return item;
        }

        const position = positionFromRatio(item.xRatio, item.yRatio);
        const next = toRatio(position.x + dx, position.y + dy);

        return { ...item, ...next, value: valueFromRatio(next.xRatio, next.yRatio) };
      }),
    );
    setFrontCardId(movieId);
  }

  function removeCard(movieId: number) {
    setCards((previous) => previous.filter((item) => item.card.movieId !== movieId));
    setFrontCardId((previous) => (previous === movieId ? null : previous));
  }

  async function submit(skipped: boolean) {
    const preferences: OnboardingPreference[] = skipped
      ? []
      : cards.map((item) => ({
          movieId: item.card.movieId,
          preference: item.value,
        }));

    setSubmitting(true);
    setFailure(null);

    try {
      await submitOnboardingPreferences(preferences, skipped);
      completeTaste(preferences, skipped);
      void navigate(path.onboardingOtt());
    } catch (error) {
      // 같은 사용자의 제출이 이미 저장됐다면 다시 보낼 것이 없다(API-024 `409`)
      if (error instanceof ApiError && error.code === 'ONBOARDING_ALREADY_COMPLETED') {
        void navigate(path.film(), { replace: true });

        return;
      }

      setFailure(
        error instanceof ApiError
          ? error.message
          : '저장하지 못했어요. 잠시 후 다시 시도해 주세요.',
      );
      setSubmitting(false);
    }
  }

  const likeCount = cards.filter((item) => item.value === 'LIKE').length;
  const dislikeCount = cards.filter((item) => item.value === 'DISLIKE').length;
  const header = <ScreenHeader title="취향 초기 설정" align="center" />;
  /*
   * 다시 시도. 상태를 여기서 `loading`으로 되돌린다 — 요청 효과 본문에서 되돌리면
   * 렌더가 연쇄된다(`react-hooks/set-state-in-effect`). 첫 요청은 초깃값이 이미
   * `loading`이라 되돌릴 것이 없다.
   */
  const retry = (
    <Button
      onClick={() => {
        setLoadState('loading');
        setReloadCount((count) => count + 1);
      }}
    >
      다시 시도
    </Button>
  );

  /* 카드를 받기 전. 빈 공간 대신 무엇을 기다리는지 세운다 */
  if (loadState === 'loading') {
    return (
      <>
        {header}

        <StatusView variant="empty" title="영화 카드를 준비하고 있어요" />
      </>
    );
  }

  if (loadState === 'failed') {
    return (
      <>
        {header}

        <StatusView title="영화 카드를 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
          {retry}
        </StatusView>
      </>
    );
  }

  /*
   * 서버가 카드를 0장 줬다. 정상 응답이므로 오류 표식을 붙이지 않고, 고를 카드가 없어도
   * 온보딩을 끝낼 수 있도록 건너뛰기를 함께 둔다.
   */
  if (emptyList) {
    return (
      <>
        {header}

        <StatusView
          variant="empty"
          title="지금은 보여드릴 영화가 없어요"
          description="잠시 후 다시 시도해 주세요"
        >
          {retry}

          {failure ? (
            <p className={styles.failure} role="alert">
              {failure}
            </p>
          ) : null}

          <Button variant="text" disabled={submitting} onClick={() => void submit(true)}>
            차차 찾아갈래요
          </Button>
        </StatusView>
      </>
    );
  }

  return (
    <>
      {header}

      <div className={styles.intro}>
        {/* `⊖로`는 한 덩어리다. 어절 단위 줄바꿈(-389)이 기호 뒤를 끊어 `로`만 다음 줄로 넘겼다 */}
        <p className={styles.guide}>
          카드를 원 안팎으로 드래그해 주세요 · 안 본 영화는{' '}
          <span className={styles.nowrap}>⊖로 빼주세요</span>
        </p>
        <VisuallyHidden>
          <p>키보드로는 카드를 고르고 Enter로 원 안팎을 오가거나 방향키로 옮길 수 있어요</p>
        </VisuallyHidden>
        <ul className={styles.legend}>
          <li>● 가까이 = 좋아요</li>
          <li>○ 멀리 = 싫어요</li>
          <li>⊖ 안 봤어요</li>
        </ul>
      </div>

      <div
        className={styles.board}
        ref={boardRef}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
      >
        <span className={styles.ring} aria-hidden="true" />
        <span className={styles.center} aria-hidden="true">
          <PersonIcon />
        </span>

        {(board.width > 0 ? cards : []).map((item) => {
          const position = positionFromRatio(item.xRatio, item.yRatio);

          return (
            <TasteCard
              key={item.card.movieId}
              movieId={item.card.movieId}
              title={item.card.title}
              posterUrl={item.card.posterUrl}
              value={item.value}
              x={position.x}
              y={position.y}
              dragging={draggingId === item.card.movieId}
              front={frontCardId === item.card.movieId}
              onPointerDown={(event) => handlePointerDown(event, item)}
              onToggle={() => toggleCard(item.card.movieId)}
              onNudge={(dx, dy) => nudgeCard(item.card.movieId, dx, dy)}
              onRemove={() => removeCard(item.card.movieId)}
            />
          );
        })}
      </div>

      <div className={styles.footer}>
        {failure ? (
          <p className={styles.failure} role="alert">
            {failure}
          </p>
        ) : null}

        <Button disabled={submitting} onClick={() => void submit(false)}>
          취향 설정 완료 (좋아요 {likeCount}개 · 싫어요 {dislikeCount}개)
        </Button>

        <Button variant="text" disabled={submitting} onClick={() => void submit(true)}>
          차차 찾아갈래요
        </Button>
      </div>
    </>
  );
}
