/*
 * 2.2 오늘의 추천 · 2.2-A 추천을 불러오지 못한 경우
 * 2.3 추천 카드 ⋯ 메뉴 · 2.3-A 관심없음 처리도 이 화면의 상태다
 * (frontend-structure.md 3.3 — 라우트로 만들지 않는다).
 *
 * 카드 구성은 계약을 따라 취향 맞춤 2편 + 취향 발견 1편이다(API-045,
 * screen-spec.md 8.1 #3). 와이어프레임은 3 + 2 = 5장이지만 계약이 기준이다.
 * 섹션 이름도 계약을 따라 `취향 발견 추천`이다(FR-12, 8.1 #4).
 *
 * **카드는 API-045 실제 응답이다**(업무 -610). 목업 카드가 목업 영화 번호(101~104)로 실제
 * 영화 상세를 열어 전혀 다른 영화가 나오던 문제를 없앴다. 일치도 `%`는 계약에 없어 그리지
 * 않는다(CR-13). 관심없음(API-047)의 프론트 연동은 S15P21E106-536이 맡으므로 그전까지
 * 메뉴의 `관심없음`은 눌리지 않는다 — 목업으로 지운 척하는 것보다 낫다.
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
 * 카드 열은 **3.1과 같은 필름 릴을 세로로 세운 것**이다(`FilmReel`, 업무 -605 — 사용자
 * 지시 2026-09-18). 그전에는 와이어프레임의 회색 판에 구멍 줄을 직접 그렸는데, 3.1의
 * 필름이 확정되고 나서 추천 카드가 다른 필름처럼 보였다. 섹션 이름표는 프레임 사이에
 * 끼운다(`data-film-label`).
 */
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import Button from '../../../components/Button';
import FilmReel from '../../../components/FilmReel';
import Menu from '../../../components/Menu';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { BlockIcon, LinkIcon, PlayIcon } from '../../../components/Icon';
import RecommendationCard from './RecommendationCard';
import { ApiError } from '../../../api/apiError';
import { getRecommendations } from '../../../api/recommendations';
import { getOtts } from '../../../api/otts';
import { path } from '../../../routes/paths';
import styles from './RecommendationsPage.module.css';
import type { MouseEvent } from 'react';
import type { Ott } from '../../../types/ott';
import type { RecommendationItem } from '../../../types/recommendation';

/* 카드 실측값 그대로다(RecommendationCard). 릴의 칸 크기가 되고 릴은 이 폭에 맞춰 선다 */
const FRAME_WIDTH = 156;
const FRAME_HEIGHT = 220;

/**
 * API-045 실패의 종류. `preparing`은 추천 후보가 아직 없어 서버가 추천을 만들 수 없는 경우다
 * (`503 RECOMMENDATION_UNAVAILABLE`). 나머지는 모두 `error`다
 */
type Failure = 'preparing' | 'error';

function failureOf(error: unknown): Failure {
  return error instanceof ApiError && error.code === 'RECOMMENDATION_UNAVAILABLE'
    ? 'preparing'
    : 'error';
}

type OpenMenu = {
  item: RecommendationItem;
  /** 화면 위쪽에서의 거리(px). 누른 버튼 위치에 맞춘다 */
  top: number;
};

export default function RecommendationsPage() {
  const rootRef = useRef<HTMLDivElement>(null);
  const [otts, setOtts] = useState<Ott[]>([]);
  const [items, setItems] = useState<RecommendationItem[] | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [openMenu, setOpenMenu] = useState<OpenMenu | null>(null);

  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    // OTT 기준정보는 실패해도 배지가 코드를 쓰므로 화면을 막지 않는다
    getOtts()
      .then(setOtts)
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    let active = true;

    getRecommendations()
      .then((set) => {
        if (active) {
          setItems(set.items);
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
  }, [reloadCount]);

  /** 2.2-A 다시 시도 */
  function handleRetry() {
    setItems(null);
    setFailure(null);
    setOpenMenu(null);
    setReloadCount((count) => count + 1);
  }

  function handleOpenMenu(event: MouseEvent<HTMLButtonElement>, item: RecommendationItem) {
    if (openMenu?.item.recommendationItemId === item.recommendationItemId) {
      setOpenMenu(null);

      return;
    }

    const root = rootRef.current;
    const buttonRect = event.currentTarget.getBoundingClientRect();
    const rootRect = root?.getBoundingClientRect();

    setOpenMenu({ item, top: buttonRect.top - (rootRect?.top ?? 0) });
  }

  const tasteItems = items?.filter((item) => item.type === 'TASTE') ?? [];
  const discoveryItems = items?.filter((item) => item.type === 'DISCOVERY') ?? [];

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
      <h1>
        <VisuallyHidden>오늘의 추천</VisuallyHidden>
      </h1>

      <div className={styles.page} ref={rootRef}>
        <div className={styles.scrollArea}>
          {items ? (
            <FilmReel
              orientation="vertical"
              frameWidth={FRAME_WIDTH}
              frameHeight={FRAME_HEIGHT}
              label="오늘의 추천 카드"
            >
              <h2 className={styles.sectionPill} data-film-label="">
                취향 맞춤 추천
              </h2>

              {tasteItems.map((item) => (
                <RecommendationCard
                  key={item.recommendationItemId}
                  item={item}
                  otts={otts}
                  menuOpen={openMenu?.item.recommendationItemId === item.recommendationItemId}
                  onOpenMenu={handleOpenMenu}
                />
              ))}

              <div className={styles.sectionGroup} data-film-label="">
                <h2 className={styles.sectionPill} data-tone="dark">
                  취향 발견 추천
                </h2>
                <p className={styles.sectionNote}>기존 취향과 연결되는 새로운 팝콘 맛이에요</p>
              </div>

              {discoveryItems.map((item) => (
                <RecommendationCard
                  key={item.recommendationItemId}
                  item={item}
                  otts={otts}
                  menuOpen={openMenu?.item.recommendationItemId === item.recommendationItemId}
                  onOpenMenu={handleOpenMenu}
                />
              ))}
            </FilmReel>
          ) : null}
        </div>

        <p className={styles.hint}>포스터를 탭하면 상세 화면 · ⋯ 버튼을 탭하면 OTT 메뉴</p>

        {/* 2.3 추천 카드 ⋯ 메뉴 */}
        {openMenu ? (
          <Menu
            label={`${openMenu.item.movie.title} OTT 메뉴`}
            title="OTT · 바로보기"
            top={openMenu.top}
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

              {/*
               * 관심없음(API-047)은 백엔드에 있지만 프론트 연동은 S15P21E106-536이 맡는다.
               * 그전까지 눌리지 않는다 — 화면 상태로만 지운 척하면 다음 조회에서 되살아난다.
               */}
              <button type="button" className={styles.menuItem} data-tone="muted" disabled>
                <BlockIcon />
                <span className={styles.menuItemLabel}>관심없음</span>
              </button>
            </div>
          </Menu>
        ) : null}
      </div>
    </>
  );
}
