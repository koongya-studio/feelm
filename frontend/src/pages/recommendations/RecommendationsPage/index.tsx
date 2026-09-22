/*
 * 2.2 오늘의 추천 · 2.2-A 추천을 불러오지 못한 경우
 * 2.3 추천 카드 ⋯ 메뉴 · 2.3-A 관심없음 처리도 이 화면의 상태다
 * (frontend-structure.md 3.3 — 라우트로 만들지 않는다).
 *
 * 카드 구성은 계약을 따라 취향 맞춤 2편 + 취향 발견 1편이다(API-045,
 * screen-spec.md 8.1 #3). 와이어프레임은 3 + 2 = 5장이지만 계약이 기준이다. 취향 맞춤을
 * 먼저, 취향 발견을 뒤에 둔다.
 *
 * **`취향 맞춤 추천` · `취향 발견 추천` 이름표는 없다**(사용자 지시 2026-09-18, 업무 -672).
 * 필름 칸 사이에 끼우던 이름표와 발견 설명 문장을 걷어냈다. 취향 발견 카드인지는 카드의
 * `새로운 맛` 배지가 말하고, 취향 맞춤 카드의 `안 봄` 배지는 뺐다(`RecommendationCard`).
 *
 * **카드는 API-045 실제 응답이다**(업무 -610). 목업 카드가 목업 영화 번호(101~104)로 실제
 * 영화 상세를 열어 전혀 다른 영화가 나오던 문제를 없앴다. 일치도 `%`는 계약에 없어 그리지
 * 않는다(CR-13).
 *
 * **관심없음은 카드 오른쪽 위 버튼이다**(사용자 지시 2026-09-18, 업무 -672 — -536을 합쳤다).
 * ⋯ 메뉴에서는 뺐다. 누르면 API-047에 `recommendationItemId`를 실어 보내고, 성공하면 그
 * 카드를 빼고 API-045를 다시 불러 서버가 보충한 카드로 채운다(API-047 처리 규칙 — 응답은
 * `{ movieId }`뿐이다). 이미 관심없음인 영화(`409 ALREADY_DISMISSED`)도 처리된 것으로 본다.
 * 실패하면 카드를 그대로 두고 알린다 — 화면에서만 지운 척하면 다음 조회에서 되살아난다.
 *
 * 추천 후보가 모두 빠진 경우(관심없음 · 온보딩 `안 좋았어요`)에도 이 화면에 보여줄 것이
 * 없다. 와이어프레임에 그 상태가 없어 2.2-A와 같은 자리에 빈 데이터 안내를 둔다
 * (업무 -264).
 *
 * **추천 후보가 아직 없는 것은 장애가 아니다**(업무 -654). 신규 사용자처럼 파이프라인이 후보를
 * 만들기 전이면 API-045가 `503 RECOMMENDATION_UNAVAILABLE`을 주는데, 이때 2.2-A의
 * `추천을 잠시 불러오지 못했어요`를 보면 사용자가 고장으로 읽는다. 그 경우만 준비 중 안내로
 * 바꾸고, 네트워크 단절 · 서버 오류 같은 실제 실패는 와이어프레임 2.2-A 문구를 그대로 둔다
 * (문구는 사용자 결정 2026-09-18).
 *
 * ─────────────────────────── 데스크톱 시안 (업무 -783)
 *
 * 1024px 이상에서는 2026-09-21 시안의 배치를 쓴다. **모바일 화면은 한 줄도 바뀌지 않는다** —
 * 폭에 따라 그리는 것 자체가 갈린다(`hooks/useShellSize`).
 *
 * - 숨겨 두었던 화면 제목이 드러난다. `오늘의 추천`과 오늘 날짜가 한 줄이다
 * - 카드가 **취향 맞춤 · 취향 발견 두 필름**으로 나뉘어 나란히 선다(`RecommendationGroup`).
 *   묶음 이름표를 되살린 것은 좌우로 놓인 두 필름이 이름 없이는 구분되지 않기 때문이고,
 *   412px에서 이름표를 뺀 결정(업무 -672)은 그대로다
 * - 오른쪽에 추천 기준 · 최근 본 영화가 붙는다(`RecommendationsAside`)
 * - **아래 `포스터를 탭하면 ⋯` 안내를 넓은 화면에서 뺀다**(사용자 결정 2026-09-22). 시안에
 *   없고, 마우스로 누르는 화면에서 `탭`이라고 말하는 줄이기도 하다
 *
 * 카드 열은 **3.1과 같은 필름 릴을 세로로 세운 것**이다(`FilmReel`, 업무 -605 — 사용자
 * 지시 2026-09-18). 그전에는 와이어프레임의 회색 판에 구멍 줄을 직접 그렸는데, 3.1의
 * 필름이 확정되고 나서 추천 카드가 다른 필름처럼 보였다.
 */
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import Button from '../../../components/Button';
import DesktopAside from '../../../components/DesktopAside';
import FilmReel from '../../../components/FilmReel';
import Menu from '../../../components/Menu';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { LinkIcon, PlayIcon } from '../../../components/Icon';
import Snackbar from '../../../components/Snackbar';
import RecommendationCard from './RecommendationCard';
import RecommendationGroup from './RecommendationGroup';
import RecommendationsAside from './RecommendationsAside';
import { ApiError } from '../../../api/apiError';
import { dismissMovie, getRecommendations } from '../../../api/recommendations';
import type { RecommendationSize } from '../../../api/recommendations';
import { getOtts } from '../../../api/otts';
import { useShellSize } from '../../../hooks/useShellSize';
import { formatMonthDayWeekday } from '../../../utils/date';
import { path } from '../../../routes/paths';
import styles from './RecommendationsPage.module.css';
import type { MouseEvent } from 'react';
import type { Ott } from '../../../types/ott';
import type { RecommendationItem } from '../../../types/recommendation';

/*
 * 카드 크기. 릴의 칸 크기가 되고 카드는 그 칸을 채운다(RecommendationCard).
 *
 * **필름이 화면 폭을 꽉 채운다**(사용자 지시 2026-09-18, 업무 -672 — "필름이 너무 작다").
 * 세로 필름은 칸 폭에 구멍 띠 · 여백 34px(12 × 2 + 5 × 2, FilmReel)가 붙는다. 화면 폭은
 * `.scrollArea`의 컨테이너 폭(`cqw`)이다 — `100vw`는 PC에서 세로 스크롤바 폭까지 넣어
 * 가로로 넘친다.
 *
 * **띠를 휘지 않는다**(`flat`). 세로 필름 띠는 가운데가 폭의 2%만큼 오목하게 휘어 있어
 * 화면 폭에 맞추면 가운데에서 양옆 벽이 8px쯤 비쳤다(사용자 지적 2026-09-19). 필름을 넓혀
 * 넘치게 하면 이번에는 구멍 줄이 화면 밖으로 밀려 잘렸다. 곧은 띠라야 테두리가 화면 끝에
 * 딱 붙고 구멍 줄도 온전하다. 칸 폭 = 화면 폭 − 34.
 *
 * 높이는 와이어프레임 카드(156 × 220)와 같은 비율로 폭을 따라간다.
 */
const FRAME_WIDTH = 'calc(100cqw - 34px)';
const FRAME_HEIGHT = 'calc((100cqw - 34px) * 220 / 156)';

/**
 * API-045 실패의 종류. `preparing`은 추천 후보가 아직 없어 서버가 추천을 만들 수 없는 경우다
 * (`503 RECOMMENDATION_UNAVAILABLE`). 나머지는 모두 `error`다
 */
type Failure = 'preparing' | 'error';

/** ⋯ 메뉴의 폭(px). `components/Menu`의 실측값이며 누른 버튼에 맞출 때 쓴다 */
const MENU_WIDTH = 148;

/** 넓은 화면의 묶음 하나에 서는 카드 수. 2026-09-21 시안이 묶음마다 석 장이다 */
const DESKTOP_GROUP_SIZE = 3;

function failureOf(error: unknown): Failure {
  return error instanceof ApiError && error.code === 'RECOMMENDATION_UNAVAILABLE'
    ? 'preparing'
    : 'error';
}

type OpenMenu = {
  item: RecommendationItem;
  /** 화면 위쪽에서의 거리(px). 누른 버튼 위치에 맞춘다 */
  top: number;
  /**
   * 왼쪽에서의 거리(px). 넓은 화면에서만 있다 — 412px에서는 카드와 화면의 오른쪽 끝이
   * 같아 메뉴가 오른쪽에 붙는 것으로 충분하지만, 좌우로 갈린 두 묶음에서는 누른 카드
   * 쪽에 열려야 한다(업무 -783)
   */
  left?: number;
};

export default function RecommendationsPage() {
  const rootRef = useRef<HTMLDivElement>(null);
  const mountedRef = useRef(true);
  const desktop = useShellSize() !== 'mobile';
  /*
   * 요청할 노출 편수 (업무 S15P21E106-794). 넓은 화면은 묶음마다 석 장이 서므로 여섯 편이
   * 필요하고, 모바일은 지금까지와 같은 세 편이다(API-045 `size`).
   *
   * 폭이 기준을 넘나들면 그 폭에 맞춰 다시 부른다. API-045는 조회가 곧 노출 기록이라 다시
   * 부르는 것이 공짜는 아니지만, **추천 입력과 요청 편수가 같으면 같은 세트를 준다**(계약).
   * 즉 되돌아온 폭에서는 이미 본 카드가 그대로 오고 카드가 뒤바뀌지 않는다.
   */
  const size: RecommendationSize = desktop ? 6 : 3;
  /** 비동기 관심없음 처리가 끝나는 순간의 화면 폭에 맞는 편수를 읽는다 */
  const sizeRef = useRef<RecommendationSize>(size);
  const [otts, setOtts] = useState<Ott[]>([]);
  const [items, setItems] = useState<RecommendationItem[] | null>(null);
  /** 이 추천을 만든 시각(API-045 `generatedAt`). 넓은 화면의 날짜 줄이 쓴다 */
  const [generatedAt, setGeneratedAt] = useState<string | null>(null);
  /** 관심없음을 처리 중인 카드. 그동안 그 카드의 버튼은 눌리지 않는다 */
  const [dismissingId, setDismissingId] = useState<number | null>(null);
  /** 관심없음을 처리하지 못했으면 하단 막대로 알린다 */
  const [dismissFailed, setDismissFailed] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [openMenu, setOpenMenu] = useState<OpenMenu | null>(null);

  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    sizeRef.current = size;
  }, [size]);

  useEffect(() => {
    mountedRef.current = true;

    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    // OTT 기준정보는 실패해도 배지가 코드를 쓰므로 화면을 막지 않는다
    getOtts()
      .then(setOtts)
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    let active = true;

    getRecommendations(size)
      .then((set) => {
        if (active) {
          setItems(set.items);
          setGeneratedAt(set.generatedAt);
          setFailure(null);
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setFailure(failureOf(error));
        }
      });

    return () => {
      active = false;
    };
  }, [reloadCount, size]);

  /** 2.2-A 다시 시도 */
  function handleRetry() {
    setItems(null);
    setFailure(null);
    setOpenMenu(null);
    setReloadCount((count) => count + 1);
  }

  /**
   * 관심없음(API-047). 서버가 받은 뒤에만 카드를 뺀다. 그다음 API-045를 다시 불러 빈 자리를
   * 서버가 보충한 카드로 바꾼다 — 다시 부르지 못해도 뺀 카드는 그대로 둔다(저장은 끝났다).
   */
  function handleDismiss(item: RecommendationItem) {
    setDismissingId(item.recommendationItemId);
    setDismissFailed(false);
    setOpenMenu(null);

    dismissMovie(item.movie.movieId, item.recommendationItemId)
      .catch((error: unknown) => {
        // 이미 관심없음인 영화다. 서버 상태는 이미 원하는 대로다
        if (!(error instanceof ApiError && error.code === 'ALREADY_DISMISSED')) throw error;
      })
      .then(() => {
        // 셸이 PC/모바일 사이에서 교체되면 새 페이지가 현재 편수를 이미 다시 요청한다
        if (!mountedRef.current) return;

        setItems((prev) =>
          prev
            ? prev.filter((card) => card.recommendationItemId !== item.recommendationItemId)
            : prev,
        );

        const refillSize = sizeRef.current;

        return getRecommendations(refillSize)
          .then((set) => {
            // 보충 요청 중 폭이 다시 바뀌었다면 그 폭의 useEffect 응답만 화면에 반영한다
            if (sizeRef.current === refillSize) {
              setItems(set.items);
              setGeneratedAt(set.generatedAt);
            }
          })
          .catch(() => undefined);
      })
      .catch(() => {
        if (mountedRef.current) setDismissFailed(true);
      })
      .finally(() => {
        if (mountedRef.current) setDismissingId(null);
      });
  }

  function handleOpenMenu(event: MouseEvent<HTMLButtonElement>, item: RecommendationItem) {
    if (openMenu?.item.recommendationItemId === item.recommendationItemId) {
      setOpenMenu(null);

      return;
    }

    const root = rootRef.current;
    const buttonRect = event.currentTarget.getBoundingClientRect();
    const rootRect = root?.getBoundingClientRect();
    const top = buttonRect.top - (rootRect?.top ?? 0);

    if (!desktop) {
      setOpenMenu({ item, top });

      return;
    }

    // 누른 버튼의 오른쪽 끝에 메뉴의 오른쪽 끝을 맞춘다
    const left = Math.max(buttonRect.right - (rootRect?.left ?? 0) - MENU_WIDTH, 0);

    setOpenMenu({ item, top, left });
  }

  const tasteItems = items?.filter((item) => item.type === 'TASTE') ?? [];
  const discoveryItems = items?.filter((item) => item.type === 'DISCOVERY') ?? [];

  /*
   * 넓은 화면의 두 묶음 (업무 S15P21E106-794, 사용자 결정 2026-09-22).
   *
   * **서버가 채워 준 자리는 채워진 대로 세운다.** 취향 발견 후보가 모자라면 그 슬롯을 맞춤이
   * 대신 채워 여섯 편이 온다(업무 -793). 유형으로만 가르면 그 대체분이 모두 맞춤 묶음으로 몰려
   * 발견 묶음이 비고, 시안의 좌우 두 단이 한 단으로 무너진다 — 카드 수는 늘었는데 화면은
   * 예전보다 못해진다.
   *
   * 그래서 발견 묶음은 **발견 카드를 먼저 놓고 남는 자리를 맞춤의 나머지로 채운다.** 묶음 이름은
   * 자리의 이름이고, 그 자리에 무엇이 왔는지는 서버가 정한다.
   */
  const desktopTasteItems = tasteItems.slice(0, DESKTOP_GROUP_SIZE);
  const desktopDiscoveryItems = [...discoveryItems, ...tasteItems.slice(DESKTOP_GROUP_SIZE)].slice(
    0,
    DESKTOP_GROUP_SIZE,
  );

  if (failure) {
    const preparing = failure === 'preparing';

    return (
      <>
        <ScreenHeader title={<VisuallyHidden>오늘의 추천</VisuallyHidden>} />

        {/* 2.2-A 추천을 불러오지 못한 경우. 후보가 아직 없으면 준비 중으로 안내한다 */}
        <StatusView
          title={preparing ? '취향에 맞는 추천을 준비하고 있어요' : '추천을 잠시 불러오지 못했어요'}
          description={
            preparing
              ? '잠시 후 다시 확인해 주세요.\n그동안 영화를 평가하면 추천이 더 정확해져요'
              : '검색과 영화 평가는 계속 이용할 수 있어요'
          }
        >
          <Button onClick={handleRetry}>{preparing ? '다시 확인하기' : '다시 시도'}</Button>
          {/* 검색(4.1)은 범위 밖이며 탭에서 안내 화면으로 이동한다 */}
          <Link to={path.search()} className={styles.searchLink}>
            영화 검색하기
          </Link>
        </StatusView>
      </>
    );
  }

  // 후보가 모두 빠져 보여줄 카드가 없는 경우 (와이어프레임에 없는 상태)
  if (items && items.length === 0) {
    return (
      <>
        <ScreenHeader title={<VisuallyHidden>오늘의 추천</VisuallyHidden>} />

        <StatusView
          variant="empty"
          title="추천할 영화를 아직 찾지 못했어요"
          description="관심없음으로 넘긴 영화가 많으면 새 추천이 준비될 때까지 기다려야 해요"
        >
          <Button onClick={handleRetry}>다시 시도</Button>
        </StatusView>
      </>
    );
  }

  return (
    <>
      {/*
       * **화면 제목은 보이지 않는다**(사용자 지시 2026-09-18, 업무 -605). 필름이 곧 화면이고
       * 하단 내비의 `필름`에 표시가 있다. 화면마다 h1이 하나는 있어야 하므로 글자는 남기고
       * 보이지만 않게 한다(7.1과 같은 처리). 오류 · 빈 상태는 안내가 화면을 채우므로
       * 헤더 자리만 남기고 제목을 숨긴다.
       */}
      {desktop ? null : (
        <h1>
          <VisuallyHidden>오늘의 추천</VisuallyHidden>
        </h1>
      )}

      <div className={desktop ? styles.desktopPage : styles.page} ref={rootRef}>
        {desktop ? (
          <>
            <header className={styles.desktopHead}>
              <div className={styles.desktopHeading}>
                <p className={styles.eyebrow}>TODAY&rsquo;S RECOMMENDATION</p>
                <h1 className={styles.desktopTitle}>오늘의 추천</h1>
                <p className={styles.desktopSubtitle}>
                  지금, 당신의 취향에 딱 맞는 영화를 골랐어요
                </p>
              </div>

              {generatedAt ? (
                <p className={styles.date}>
                  <span className={styles.dateValue}>{formatMonthDayWeekday(generatedAt)}</span>
                  <span className={styles.dateNote}>오늘도 좋은 영화와 함께해요</span>
                </p>
              ) : null}
            </header>

            <div className={styles.groups}>
              {/*
               * 묶음 설명은 고정 문구다. 시안은 그 자리에 첫 카드의 추천 이유를 적었지만,
               * 그 문장은 카드 안에 이미 있고 카드가 바뀌면 묶음 이름 아래 설명까지 흔들린다.
               */}
              <RecommendationGroup
                title="취향 맞춤 추천"
                description="좋아한 영화와 가장 가까운 편이에요"
                items={desktopTasteItems}
                otts={otts}
                openMenuId={openMenu?.item.recommendationItemId ?? null}
                onOpenMenu={handleOpenMenu}
                dismissingId={dismissingId}
                onDismiss={handleDismiss}
              />

              <RecommendationGroup
                title="취향 발견 추천"
                description="기존 취향과 연결되는 새로운 맛이에요"
                items={desktopDiscoveryItems}
                otts={otts}
                openMenuId={openMenu?.item.recommendationItemId ?? null}
                onOpenMenu={handleOpenMenu}
                dismissingId={dismissingId}
                onDismiss={handleDismiss}
              />
            </div>
          </>
        ) : (
          <>
            <div className={styles.scrollArea}>
              {items ? (
                <FilmReel
                  orientation="vertical"
                  flat
                  frameWidth={FRAME_WIDTH}
                  frameHeight={FRAME_HEIGHT}
                  label="오늘의 추천 카드"
                >
                  {[...tasteItems, ...discoveryItems].map((item) => (
                    <RecommendationCard
                      key={item.recommendationItemId}
                      item={item}
                      otts={otts}
                      menuOpen={openMenu?.item.recommendationItemId === item.recommendationItemId}
                      onOpenMenu={handleOpenMenu}
                      dismissing={dismissingId === item.recommendationItemId}
                      onDismiss={handleDismiss}
                    />
                  ))}
                </FilmReel>
              ) : null}
            </div>

            <p className={styles.hint}>포스터를 탭하면 상세 화면 · ⋯ 버튼을 탭하면 OTT 메뉴</p>
          </>
        )}

        {/* 2.3 추천 카드 ⋯ 메뉴 */}
        {openMenu ? (
          <Menu
            label={`${openMenu.item.movie.title} OTT 메뉴`}
            title="OTT · 바로보기"
            top={openMenu.top}
            left={openMenu.left}
            onClose={() => setOpenMenu(null)}
          >
            <ul className={styles.menuOttList}>
              {openMenu.item.movie.ottProviders.map((code) => {
                const ott = otts.find((candidate) => candidate.code === code);

                return (
                  <li key={code}>
                    <OttBadge code={code} name={ott?.name ?? code} size={28} />
                  </li>
                );
              })}
            </ul>

            <p className={styles.menuOttNames}>
              {openMenu.item.movie.ottProviders
                .map((code) => otts.find((candidate) => candidate.code === code)?.name ?? code)
                .join(' · ')}
            </p>

            <hr className={styles.menuDivider} />

            <div className={styles.menuItems}>
              {/*
               * OTT 바로보기 주소는 API-030 영화 상세의 `ottProviders[].watchUrl`에만 있고
               * 추천 응답(API-045)에는 없다. OTT가 여러 개일 때 어느 것으로 이동하는지도
               * 미확정이다(screen-spec.md 2.3). 결정 전까지 동작하지 않는다.
               */}
              <button type="button" className={styles.menuItem} disabled>
                <PlayIcon />
                <span className={styles.menuItemLabel}>OTT에서 바로 보기</span>
              </button>

              <Link
                to={path.movieDetail(openMenu.item.movie.movieId)}
                state={{ recommendationItemId: openMenu.item.recommendationItemId }}
                className={styles.menuItem}
                onClick={() => setOpenMenu(null)}
              >
                <LinkIcon />
                <span className={styles.menuItemLabel}>영화 상세 보기</span>
              </Link>
            </div>
          </Menu>
        ) : null}

        {/* 관심없음을 처리하지 못했을 때. 카드는 그대로 남아 다시 누를 수 있다 */}
        {dismissFailed ? (
          <div className={styles.notice}>
            <Snackbar actionLabel="확인" onAction={() => setDismissFailed(false)}>
              관심없음으로 처리하지 못했어요. 잠시 후 다시 눌러 주세요
            </Snackbar>
          </div>
        ) : null}
      </div>

      {/* 우측 보조 패널. 1280px 이상에서만 자리가 있다(업무 -782) */}
      {desktop ? (
        <DesktopAside>
          <RecommendationsAside items={items ?? []} />
        </DesktopAside>
      ) : null}
    </>
  );
}
