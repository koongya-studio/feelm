/*
 * 2.2 오늘의 추천 · 2.2-A 추천을 불러오지 못한 경우
 * 2.3 추천 카드 ⋯ 메뉴 · 2.3-A 관심없음 처리도 이 화면의 상태다
 * (frontend-structure.md 3.3 — 라우트로 만들지 않는다).
 *
 * 카드 구성은 계약을 따라 취향 맞춤 2편 + 취향 발견 1편이다(API-045,
 * screen-spec.md 8.1 #3). 와이어프레임은 3 + 2 = 5장이지만 계약이 기준이다.
 * 섹션 이름도 계약을 따라 `취향 발견 추천`이다(FR-12, 8.1 #4).
 *
 * `?mock=error`로 2.2-A를 확인할 수 있다. 계약의 `503 RECOMMENDATION_UNAVAILABLE`이
 * 아니면 이 화면을 볼 방법이 없어 둔 목업 전용 입력이다(frontend-structure.md 7.4).
 * 실제 연동 시 제거한다.
 */
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import Button from '../../../components/Button';
import Menu from '../../../components/Menu';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import Snackbar from '../../../components/Snackbar';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { BlockIcon, LinkIcon, PlayIcon } from '../../../components/Icon';
import RecommendationCard from './RecommendationCard';
import { dismissMovie, getRecommendations } from '../../../api/recommendations';
import { getOtts } from '../../../api/otts';
import { path } from '../../../routes/paths';
import { ottBadgeLetter } from '../../../utils/ott';
import styles from './RecommendationsPage.module.css';
import type { MouseEvent } from 'react';
import type { Ott } from '../../../types/ott';
import type { RecommendationItemView } from '../../../types/unspecified';

type OpenMenu = {
  item: RecommendationItemView;
  /** 화면 위쪽에서의 거리(px). 누른 버튼 위치에 맞춘다 */
  top: number;
};

export default function RecommendationsPage() {
  const [searchParams] = useSearchParams();
  const scenario = searchParams.get('mock') === 'error' ? 'error' : 'success';

  const rootRef = useRef<HTMLDivElement>(null);
  const [otts, setOtts] = useState<Ott[]>([]);
  const [items, setItems] = useState<RecommendationItemView[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [openMenu, setOpenMenu] = useState<OpenMenu | null>(null);
  const [dismissedItems, setDismissedItems] = useState<RecommendationItemView[] | null>(null);

  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    void getOtts().then(setOtts);
  }, []);

  useEffect(() => {
    let active = true;

    getRecommendations(scenario)
      .then((set) => {
        if (active) {
          setItems(set.items);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    return () => {
      active = false;
    };
  }, [scenario, reloadCount]);

  /** 2.2-A 다시 시도 */
  function handleRetry() {
    setItems(null);
    setFailed(false);
    setOpenMenu(null);
    setDismissedItems(null);
    setReloadCount((count) => count + 1);
  }

  function handleOpenMenu(event: MouseEvent<HTMLButtonElement>, item: RecommendationItemView) {
    if (openMenu?.item.recommendationItemId === item.recommendationItemId) {
      setOpenMenu(null);

      return;
    }

    const root = rootRef.current;
    const buttonRect = event.currentTarget.getBoundingClientRect();
    const rootRect = root?.getBoundingClientRect();

    setOpenMenu({ item, top: buttonRect.top - (rootRect?.top ?? 0) });
  }

  async function handleDismiss(item: RecommendationItemView) {
    if (!items) {
      return;
    }

    const previous = items;
    const result = await dismissMovie(item.movie.movieId, item.recommendationItemId);

    setItems(
      previous.flatMap((candidate) => {
        if (candidate.recommendationItemId !== item.recommendationItemId) {
          return [candidate];
        }

        // 교체 후보가 없으면 카드가 사라진다.
        // `[?]` 이때의 표시는 screen-spec.md 2.3-A 미확정 항목이다.
        return result.replacement ? [result.replacement] : [];
      }),
    );
    setDismissedItems(previous);
    setOpenMenu(null);
  }

  function handleUndo() {
    // 관심없음 해제 API가 없어 화면 상태로만 되돌린다(CR-15).
    setItems(dismissedItems);
    setDismissedItems(null);
  }

  const tasteItems = items?.filter((item) => item.type === 'TASTE') ?? [];
  const discoveryItems = items?.filter((item) => item.type === 'DISCOVERY') ?? [];

  if (failed) {
    return (
      <>
        <ScreenHeader title="오늘의 추천" align="center" />

        {/* 2.2-A 추천을 불러오지 못한 경우 */}
        <div className={styles.errorArea}>
          <span className={styles.errorMark} aria-hidden="true">
            <span className={styles.errorMarkStrip} data-edge="top" />!
            <span className={styles.errorMarkStrip} data-edge="bottom" />
          </span>

          <div className={styles.errorText}>
            <p className={styles.errorTitle}>추천을 잠시 불러오지 못했어요</p>
            <p className={styles.errorHint}>검색과 영화 평가는 계속 이용할 수 있어요</p>
          </div>

          <div className={styles.errorActions}>
            <Button onClick={handleRetry}>다시 시도</Button>
            {/* 검색(4.1)은 범위 밖이며 탭에서 안내 화면으로 이동한다 */}
            <Link to={path.search()} className={styles.searchLink}>
              영화 검색하기
            </Link>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <ScreenHeader title="오늘의 추천" align="center" />

      <div className={styles.page} ref={rootRef}>
        <div className={styles.scrollArea}>
          {items ? (
            <div className={styles.strip}>
              <span className={styles.perforation} data-side="left" aria-hidden="true" />
              <span className={styles.perforation} data-side="right" aria-hidden="true" />

              <h2 className={styles.sectionPill}>취향 맞춤 추천</h2>

              {tasteItems.map((item) => (
                <RecommendationCard
                  key={item.recommendationItemId}
                  item={item}
                  otts={otts}
                  menuOpen={openMenu?.item.recommendationItemId === item.recommendationItemId}
                  onOpenMenu={handleOpenMenu}
                />
              ))}

              <div className={styles.sectionGroup}>
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
            </div>
          ) : null}
        </div>

        <p className={styles.hint}>포스터를 탭하면 상세 화면 · ⋯ 버튼을 탭하면 OTT 메뉴</p>

        {dismissedItems ? (
          <div className={styles.snackbarArea}>
            <Snackbar actionLabel="실행 취소" onAction={handleUndo}>
              <VisuallyHidden>관심없음 처리됨. </VisuallyHidden>이 영화는 이후 추천에서 제외돼요
            </Snackbar>
          </div>
        ) : null}

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
                    <OttBadge letter={ottBadgeLetter(code)} name={ott?.name ?? code} size={28} />
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
                className={styles.menuItem}
                onClick={() => setOpenMenu(null)}
              >
                <LinkIcon />
                <span className={styles.menuItemLabel}>영화 상세 보기</span>
              </Link>

              <button
                type="button"
                className={styles.menuItem}
                data-tone="muted"
                onClick={() => void handleDismiss(openMenu.item)}
              >
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
