/*
 * 2.4 영화 상세
 *
 * 사용자 시안 2026-09-17(업무 -458)의 전면 포스터·어두운 본문 디자인을 유지한다.
 * 상세(API-030), 유사 영화(API-031), 공개 한줄평(API-032)은 실제 API다.
 * 한줄평은 서버 최신순을 20개씩 받아 화면에서 5개씩 펼친다.
 *
 * 주 동작은 평가 하나다. 기존 평가가 있으면 버튼 위에 내 평가 원문을 보여 주고
 * `내 별점 수정하기`로 이동한다. 별도 `내 감상평` 편집 섹션은 두지 않는다.
 * 공개 목록에서는 작성자가 본인이어도 `spoiler=true`인 항목을 기본 블러한다.
 */
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import Button from '../../../components/Button';
import IconButton from '../../../components/IconButton';
import OttBadge from '../../../components/OttBadge';
import Poster from '../../../components/Poster';
import RatingStars from '../../../components/RatingStars';
import StatusView from '../../../components/StatusView';
import { ChevronLeftIcon } from '../../../components/Icon';
import { ApiError } from '../../../api/apiError';
import { getMovieDetail, getMovieReviews, getSimilarMovies } from '../../../api/movies';
import { recordOttClick } from '../../../api/watch';
import { useAuth } from '../../../contexts/authContext';
import { useBack } from '../../../hooks/useBack';
import { path } from '../../../routes/paths';
import { movieMetaLine } from '../../../utils/movie';
import styles from './MovieDetailPage.module.css';
import type { CSSProperties, PointerEvent as ReactPointerEvent } from 'react';
import type { MovieDetail, SimilarMovie } from '../../../types/movie';
import type { PublicReview, PublicReviewList } from '../../../types/watch';

const BACK_BUTTON_SIZE = 39;
const SIMILAR_POSTER_WIDTH = 96;
const SIMILAR_POSTER_HEIGHT = 144;
const BACKDROP_FADE_RATIO = 0.55;
const REVIEW_FETCH_SIZE = 20;
const REVIEW_REVEAL_SIZE = 5;
/** 숨김 상태에서 실제 문장 길이와 형태를 노출하지 않는 고정 가림 문구 */
const SPOILER_PLACEHOLDER = '스포일러가 포함된 한줄평을 가려두었어요';

async function getReviewBatch(movieId: number, cursor?: string): Promise<PublicReviewList> {
  return getMovieReviews(movieId, cursor, REVIEW_FETCH_SIZE);
}

type Failure = 'notFound' | 'error';

export default function MovieDetailPage() {
  const { movieId } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  // 검색이나 추천에서 들어오는 화면이다. 바로 들어왔으면 검색으로 돌아간다(업무 -610)
  const back = useBack(path.search());
  const id = Number(movieId);

  const routeState = useLocation().state as { recommendationItemId?: number } | null;
  const queryItemId = Number(searchParams.get('recommendationItemId')) || undefined;
  const recommendationItemId = routeState?.recommendationItemId ?? queryItemId;

  /*
   * API-030은 **선택 인증**이라 토큰 없이 불러도 `200`이다. 그래서 세션 복구가 끝나기 전에
   * 부르면 비회원 응답이 그대로 굳는다 — `myRating` · `myWatchStatus` · `recommendation`이
   * `null`이고 모든 OTT의 `subscribed`가 `false`인 화면이다(계약 처리 규칙).
   *
   * `client.ts`의 재시도는 `401`일 때만 도는데 비회원 조회는 `200`이라 걸리지 않는다.
   * `AuthProvider`는 복구를 기다리지 않고 화면을 그리므로 콜드 로드에서 이 경합이 실제로
   * 일어나고, SPA 이동으로 들어오면 토큰이 메모리에 있어 같은 화면이 다르게 보인다.
   * 배포본에서 새로고침하면 내 평가와 구독 표시가 사라지는 것이 이 때문이었다(업무 -600).
   *
   * 그래서 `restoring` 동안에는 부르지 않는다. 비회원이 확정되면 그대로 비회원 조회다.
   * 유사 영화(API-031)와 한줄평(API-032)은 `required`라 `401` → 재발급 → 재시도로
   * 되살아나므로 기다릴 필요가 없다.
   */
  const { status: authStatus } = useAuth();
  const sessionSettled = authStatus !== 'restoring';

  const [movie, setMovie] = useState<MovieDetail | null>(null);
  const [similar, setSimilar] = useState<SimilarMovie[]>([]);
  const [reviewPage, setReviewPage] = useState<{
    movieId: number;
    items: PublicReview[];
    nextCursor: string | null;
    hasNext: boolean;
  } | null>(null);
  const [reviewVisibility, setReviewVisibility] = useState({
    movieId: id,
    count: REVIEW_REVEAL_SIZE,
  });
  const [loadingMoreReviews, setLoadingMoreReviews] = useState(false);
  const [reviewLoadFailed, setReviewLoadFailed] = useState(false);
  const [revealedReviews, setRevealedReviews] = useState<Set<string>>(() => new Set());
  const [failure, setFailure] = useState<Failure | null>(null);
  const [reloadCount, setReloadCount] = useState(0);
  const [clicking, setClicking] = useState(false);
  const [clickFailed, setClickFailed] = useState(false);
  const [popupBlocked, setPopupBlocked] = useState(false);
  const pageRef = useRef<HTMLDivElement>(null);
  const peopleDragRef = useRef<{
    pointerId: number;
    clientX: number;
    scrollLeft: number;
  } | null>(null);

  useEffect(() => {
    // 복구가 끝나기 전에 부르면 비회원 응답이 굳는다(위 주석)
    if (!sessionSettled) return;

    let active = true;

    getMovieDetail(id, recommendationItemId)
      .then((detail) => {
        if (active) {
          setMovie(detail);
          setFailure(null);
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setFailure(error instanceof ApiError && error.status === 404 ? 'notFound' : 'error');
        }
      });

    return () => {
      active = false;
    };
  }, [id, recommendationItemId, reloadCount, sessionSettled]);

  useEffect(() => {
    let active = true;

    getSimilarMovies(id)
      .then((items) => {
        if (active) setSimilar(items);
      })
      .catch(() => undefined);

    getReviewBatch(id)
      .then((page) => {
        if (active) {
          setReviewPage({ movieId: id, ...page });
          setReviewVisibility({ movieId: id, count: REVIEW_REVEAL_SIZE });
          setReviewLoadFailed(false);
        }
      })
      .catch(() => {
        if (active) setReviewLoadFailed(true);
      });

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [id]);

  useEffect(() => {
    const page = pageRef.current;
    if (!page) return;

    let frame = 0;
    const apply = () => {
      frame = 0;
      const end = window.innerHeight * BACKDROP_FADE_RATIO;
      const progress = end > 0 ? Math.min(1, window.scrollY / end) : 1;
      page.style.setProperty('--detail-scroll', progress.toFixed(3));
    };
    const onScroll = () => {
      if (frame === 0) frame = window.requestAnimationFrame(apply);
    };

    apply();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll, { passive: true });

    return () => {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      if (frame !== 0) window.cancelAnimationFrame(frame);
    };
  }, [movie]);

  const bufferedReviews = reviewPage?.movieId === id ? reviewPage.items : [];
  const visibleReviewCount =
    reviewVisibility.movieId === id ? reviewVisibility.count : REVIEW_REVEAL_SIZE;
  const reviews = bufferedReviews.slice(0, visibleReviewCount);
  const hasMoreReviews =
    visibleReviewCount < bufferedReviews.length || Boolean(reviewPage?.hasNext);

  function reviewKey(ratingId: number) {
    return `${id}:${ratingId}`;
  }

  function isReviewHidden(review: PublicReview) {
    return review.spoiler && !revealedReviews.has(reviewKey(review.ratingId));
  }

  function handleRetry() {
    setFailure(null);
    setReloadCount((count) => count + 1);
  }

  function handleBackToTop() {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function handlePeoplePointerDown(event: ReactPointerEvent<HTMLUListElement>) {
    if (event.pointerType !== 'mouse' || event.button !== 0) return;

    peopleDragRef.current = {
      pointerId: event.pointerId,
      clientX: event.clientX,
      scrollLeft: event.currentTarget.scrollLeft,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
    event.preventDefault();
  }

  function handlePeoplePointerMove(event: ReactPointerEvent<HTMLUListElement>) {
    const drag = peopleDragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;

    event.currentTarget.scrollLeft = drag.scrollLeft - (event.clientX - drag.clientX);
    event.preventDefault();
  }

  function finishPeopleDrag(event: ReactPointerEvent<HTMLUListElement>) {
    const drag = peopleDragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;

    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    peopleDragRef.current = null;
  }

  async function handleLoadMoreReviews() {
    if (!reviewPage || loadingMoreReviews) return;

    const nextVisibleCount = visibleReviewCount + REVIEW_REVEAL_SIZE;
    if (nextVisibleCount <= reviewPage.items.length || !reviewPage.hasNext) {
      setReviewVisibility({
        movieId: id,
        count: Math.min(nextVisibleCount, reviewPage.items.length),
      });
      return;
    }
    if (!reviewPage.nextCursor) return;

    setLoadingMoreReviews(true);
    setReviewLoadFailed(false);
    try {
      const nextPage = await getReviewBatch(id, reviewPage.nextCursor);
      setReviewPage((current) => {
        if (!current || current.movieId !== id) return current;
        const knownIds = new Set(current.items.map((review) => review.ratingId));
        return {
          movieId: id,
          items: [
            ...current.items,
            ...nextPage.items.filter((review) => !knownIds.has(review.ratingId)),
          ],
          nextCursor: nextPage.nextCursor,
          hasNext: nextPage.hasNext,
        };
      });
      setReviewVisibility({ movieId: id, count: nextVisibleCount });
    } catch {
      setReviewLoadFailed(true);
    } finally {
      setLoadingMoreReviews(false);
    }
  }

  async function handleOttClick(ottId: number, watchUrl: string | null) {
    setClicking(true);
    setClickFailed(false);
    setPopupBlocked(false);

    const opened = watchUrl ? window.open('', '_blank') : null;
    if (opened) opened.opener = null;

    try {
      const record = await recordOttClick(id, ottId);
      if (watchUrl) {
        if (opened) opened.location.replace(watchUrl);
        else setPopupBlocked(true);
        setClicking(false);
        return;
      }
      void navigate(path.watchConfirm(record.watchRecordId));
    } catch {
      opened?.close();
      setClickFailed(true);
      setClicking(false);
    }
  }

  if (failure) {
    return (
      <div className={styles.page} data-poster="none" ref={pageRef}>
        <span className={styles.backdrop} aria-hidden="true" />
        <div className={styles.top}>
          <IconButton
            label="뒤로 가기"
            icon={<ChevronLeftIcon />}
            size={BACK_BUTTON_SIZE}
            variant="translucent"
            onClick={back}
          />
        </div>
        {failure === 'notFound' ? (
          <StatusView
            titleAs="h1"
            title="영화를 찾을 수 없어요"
            description="주소가 잘못되었거나 서비스에서 내려간 영화예요"
          >
            <Button variant="brand" onClick={() => void navigate(path.film())}>
              필름으로
            </Button>
          </StatusView>
        ) : (
          <StatusView
            titleAs="h1"
            title="영화 정보를 불러오지 못했어요"
            description="잠시 문제가 생겼어요"
          >
            <Button variant="brand" onClick={handleRetry}>
              다시 시도
            </Button>
          </StatusView>
        )}
      </div>
    );
  }

  if (!movie) return null;

  const meta = movieMetaLine(movie);
  const people = [
    ...movie.directors.map((person) => ({ ...person, creditKey: `director:${person.personId}` })),
    ...movie.cast.map((person) => ({ ...person, creditKey: `cast:${person.personId}` })),
  ];
  const backdropStyle = movie.posterUrl
    ? ({ '--detail-poster': `url("${movie.posterUrl}")` } as CSSProperties)
    : undefined;

  return (
    <div
      className={styles.page}
      data-poster={movie.posterUrl ? 'image' : 'none'}
      style={backdropStyle}
      ref={pageRef}
    >
      <span className={styles.backdrop} aria-hidden="true" />
      <span className={styles.scrim} aria-hidden="true" />

      <div className={styles.top}>
        <IconButton
          label="뒤로 가기"
          icon={<ChevronLeftIcon />}
          size={BACK_BUTTON_SIZE}
          variant="translucent"
          onClick={back}
        />
      </div>

      <div className={styles.stage} aria-hidden="true" />

      <div className={styles.sheet}>
        <header className={styles.head}>
          <h1 className={styles.title}>{movie.title}</h1>
          {meta ? <p className={styles.meta}>{meta}</p> : null}
          <p className={styles.average}>
            <RatingStars score={movie.averageRating} size={15} gap={3} />
            <span className={styles.averageValue}>{movie.averageRating.toFixed(1)}</span>
            <span className={styles.averageCount}>사용자 평점 · {movie.ratingCount}명</span>
          </p>
          {movie.recommendation?.reason.summary ? (
            <p className={styles.reason}>{movie.recommendation.reason.summary}</p>
          ) : null}
        </header>

        <div className={styles.action}>
          {movie.myRating ? (
            <div className={styles.myRating}>
              <div className={styles.reviewHead}>
                <span className={styles.reviewUser}>내 평가</span>
                <span className={styles.reviewScore}>★ {movie.myRating.score.toFixed(1)}</span>
              </div>
              {movie.myRating.comment ? (
                <p className={styles.reviewBody}>{movie.myRating.comment}</p>
              ) : null}
            </div>
          ) : null}
          <Button variant="brand" onClick={() => void navigate(path.movieRating(id))}>
            {movie.myRating ? '내 별점 수정하기' : '내 별점 남기기'}
          </Button>
        </div>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>볼 수 있는 곳</h2>
          {movie.ottProviders.length > 0 ? (
            <>
              <ul className={styles.ottList}>
                {movie.ottProviders.map((ott) => (
                  <li key={ott.ottId}>
                    <button
                      type="button"
                      className={styles.ottButton}
                      disabled={clicking}
                      aria-label={`${ott.name}에서 보기`}
                      data-mine={ott.subscribed ? 'true' : null}
                      onClick={() => void handleOttClick(ott.ottId, ott.watchUrl)}
                    >
                      <OttBadge
                        code={ott.code}
                        name={ott.name}
                        size={40}
                        radius={10}
                        labelled={false}
                      />
                    </button>
                  </li>
                ))}
              </ul>
              <a
                className={styles.ottAttribution}
                href="https://www.justwatch.com/kr"
                target="_blank"
                rel="noreferrer"
              >
                시청 정보 제공: JustWatch
              </a>
            </>
          ) : (
            <p className={styles.emptyNote}>현재 확인된 OTT 제공처가 없어요</p>
          )}
          {clickFailed ? (
            <p className={styles.ottError} role="alert">
              기록을 남기지 못했어요. 잠시 후 다시 눌러 주세요
            </p>
          ) : popupBlocked ? (
            <p className={styles.ottError} role="alert">
              새 탭이 열리지 않았어요. 브라우저의 팝업 차단을 풀고 다시 눌러 주세요
            </p>
          ) : null}
        </section>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>줄거리</h2>
          {movie.synopsis ? (
            <p className={styles.synopsis}>{movie.synopsis}</p>
          ) : (
            <p className={styles.emptyNote}>줄거리가 아직 등록되지 않았어요</p>
          )}
        </section>

        {people.length > 0 ? (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>출연 · 제작</h2>
            <ul
              className={styles.peopleList}
              aria-label="출연·제작 목록"
              onPointerDown={handlePeoplePointerDown}
              onPointerMove={handlePeoplePointerMove}
              onPointerUp={finishPeopleDrag}
              onPointerCancel={finishPeopleDrag}
            >
              {people.map((person) => (
                <li key={person.creditKey} className={styles.personItem}>
                  {person.profileUrl ? (
                    <img
                      className={styles.personPhoto}
                      src={person.profileUrl}
                      alt=""
                      loading="lazy"
                    />
                  ) : (
                    <span className={styles.personPhoto} aria-hidden="true" />
                  )}
                  <span className={styles.personName}>{person.name}</span>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>이런 영화도 좋아할 거예요</h2>
          {similar.length === 0 ? (
            <p className={styles.emptyNote}>비슷한 영화를 아직 찾지 못했어요</p>
          ) : (
            <ul className={styles.similarList}>
              {similar.map((item) => (
                <li key={item.movieId}>
                  <button
                    type="button"
                    className={styles.similarButton}
                    onClick={() => void navigate(path.movieDetail(item.movieId))}
                  >
                    <Poster
                      width={SIMILAR_POSTER_WIDTH}
                      height={SIMILAR_POSTER_HEIGHT}
                      radius={10}
                      src={item.posterUrl ?? undefined}
                      title={item.title}
                      seed={item.movieId}
                    />
                    <span className={styles.similarTitle}>{item.title}</span>
                    <span className={styles.similarReason}>
                      유사도 {Math.round(item.similarity * 100)}%
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>한줄평</h2>
          {reviewPage && reviews.length === 0 ? (
            <p className={styles.emptyNote}>아직 공개된 한줄평이 없어요</p>
          ) : (
            <ul className={styles.reviewList}>
              {reviews.map((review) => {
                const hidden = isReviewHidden(review);

                return (
                  <li key={review.ratingId} className={styles.reviewItem}>
                    <div className={styles.reviewHead}>
                      <span className={styles.reviewUser}>{review.user.nickname}</span>
                      <span className={styles.reviewScore}>★ {review.score.toFixed(1)}</span>
                    </div>
                    {hidden ? (
                      <div className={styles.reviewSpoiler}>
                        <span className={styles.reviewSpoilerPlaceholder} aria-hidden="true">
                          {SPOILER_PLACEHOLDER}
                        </span>
                        <button
                          type="button"
                          className={styles.reviewReveal}
                          onClick={() =>
                            setRevealedReviews((ids) =>
                              new Set(ids).add(reviewKey(review.ratingId)),
                            )
                          }
                        >
                          스포일러 포함 · 평가 보기
                        </button>
                      </div>
                    ) : (
                      <p
                        className={`${styles.reviewBody} ${
                          review.spoiler ? styles.reviewBodyRevealed : ''
                        }`}
                      >
                        {review.comment}
                      </p>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          {hasMoreReviews ? (
            <Button
              variant="secondary"
              disabled={loadingMoreReviews}
              onClick={() => void handleLoadMoreReviews()}
            >
              {loadingMoreReviews ? '불러오는 중…' : '한줄평 더 보기'}
            </Button>
          ) : null}
          {reviewLoadFailed ? (
            <p className={styles.reviewError} role="alert">
              한줄평을 더 불러오지 못했어요. 다시 눌러 주세요
            </p>
          ) : null}
          {reviewLoadFailed && !reviewPage ? (
            <Button variant="secondary" onClick={handleRetry}>
              한줄평 다시 불러오기
            </Button>
          ) : null}
        </section>

        <div className={styles.backToTop}>
          <Button variant="text" onClick={handleBackToTop}>
            ↑ 맨 위로
          </Button>
        </div>
      </div>
    </div>
  );
}
