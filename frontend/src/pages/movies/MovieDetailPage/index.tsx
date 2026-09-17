/*
 * 2.4 영화 상세
 *
 * 구성은 사용자 시안 2026-09-17(업무 -458)을 따른다. 시안이 이 화면에 요구하는 것은
 * **몰입**이다. 그 영화의 포스터가 화면 전체 배경이고, 글자는 그 위에 얹힌다.
 *
 * 제목 · 개봉연도/장르/러닝타임 · 평균 평점 · OTT · 줄거리 · 내 감상평 · 출연/제작 ·
 * 관련 영화 · 다른 사용자 한줄평
 *
 * **이 화면은 앱에서 유일하게 어둡다.** `design-concept.md` 2.1의 밝은 단일 배경과
 * 2.2의 "포스터 색을 배경으로 번지게 하지 않는다"를 따르지 않는 유일한 화면이며,
 * 그 예외는 같은 문서 2.9에 적었다. 다른 화면에 이 방식을 옮기지 않는다.
 *
 * 이전 구현에서 바뀐 것
 * - 벽에 걸린 176px 포스터를 걷고 **포스터를 전면 배경으로** 깔았다. 상단은 선명하고,
 *   글자가 시작되는 지점부터 어두운 반투명이 덮는다
 * - **스크롤에 따라 배경이 더 흐려지고 어두워진다.** 진행도를 CSS 변수 하나로 내려
 *   블러와 밝기를 같이 움직인다. 글자가 길어질수록 뒤가 물러나야 읽힌다
 * - **감상 상태 알약을 없앴다.** `myWatchStatus`로 `바로 보기`·`별점 다시 남기기`를
 *   바꿔 달던 것인데, 초기 취향 설정에서 좋아한다고 고른 영화도 이 화면에 들어오므로
 *   **평가·시청 완료 상태를 화면이 단정하면 안 된다**(사용자 지시 2026-09-17)
 * - **OTT는 로고 아이콘만 둔다.** 큰 `바로 보기` 알약과 `볼 수 있는 곳` 목록이 같은
 *   것을 두 번 말하고 있었다. 아이콘을 누르면 그 영화의 OTT로 바로 간다
 * - 관련 영화가 출연·제작 뒤로 갔다. 시안이 정한 순서다
 *
 * 연동 API는 그대로다. API-030(상세) · API-031(유사) · API-032(공개 한줄평) ·
 * API-033(링크 클릭 기록)이며, 유사 영화와 공개 한줄평은 백엔드가 아직 없어 목업이다.
 * 목업 목록에서 넘어온 `movieId`는 실제 DB에 없어 `404`가 된다(업무 -475 · -476).
 *
 * **API-034(감상 기록 목록) 호출이 빠졌다.** 감상 상태 알약이 사라지면서 기록 번호를
 * 찾을 이유가 없어졌다. 2.4-A 감상 확인으로 가는 길은 OTT 링크가 없는 영화에서만
 * 남는다 — 링크가 있으면 OTT로 보내고, 없으면 예전처럼 감상 확인을 연다.
 *
 * `[?]` 출연·제작 노출 인원 수는 API-030 미정 사항이라 받은 만큼 그린다.
 * `[?]` 한줄평 목록의 개수와 더 보기 방식은 미확정이다. 받은 만큼 그린다.
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
import { path } from '../../../routes/paths';
import { movieMetaLine } from '../../../utils/movie';
import styles from './MovieDetailPage.module.css';
import type { CSSProperties } from 'react';
import type { MovieDetail, SimilarMovie } from '../../../types/movie';
import type { PublicReview } from '../../../types/watch';

/** 시안의 뒤로 가기 원 지름(px). 3.2와 같은 값이다 */
const BACK_BUTTON_SIZE = 39;

/** 유사 영화 목록의 포스터 */
const SIMILAR_POSTER_WIDTH = 96;
const SIMILAR_POSTER_HEIGHT = 144;

/**
 * 배경 전환이 끝나는 스크롤 거리(화면 높이 대비).
 *
 * 글자가 시작되는 지점까지 내려오면 배경은 완전히 물러나 있어야 한다. 그보다 길게
 * 잡으면 본문을 읽는 내내 뒤가 계속 움직여 산만하다.
 */
const BACKDROP_FADE_RATIO = 0.55;

/**
 * 상세를 못 받은 이유. 없는 영화는 다시 눌러도 같은 답이라 재시도를 주지 않는다.
 *
 * 목업 목록에서 들어오면 실제로 자주 만나는 상태다. 2.2 오늘의 추천 · 7.5 추천 기록 ·
 * 4.1 급상승은 아직 목업이라 그 카드의 `movieId`가 실제 DB에 없다(업무 -475 · -476).
 */
type Failure = 'notFound' | 'error';

export default function MovieDetailPage() {
  const { movieId } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const id = Number(movieId);

  /*
   * 추천 경로로 들어왔는지는 Router state로 받는다(frontend-structure.md 6.2 —
   * `recommendationItemId`는 없어도 화면이 성립하는 부가 정보다). state가 없으면
   * 추천 이유 영역을 표시하지 않으며 계약도 같다(API-030, 6.3 대체 동작).
   * URL 질의는 이 화면을 URL만으로 확인할 때를 위한 대체 경로로 남겨 둔다.
   */
  const routeState = useLocation().state as { recommendationItemId?: number } | null;
  const queryItemId = Number(searchParams.get('recommendationItemId')) || undefined;
  const recommendationItemId = routeState?.recommendationItemId ?? queryItemId;

  const [movie, setMovie] = useState<MovieDetail | null>(null);
  const [similar, setSimilar] = useState<SimilarMovie[]>([]);
  const [reviews, setReviews] = useState<PublicReview[]>([]);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [reloadCount, setReloadCount] = useState(0);
  /** 링크 클릭을 기록하는 중. 같은 클릭이 두 번 나가지 않게 막는다 */
  const [clicking, setClicking] = useState(false);
  /** 클릭 기록(API-033)이 실패했다. OTT 줄 아래에 알린다 */
  const [clickFailed, setClickFailed] = useState(false);
  /** 브라우저가 새 탭을 막았다. 기록은 남았으므로 다른 문구로 알린다 */
  const [popupBlocked, setPopupBlocked] = useState(false);

  const pageRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
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

    // 유사 영화와 한줄평은 실패하면 그 섹션만 빈 안내로 남는다. 화면 전체를 덮지 않는다
    getSimilarMovies(id)
      .then((items) => {
        if (active) {
          setSimilar(items);
        }
      })
      .catch(() => undefined);

    getMovieReviews(id)
      .then((items) => {
        if (active) {
          setReviews(items);
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [id, recommendationItemId, reloadCount]);

  /*
   * 다른 영화로 넘어가면 맨 위로 되돌린다.
   *
   * **관련 영화는 이 화면 아래쪽에 있다.** 거기서 카드를 누르면 같은 라우트라 컴포넌트가
   * 다시 마운트되지 않고 스크롤도 그대로 남는다 — 새 영화가 배경이 이미 흐려지고 어두워진
   * 상태로 시작한다. 선명한 포스터로 여는 것이 이 화면의 전부인데 그것을 건너뛰게 된다
   * (MR !349 리뷰 지적, 실제로 `scrollY` 633 · 진행도 1.0으로 재현했다).
   *
   * `id`에만 매단다. 재시도(`reloadCount`)는 같은 영화를 다시 받는 것이라 읽던 자리를
   * 빼앗지 않는다. 뒤로 가기의 위치 복원은 아직 앱에 없다(업무 -520) — 생기면 그쪽과
   * 순서를 맞춰야 한다.
   */
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [id]);

  /*
   * 배경 전환. 진행도(0~1)를 CSS 변수로 내리고 블러와 밝기는 CSS가 계산한다.
   *
   * 상태로 두지 않는다 — 스크롤마다 리렌더가 나면 목록이 긴 화면에서 눈에 띄게 끊긴다.
   * 값은 `requestAnimationFrame` 한 프레임에 한 번만 쓴다.
   */
  useEffect(() => {
    const page = pageRef.current;

    if (!page) {
      return;
    }

    let frame = 0;

    const apply = () => {
      frame = 0;

      const end = window.innerHeight * BACKDROP_FADE_RATIO;
      const progress = end > 0 ? Math.min(1, window.scrollY / end) : 1;

      page.style.setProperty('--detail-scroll', progress.toFixed(3));
    };

    const onScroll = () => {
      if (frame === 0) {
        frame = window.requestAnimationFrame(apply);
      }
    };

    apply();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll, { passive: true });

    return () => {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);

      if (frame !== 0) {
        window.cancelAnimationFrame(frame);
      }
    };
  }, [movie]);

  function handleRetry() {
    setFailure(null);
    setReloadCount((count) => count + 1);
  }

  /*
   * OTT 아이콘. 기록을 남기고 그 영화의 시청 페이지로 보낸다(API-033).
   *
   * 계약은 "클라이언트는 응답과 무관하게 외부 시청 페이지를 연다"고 적었지만, 기록에
   * 실패하면 열지 않고 알린다. 링크 클릭 기록이 감상 기록의 시작점이라(API-034)
   * 기록 없이 나가면 돌아왔을 때 이어지는 흐름이 없다.
   *
   * 새 탭을 여는 시점은 아래 주석을 보라. 기록을 기다린 뒤에 열면 팝업 차단에 막힌다
   * (MR !349 리뷰 지적).
   *
   * `watchUrl`은 값이 없으면 `null`이다(API-030). 그때는 예전처럼 2.4-A 감상 확인을
   * 연다 — 나갈 곳은 없어도 봤는지 묻는 흐름은 살아 있어야 한다.
   */
  async function handleOttClick(ottId: number, watchUrl: string | null) {
    setClicking(true);
    setClickFailed(false);
    setPopupBlocked(false);

    /*
     * **새 탭은 누른 그 자리에서 연다.** 기록을 기다린 뒤에 열면 사용자 제스처가 끊겨
     * 모바일 브라우저(특히 iOS Safari)의 팝업 차단에 막힌다. 빈 탭을 먼저 잡아 두고
     * 주소는 기록이 끝난 뒤에 넣는다.
     *
     * `noopener`를 창 기능으로 넘기지 않는다 — 그렇게 열면 `window.open`이 `null`을
     * 돌려줘 주소를 넣을 손잡이가 없어진다. 대신 받은 창의 `opener`를 직접 끊는다.
     */
    const opened = watchUrl ? window.open('', '_blank') : null;

    if (opened) {
      opened.opener = null;
    }

    try {
      const record = await recordOttClick(id, ottId);

      if (watchUrl) {
        // 차단됐으면 알린다. 기록은 남았으므로 실패와는 다른 이야기다
        if (opened) {
          opened.location.replace(watchUrl);
        } else {
          setPopupBlocked(true);
        }

        setClicking(false);

        return;
      }

      void navigate(path.watchConfirm(record.watchRecordId));
    } catch {
      // 기록이 실패했으면 열어 둔 빈 탭을 남기지 않는다
      opened?.close();
      setClickFailed(true);
      setClicking(false);
    }
  }

  if (failure) {
    /*
     * 이 화면은 상단 제목 없이 떠 있는 뒤로 가기 버튼만 있다. 오류 상태에서도 같은
     * 버튼을 남겨 돌아갈 길을 만든다. 배경으로 깔 포스터가 없으므로 어두운 면만 둔다.
     */
    return (
      <div className={styles.page} data-poster="none" ref={pageRef}>
        <span className={styles.backdrop} aria-hidden="true" />

        <div className={styles.top}>
          <IconButton
            label="뒤로 가기"
            icon={<ChevronLeftIcon />}
            size={BACK_BUTTON_SIZE}
            variant="translucent"
            onClick={() => void navigate(-1)}
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

  if (!movie) {
    return null;
  }

  // 개봉일 · 장르 · 상영 시간은 원천에 없으면 빠진다(API-030). 있는 값만 잇는다
  const meta = movieMetaLine(movie);
  const people = [...movie.directors, ...movie.cast];

  /*
   * 포스터가 없는 영화가 있다(API-030 `posterUrl: null`). 그때는 배경 이미지를 비우고
   * 어두운 면만 남긴다 — 자리표시자를 전면에 늘리면 화면 전체가 회색 덩어리가 된다.
   */
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
          onClick={() => void navigate(-1)}
        />
      </div>

      {/* 선명한 포스터가 보이는 구간. 글자를 올리지 않는다 */}
      <div className={styles.stage} aria-hidden="true" />

      <div className={styles.sheet}>
        <header className={styles.head}>
          <h1 className={styles.title}>{movie.title}</h1>
          {meta ? <p className={styles.meta}>{meta}</p> : null}

          {/* 평균 평점. 내 평가와 섞이지 않게 `사용자 평점`이라고 말한다 */}
          <p className={styles.average}>
            <RatingStars score={movie.averageRating} size={15} gap={3} />
            <span className={styles.averageValue}>{movie.averageRating.toFixed(1)}</span>
            <span className={styles.averageCount}>사용자 평점 · {movie.ratingCount}명</span>
          </p>

          {/*
           * 추천 이유 — 추천 경로로 진입했을 때만 있다. 저장된 이유 스냅샷에 문구가
           * 없으면 빈 줄만 남으므로 `summary`가 있을 때만 그린다(API-030).
           */}
          {movie.recommendation?.reason.summary ? (
            <p className={styles.reason}>{movie.recommendation.reason.summary}</p>
          ) : null}
        </header>

        {/*
         * OTT — 로고만 둔다. 구독 중인 것이 앞에 온다(FR-16, API 계층이 정렬).
         * 터치 영역은 로고보다 넉넉히 잡는다.
         */}
        {movie.ottProviders.length > 0 ? (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>볼 수 있는 곳</h2>

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
        ) : null}

        {/* 줄거리 — AI 영화 요약은 제외 (FR-34) */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>줄거리</h2>
          {movie.synopsis ? (
            <p className={styles.synopsis}>{movie.synopsis}</p>
          ) : (
            <p className={styles.emptyNote}>줄거리가 아직 등록되지 않았어요</p>
          )}
        </section>

        {/*
         * 내 감상평. **평균 평점과 같은 자리에 두지 않는다** — 둘은 다른 수다.
         *
         * 한줄평은 평가에 종속되므로(API-036) 이 화면에서 단독 저장할 수 없고, 실제로
         * 하는 일은 평가 화면으로 보내는 것이다. 쓰지 않아도 되는 선택 사항이다.
         */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>내 감상평</h2>

          {movie.myRating ? (
            <p className={styles.mine}>
              <RatingStars score={movie.myRating.score} size={14} gap={3} />
              <span className={styles.mineValue}>{movie.myRating.score.toFixed(1)}</span>
            </p>
          ) : null}

          {movie.myRating?.comment ? (
            <p className={styles.myComment}>{movie.myRating.comment}</p>
          ) : (
            <p className={styles.emptyNote}>이 영화를 어떻게 기억하나요? 선택 사항이에요</p>
          )}

          <Button variant="brand" onClick={() => void navigate(path.movieRating(id))}>
            {movie.myRating ? '내 평가 고치기' : '한 줄 감상 남기기'}
          </Button>
        </section>

        {/* 출연 · 제작 */}
        {people.length > 0 ? (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>출연 · 제작</h2>
            <ul className={styles.peopleList}>
              {people.map((person) => (
                <li key={person.personId} className={styles.personItem}>
                  <span className={styles.personPhoto} aria-hidden="true" />
                  <span className={styles.personName}>{person.name}</span>
                  {person.character ? (
                    <span className={styles.personRole}>{person.character}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/* 관련 영화 — 탭 없이 단일 목록이고 근거는 API-031 `reason.summary`다 */}
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
                    <span className={styles.similarReason}>{item.reason.summary}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        {/* 다른 사용자 한줄평 — 차단 관계는 서버가 걸러 준다 (API-032) */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>다른 사용자 한줄평</h2>

          {reviews.length === 0 ? (
            <p className={styles.emptyNote}>아직 공개된 한줄평이 없어요</p>
          ) : (
            <ul className={styles.reviewList}>
              {reviews.map((review) => (
                <li key={review.ratingId} className={styles.reviewItem}>
                  <div className={styles.reviewHead}>
                    <span className={styles.reviewUser}>{review.user.nickname}</span>
                    <span className={styles.reviewScore}>★ {review.score.toFixed(1)}</span>
                  </div>
                  <p className={styles.reviewBody}>{review.comment}</p>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
