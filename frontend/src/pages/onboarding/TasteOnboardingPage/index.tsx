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
 * 위해 같은 버튼의 문구를 상태에 따라 바꾼다(팀 결정 2026-09-07).
 * 선택 0개 → `차차 찾아갈래요 (건너뛰기)` · 1개 이상 → `취향 설정 완료 (n개)`
 *
 * `[?]` 카드 개수 · 카드 선정 기준 · 최소 선택 개수 · 초기 배치 위치는 미확정이다.
 * 초기 위치는 와이어프레임의 카드 6장 좌표를 그대로 쓴다.
 */
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { PersonIcon } from '../../../components/Icon';
import ScreenHeader from '../../../components/ScreenHeader';
import TasteCard from './TasteCard';
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

/*
 * 와이어프레임 카드 6장의 초기 좌표.
 *
 * 와이어프레임의 배치 영역은 412 x 653이다(892에서 헤더와 하단 영역을 뺀 값).
 * 실제 영역 높이는 헤더 · 하단 높이에 따라 달라지므로 좌표를 비율로 바꿔 둔다.
 * 그러지 않으면 영역이 짧을 때 카드가 가운데 표식과 겹친다.
 */
const WIREFRAME_BOARD = { width: 412, height: 653 };

const INITIAL_POSITIONS = [
  { x: 104, y: 140 },
  { x: 254, y: 208 },
  { x: 152, y: 298 },
  { x: 24, y: 64 },
  { x: 330, y: 110 },
  { x: 316, y: 352 },
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
  const [openCardId, setOpenCardId] = useState<number | null>(null);
  const [draggingId, setDraggingId] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [board, setBoard] = useState({ width: 0, height: 0 });

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
  }, []);

  useEffect(() => {
    let active = true;

    void getOnboardingCards().then((items) => {
      if (!active) {
        return;
      }

      setCards(
        items.map((card, index) => ({
          card,
          value: 'UNSET' as TasteValue,
          ...INITIAL_POSITIONS[index % INITIAL_POSITIONS.length],
        })),
      );
    });

    return () => {
      active = false;
    };
  }, []);

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
    await submitOnboardingPreferences(preferences, skipped);
    completeTaste(preferences, skipped);
    void navigate(path.onboardingOtt());
  }

  const savedCount = cards.filter((item) => isSavedValue(item.value)).length;
  const openCard = cards.find((item) => item.card.movieId === openCardId) ?? null;

  return (
    <>
      <ScreenHeader title="취향 초기 설정" align="center" />

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

        <button
          type="button"
          className={styles.submit}
          disabled={submitting}
          onClick={() => void submit()}
        >
          {savedCount === 0 ? '차차 찾아갈래요 (건너뛰기)' : `취향 설정 완료 (${savedCount}개)`}
        </button>
      </div>
    </>
  );
}
