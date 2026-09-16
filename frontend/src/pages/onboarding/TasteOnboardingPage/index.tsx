/*
 * 1.1 영화 취향 초기 설정
 *
 * 요소와 문구는 와이어프레임 1.1 그대로다(screen-spec.md 4장 1.1). 다만 건너뛰기 문구는
 * 계약을 따라 `차차 찾아갈래요`다(FR-02, screen-spec.md 8.1 #12).
 *
 * 판정 기준은 와이어프레임의 안내 문구가 정하고 있다.
 * `공간 안에 담으면 좋아한 영화, 밖에 두면 안 좋아한 영화예요`
 * 따라서 카드 중심이 점선 원 안이면 LIKE, 밖이면 DISLIKE다. 임의로 정한 값이 아니다.
 *
 * 조작은 두 가지다(와이어프레임 하단 힌트).
 * - 드래그해서 원 안팎에 놓는다
 * - 카드를 탭하면 `가까이 / 멀리 / 안 봤어요` 버튼이 열린다. 키보드도 이 경로를 쓴다
 *
 * 저장은 명시적으로 선택한 LIKE · DISLIKE만 한다. `안 봤어요`와 `⊖`로 제거한 카드는
 * 보내지 않는다(API-024 처리 규칙).
 *
 * 와이어프레임 하단에는 건너뛰기 버튼만 있고 완료 버튼이 없다. 새 요소를 추가하지 않기
 * 위해 같은 자리의 문구를 상태에 따라 바꾼다(팀 결정 2026-09-07).
 * 선택 0개 → `차차 찾아갈래요 (건너뛰기)` · 1개 이상 → `취향 설정 완료 (n개)`
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
 * 카드 개수는 10장으로 정했다(팀 결정 2026-09-15 · `CARD_COUNT`). 카드 선정 기준은
 * 서버가 정한다 — 팝콘 맛이 고루 들어가도록 맛마다 인기 순위를 매겨 고른다
 * (API-023 처리 규칙). 화면은 받은 순서를 그대로 늘어놓는다.
 *
 * `[?]` 최소 선택 개수는 여전히 미확정이다. 초기 배치 위치는 와이어프레임의 카드 6장
 * 좌표에 네 자리를 더해 쓴다.
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
import TasteCard from './TasteCard';
import { ApiError } from '../../../api/apiError';
import { getOnboardingCards, submitOnboardingPreferences } from '../../../api/onboarding';
import { useOnboarding } from '../../../contexts/onboardingContext';
import { path } from '../../../routes/paths';
import { isSavedValue, tasteValueLabel } from './tasteValue';
import styles from './TasteOnboardingPage.module.css';
import type { PointerEvent } from 'react';
import type { OnboardingCard, OnboardingPreference } from '../../../types/onboarding';
import type { TasteValue } from './tasteValue';

const CARD_WIDTH = 58;
const CARD_HEIGHT = 82;

/** 점선 원. 와이어프레임 실측값 — 지름 292px, 중심은 배치 영역의 (50%, 47%) */
const RING_DIAMETER = 292;
const RING_CENTER_Y_RATIO = 0.47;

/**
 * 서버에 요청할 카드 장수(API-023 `size`. 기본 20 · 최대 40).
 *
 * 계약은 맛마다 인기 1순위를 한 장씩 깔고 다음 순위로 넘어가는 방식으로 카드를
 * 구성한다. 팝콘 맛이 여덟 가지이므로 8장보다 적게 받으면 **아예 나오지 않는 맛이
 * 생긴다.** 10장은 여덟 맛을 한 번씩 덮고 인기 있는 두 맛을 한 장 더 보는 수다
 * (팀 결정 2026-09-15, 업무 S15P21E106-379).
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
 * 앞의 여섯은 와이어프레임 1.1의 카드 6장 좌표 그대로다. 카드가 10장이 되면서
 * (`CARD_COUNT`) 네 자리를 같은 영역 아래쪽에 더했다. 서로 겹치지 않고 가운데 표식과도
 * 떨어지도록 잡은 값이다.
 */
const WIREFRAME_BOARD = { width: 412, height: 653 };

const INITIAL_POSITIONS = [
  { x: 104, y: 140 },
  { x: 254, y: 208 },
  { x: 152, y: 298 },
  { x: 24, y: 64 },
  { x: 330, y: 110 },
  { x: 316, y: 352 },
  { x: 240, y: 60 },
  { x: 40, y: 420 },
  { x: 330, y: 470 },
  { x: 120, y: 540 },
].map((position) => ({
  xRatio: position.x / WIREFRAME_BOARD.width,
  yRatio: position.y / WIREFRAME_BOARD.height,
}));

/** `⊖`가 카드 밖으로 나가는 크기. 카드를 영역 안에 가둘 때 쓴다 */
const REMOVE_OVERHANG = 8;

/** 이 거리 안에서 손을 떼면 드래그가 아니라 탭으로 본다 */
const TAP_THRESHOLD = 5;

/** 선택 버튼 묶음의 대략적인 폭. 화면 밖으로 나가지 않게 자리를 잡을 때만 쓴다 */
const PICKER_WIDTH = 232;

const SELECT_OPTIONS: TasteValue[] = ['LIKE', 'DISLIKE', 'UNWATCHED'];

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
  /** 배치 영역 폭에 대한 비율 (0~1) */
  xRatio: number;
  /** 배치 영역 높이에 대한 비율 (0~1) */
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
  /* 드래그가 끝난 뒤 따라오는 click을 탭으로 오해하지 않도록 한 번 무시한다 */
  const skipNextClickRef = useRef(false);

  const [cards, setCards] = useState<CardState[]>([]);
  /** 카드 요청의 진행 상태. `loading`으로 시작해 화면이 빈 공간을 먼저 그리지 않게 한다 */
  const [loadState, setLoadState] = useState<LoadState>('loading');
  /** 서버가 카드를 0장 준 경우. `⊖`로 사용자가 전부 지운 것과 구분한다 */
  const [emptyList, setEmptyList] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [openCardId, setOpenCardId] = useState<number | null>(null);
  const [draggingId, setDraggingId] = useState<number | null>(null);
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
            value: 'UNSET' as TasteValue,
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

  /** 카드 중심이 점선 원 안인지 */
  function valueFromRatio(xRatio: number, yRatio: number): TasteValue {
    const centerX = board.width / 2;
    const centerY = board.height * RING_CENTER_Y_RATIO;
    const cardCenterX = xRatio * board.width + CARD_WIDTH / 2;
    const cardCenterY = yRatio * board.height + CARD_HEIGHT / 2;
    const distance = Math.hypot(cardCenterX - centerX, cardCenterY - centerY);

    return distance <= RING_DIAMETER / 2 ? 'LIKE' : 'DISLIKE';
  }

  /**
   * 카드 왼쪽 위 좌표(px)를 영역 안으로 가둔 비율로 바꾼다.
   * `⊖`가 카드 오른쪽 위로 8px 넘어가므로 그만큼 여유를 둔다.
   */
  function toRatio(x: number, y: number) {
    const maxX = Math.max(0, board.width - CARD_WIDTH - REMOVE_OVERHANG);
    const maxY = Math.max(0, board.height - CARD_HEIGHT);

    return {
      xRatio: board.width === 0 ? 0 : Math.min(Math.max(x, 0), maxX) / board.width,
      yRatio: board.height === 0 ? 0 : Math.min(Math.max(y, REMOVE_OVERHANG), maxY) / board.height,
    };
  }

  /**
   * 버튼으로 값을 고르면 카드도 그 값에 맞는 자리로 옮긴다.
   * 원 중심에서의 각도는 카드 순서로 고르게 나누고, 거리는 값에 따라 정한다.
   */
  function positionForValue(value: TasteValue, index: number, total: number) {
    const centerX = board.width / 2;
    const centerY = board.height * RING_CENTER_Y_RATIO;
    // 가까이는 가운데 표식(84px)과 겹치지 않고 원 안에 들어오는 거리,
    // 나머지는 원 밖으로 확실히 나가는 거리다.
    const radius = (RING_DIAMETER / 2) * (value === 'LIKE' ? 0.7 : 1.15);
    const angle = (index / Math.max(1, total)) * Math.PI * 2 - Math.PI / 2;

    return toRatio(
      centerX + Math.cos(angle) * radius - CARD_WIDTH / 2,
      centerY + Math.sin(angle) * radius - CARD_HEIGHT / 2,
    );
  }

  function handlePointerDown(event: PointerEvent<HTMLButtonElement>, state: CardState) {
    const element = boardRef.current;

    if (!element) {
      return;
    }

    const boardRect = element.getBoundingClientRect();

    dragRef.current = {
      movieId: state.card.movieId,
      pointerId: event.pointerId,
      offsetX: event.clientX - boardRect.left - state.xRatio * board.width,
      offsetY: event.clientY - boardRect.top - state.yRatio * board.height,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
    };

    event.currentTarget.setPointerCapture(event.pointerId);
    setDraggingId(state.card.movieId);
  }

  function handlePointerMove(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    const element = boardRef.current;

    if (!drag || !element || drag.pointerId !== event.pointerId) {
      return;
    }

    if (Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) > TAP_THRESHOLD) {
      drag.moved = true;
    }

    const boardRect = element.getBoundingClientRect();
    const next = toRatio(
      event.clientX - boardRect.left - drag.offsetX,
      event.clientY - boardRect.top - drag.offsetY,
    );

    setCards((previous) =>
      previous.map((item) => (item.card.movieId === drag.movieId ? { ...item, ...next } : item)),
    );
  }

  function handlePointerUp(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;

    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }

    dragRef.current = null;
    setDraggingId(null);

    if (!drag.moved) {
      // 거의 움직이지 않았으면 탭이다. 선택 버튼을 여는 일은 click에서 처리한다.
      // 키보드 Enter도 click만 발생하므로 두 경로를 한곳에서 다룬다.
      return;
    }

    skipNextClickRef.current = true;
    setOpenCardId(null);
    setCards((previous) =>
      previous.map((item) =>
        item.card.movieId === drag.movieId
          ? { ...item, value: valueFromRatio(item.xRatio, item.yRatio) }
          : item,
      ),
    );
  }

  /** 탭과 키보드 조작을 함께 처리한다 */
  function handleCardClick(movieId: number) {
    if (skipNextClickRef.current) {
      skipNextClickRef.current = false;

      return;
    }

    setOpenCardId((previous) => (previous === movieId ? null : movieId));
  }

  function selectValue(movieId: number, value: TasteValue) {
    setCards((previous) =>
      previous.map((item, index) => {
        if (item.card.movieId !== movieId) {
          return item;
        }

        return { ...item, value, ...positionForValue(value, index, previous.length) };
      }),
    );
    setOpenCardId(null);
  }

  function removeCard(movieId: number) {
    setCards((previous) => previous.filter((item) => item.card.movieId !== movieId));
    setOpenCardId(null);
  }

  async function submit() {
    const preferences: OnboardingPreference[] = cards
      .filter((item) => isSavedValue(item.value))
      .map((item) => ({
        movieId: item.card.movieId,
        preference: item.value as OnboardingPreference['preference'],
      }));
    const skipped = preferences.length === 0;

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

  const savedCount = cards.filter((item) => isSavedValue(item.value)).length;
  const openCard = cards.find((item) => item.card.movieId === openCardId) ?? null;

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

          <Button variant="text" disabled={submitting} onClick={() => void submit()}>
            차차 찾아갈래요 (건너뛰기)
          </Button>
        </StatusView>
      </>
    );
  }

  return (
    <>
      {header}

      <div className={styles.intro}>
        <p className={styles.guide}>공간 안에 담으면 좋아한 영화, 밖에 두면 안 좋아한 영화예요</p>
        <ul className={styles.legend}>
          <li>● 가까이 = 좋아요</li>
          <li>○ 멀리 = 싫어요</li>
          <li>◌ 안 봤어요</li>
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

        {(board.width > 0 ? cards : []).map((item) => (
          <TasteCard
            key={item.card.movieId}
            movieId={item.card.movieId}
            title={item.card.title}
            posterUrl={item.card.posterUrl}
            value={item.value}
            x={item.xRatio * board.width}
            y={item.yRatio * board.height}
            dragging={draggingId === item.card.movieId}
            onPointerDown={(event) => handlePointerDown(event, item)}
            onClick={() => handleCardClick(item.card.movieId)}
            onRemove={() => removeCard(item.card.movieId)}
          />
        ))}

        {openCard ? (
          <div
            className={styles.picker}
            style={{
              left: `${Math.max(
                8,
                Math.min(
                  openCard.xRatio * board.width + CARD_WIDTH / 2 - PICKER_WIDTH / 2,
                  Math.max(8, board.width - PICKER_WIDTH - 8),
                ),
              )}px`,
              top: `${openCard.yRatio * board.height + CARD_HEIGHT + 10}px`,
            }}
            role="group"
            aria-label={`${openCard.card.title} 취향 선택`}
          >
            {SELECT_OPTIONS.map((option) => (
              <button
                key={option}
                type="button"
                className={styles.pickerOption}
                data-selected={openCard.value === option}
                aria-pressed={openCard.value === option}
                onClick={() => selectValue(openCard.card.movieId, option)}
              >
                {tasteValueLabel(option)}
              </button>
            ))}
          </div>
        ) : null}
      </div>

      <div className={styles.footer}>
        <p className={styles.hint}>
          드래그로 배치하거나, 카드를 탭해 버튼으로 선택할 수 있어요 · ⊖는 목록에서 제거
        </p>

        {/*
         * 검정 알약은 한 화면에 하나이고 그 하나가 `취향 설정 완료`다
         * (`docs/screens/design-concept.md` 2.4). 아직 고른 것이 없으면 할 행동이
         * 없으므로 건너뛰기를 글자 버튼으로 둔다. 같은 버튼이 문구만 바꾸던 이전
         * 구현은 건너뛰기까지 알약 자리를 차지했다.
         */}
        {failure ? (
          <p className={styles.failure} role="alert">
            {failure}
          </p>
        ) : null}

        {savedCount === 0 ? (
          <Button variant="text" disabled={submitting} onClick={() => void submit()}>
            차차 찾아갈래요 (건너뛰기)
          </Button>
        ) : (
          <Button disabled={submitting} onClick={() => void submit()}>
            취향 설정 완료 ({savedCount}개)
          </Button>
        )}
      </div>
    </>
  );
}
