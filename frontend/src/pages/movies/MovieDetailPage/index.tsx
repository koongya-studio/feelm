/*
 * 2.4 영화 상세
 *
 * 구성은 확정 시안을 따른다(`docs/screens/design-concept.md`). 시안은 이 화면이
 * 한 가지만 말하게 한다. **이 영화로 지금 무엇을 할 것인가.**
 *
 * 포스터 · 제목·메타 · 검정 알약 하나 · 추천 이유 · OTT · 시놉시스 · 유사한 영화 ·
 * 출연·제작 · 다른 사용자 한줄평
 *
 * 연동 API는 API-030(상세) · API-031(유사) · API-032(공개 한줄평) · API-033(링크 클릭
 * 기록) · API-034(감상 기록)이다.
 *
 * **API-030만 실제 API다**(업무 -475). 나머지 넷은 백엔드가 아직 없어 목업이며, 그래서
 * 이 화면은 실제 영화 위에 목업 유사 영화 · 한줄평 · 감상 상태가 얹힌 상태로 보인다.
 * 목업 화면(2.2 · 7.5 · 4.x)에서 넘어온 `movieId`는 실제 DB에 없어 `404`가 되고, 화면은
 * `영화를 찾을 수 없어요`를 보여준다. 한계는 `verification/S15P21E106-475.md`에 적었다.
 *
 * 팀 결정 2026-09-10 (업무 -99)
 * - **전면 배경 포스터를 걷어냈다.** 포스터를 화면 폭으로 깔고 흰색으로 페이드하던
 *   구성인데, 시안 3장이 "포스터 색을 배경으로 번지게 하지 않는다"로 금지한다.
 *   포스터는 프레임 안에 머물고 벽에 걸린 것처럼 그림자만 드리운다
 * - **와이어프레임의 번호 카드 ①~⑥을 걷어냈다.** 번호는 와이어프레임 표기였을 뿐이고,
 *   테두리 카드 여덟 개가 쌓이면 화면이 목록이 된다. 홈·취향 필름과 같이 카드 없는
 *   섹션으로 두고 소제목만 남긴다
 * - **주 동작을 하나로 합쳤다.** `내 별점 남기기`·OTT 링크·감상평이 각자 어두운 버튼을
 *   갖고 있어 무게가 셋으로 나뉘었다. 시안 2.4장은 검정 알약을 한 화면에 하나만
 *   허용한다. 그 하나를 **감상 상태(`myWatchStatus`)에 따라 바뀌는 알약**으로 두었다.
 *   아직 안 봤으면 보러 가는 것이, 봤으면 평가하는 것이 다음 행동이다
 * - 3번 `OTT 링크` 영역이 이 알약에 흡수됐다. 안내 문구는 알약 아래 힌트로 남는다
 * - 5번 `감상평`은 약한 버튼으로 내렸다. 한줄평은 평가에 종속되므로(API-036) 이
 *   화면에서 단독 저장할 수 없고, 실제로 하는 일은 평가 화면으로 이동하는 것이다
 * - 와이어프레임의 우측 상단 `더 보기`를 뺐다. 동작이 계약에도 명세에도 없어 이전에는
 *   비활성으로 두었는데, 누를 수 없는 것을 화면에 남길 이유가 없다
 *
 * 계약 기준으로 확정한 두 가지를 그대로 유지한다(screen-spec.md 8.1).
 * - 4번 영역 제목은 `시놉시스`만 쓴다. AI 영화 요약은 범위 밖이다(FR-34, API-030)
 * - 유사 영화는 탭 없이 단일 목록이다. 각 항목의 근거는 API-031 `reason.summary`다
 *
 * OTT 목록의 구독 우선 정렬은 API 계층이 이미 해 준다(FR-16, `api/movies.ts`).
 *
 * `[?]` `내 별점 남기기`가 이미 평가한 영화에서 어떻게 바뀌는지는 미확정이다.
 * 문구만 `별점 다시 남기기`로 바꾸고 같은 화면으로 보낸다. API-037(평가 수정)은
 * 2.5가 아직 구분하지 않는다.
 * `[?]` 출연·제작 노출 인원 수는 API-030 미정 사항이라 받은 만큼 그린다.
 * `[?]` 한줄평 목록의 개수와 더 보기 방식은 미확정이다. 받은 만큼 그린다.
 */
import { useEffect, useState } from 'react';
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
import { getWatchRecords, recordOttClick } from '../../../api/watch';
import { path } from '../../../routes/paths';
import { movieMetaLine } from '../../../utils/movie';
import styles from './MovieDetailPage.module.css';
import type { MovieDetail, MovieOttProvider, SimilarMovie } from '../../../types/movie';
import type { PublicReview } from '../../../types/watch';

/** 시안의 뒤로 가기 원 지름(px). 3.2와 같은 값이다 */
const BACK_BUTTON_SIZE = 39;

/** 벽에 걸리는 포스터. 2:3 비율이고 본문 폭(372px)의 절반 조금 넘게 둔다 */
const POSTER_WIDTH = 176;
const POSTER_HEIGHT = 264;

/** 유사 영화 목록의 포스터 */
const SIMILAR_POSTER_WIDTH = 84;
const SIMILAR_POSTER_HEIGHT = 126;

/**
 * 상세를 못 받은 이유. 없는 영화는 다시 눌러도 같은 답이라 재시도를 주지 않는다.
 *
 * 목업 화면에서 들어오면 실제로 자주 만나는 상태다. 2.2 오늘의 추천 · 7.5 추천 기록 ·
 * 4.x 검색은 아직 목업이라 그 카드의 `movieId`가 실제 DB에 없다(업무 -475).
 */
type Failure = 'notFound' | 'error';

type PrimaryAction = {
  label: string;
  /** 알약 아래 한 줄 안내. 없으면 그리지 않는다 */
  hint?: string;
  run: () => void;
};

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
  /*
   * `LINK_CLICKED` 상태에서 2.4-A로 가려면 감상 기록 id가 필요한데 API-030에는 없다.
   * API-034로 이 영화의 최근 기록을 찾는다. 실패하면 알약이 OTT 링크로 되돌아간다.
   */
  const [watchRecordId, setWatchRecordId] = useState<number | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [reloadCount, setReloadCount] = useState(0);

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

    getWatchRecords()
      .then((list) => {
        if (active) {
          const record = list.items.find((item) => item.movie.movieId === id) ?? null;
          setWatchRecordId(record?.watchRecordId ?? null);
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [id, recommendationItemId, reloadCount]);

  function handleRetry() {
    setFailure(null);
    setReloadCount((count) => count + 1);
  }

  async function handleOttClick(ottId: number) {
    const record = await recordOttClick(id, ottId);

    // 목업에는 시청 페이지 주소가 없다. 만들어진 감상 기록으로 2.4-A를 확인한다
    void navigate(path.watchConfirm(record.watchRecordId));
  }

  if (failure) {
    /*
     * 이 화면은 상단 제목 없이 떠 있는 뒤로 가기 버튼만 있다. 오류 상태에서도 같은
     * 버튼을 남겨 돌아갈 길을 만든다.
     */
    return (
      <div className={styles.page}>
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
            <Button onClick={() => void navigate(path.home())}>홈으로</Button>
          </StatusView>
        ) : (
          <StatusView
            titleAs="h1"
            title="영화 정보를 불러오지 못했어요"
            description="잠시 문제가 생겼어요"
          >
            <Button onClick={handleRetry}>다시 시도</Button>
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
  const primaryOtt: MovieOttProvider | null = movie.ottProviders[0] ?? null;
  const people = [...movie.directors, ...movie.cast];
  const action = resolvePrimaryAction();

  /*
   * 화면의 검정 알약 하나가 무엇을 할지 감상 상태로 정한다
   * (`docs/screens/design-concept.md` 2.4, WatchStatus는 screen-spec.md 6장 2.4).
   *
   * 아직 안 봤으면 보러 가는 것이, 봤으면 평가하는 것이 다음 행동이다. OTT가 하나도
   * 없으면 보러 갈 데가 없으므로 평가로 보낸다.
   */
  function resolvePrimaryAction(): PrimaryAction {
    const toRating = () => void navigate(path.movieRating(id));

    const ottAction = primaryOtt
      ? {
          label: `${primaryOtt.name}에서 바로 보기`,
          hint: '이동 시 링크 클릭함으로 기록돼요',
          run: () => void handleOttClick(primaryOtt.ottId),
        }
      : { label: '내 별점 남기기', run: toRating };

    switch (movie?.myWatchStatus) {
      case 'RATED':
        return { label: '별점 다시 남기기', run: toRating };
      case 'WATCHED_UNRATED':
        return { label: '내 별점 남기기', run: toRating };
      case 'LINK_CLICKED':
        // 봤는지 아직 모르는 상태다. 2.4-A가 그걸 묻는 화면이다
        return watchRecordId === null
          ? ottAction
          : {
              label: '감상 확인하기',
              hint: '이동한 지 시간이 지났다면 여기서 확인해요',
              run: () => void navigate(path.watchConfirm(watchRecordId)),
            };
      default:
        // 없음 · EXPIRED — 둘 다 아직 보지 않은 것으로 다룬다
        return ottAction;
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.top}>
        <IconButton
          label="뒤로 가기"
          icon={<ChevronLeftIcon />}
          size={BACK_BUTTON_SIZE}
          variant="translucent"
          onClick={() => void navigate(-1)}
        />
      </div>

      {/* 포스터는 벽에 걸린다. 색은 이 프레임 안에 머문다 (시안 3장) */}
      <div className={styles.hero}>
        <Poster
          width={POSTER_WIDTH}
          height={POSTER_HEIGHT}
          src={movie.posterUrl ?? undefined}
          title={movie.title}
          seed={movie.movieId}
        />
      </div>

      <header className={styles.head}>
        <h1 className={styles.title}>{movie.title}</h1>
        {meta ? <p className={styles.meta}>{meta}</p> : null}

        <p className={styles.average}>
          <RatingStars score={movie.averageRating} size={15} gap={3} />
          <span className={styles.averageValue}>{movie.averageRating.toFixed(1)}</span>
          <span className={styles.averageCount}>{movie.ratingCount}명</span>
        </p>
      </header>

      {/*
       * 이 화면의 주 동작. 검정 알약은 한 화면에 하나뿐이고 그 하나가 여기다
       * (`docs/screens/design-concept.md` 2.4).
       */}
      <div className={styles.action}>
        <Button onClick={action.run}>{action.label}</Button>
        {action.hint ? <p className={styles.actionHint}>{action.hint}</p> : null}
      </div>

      <div className={styles.content}>
        {/*
         * 추천 이유 — 추천 경로로 진입했을 때만 있다. 저장된 이유 스냅샷에 문구가 없으면
         * 빈 줄만 남으므로 `summary`가 있을 때만 그린다(API-030).
         */}
        {movie.recommendation?.reason.summary ? (
          <section className={styles.section}>
            <p className={styles.eyebrow}>추천 이유</p>
            <p className={styles.reason}>{movie.recommendation.reason.summary}</p>
          </section>
        ) : null}

        {/* OTT — 구독 중인 것이 앞에 온다 (FR-16, API 계층이 정렬) */}
        {movie.ottProviders.length > 0 ? (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>볼 수 있는 곳</h2>
            <ul className={styles.ottList}>
              {movie.ottProviders.map((ott) => (
                <li key={ott.ottId} className={styles.ottItem}>
                  <OttBadge code={ott.code} name={ott.name} size={26} radius={7} labelled={false} />
                  <span className={styles.ottName}>{ott.name}</span>
                  {ott.subscribed ? <span className={styles.ottMine}>구독 중</span> : null}
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/* 시놉시스 — AI 영화 요약은 제외 (FR-34) */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>시놉시스</h2>
          {movie.synopsis ? (
            <p className={styles.synopsis}>{movie.synopsis}</p>
          ) : (
            <p className={styles.emptyNote}>줄거리가 아직 등록되지 않았어요</p>
          )}
        </section>

        {/* 감상평 — 입력이 아니라 평가 화면으로 이동한다 (API-036) */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>내 감상평</h2>
          {movie.myRating?.comment ? (
            <p className={styles.myComment}>{movie.myRating.comment}</p>
          ) : null}
          <Button variant="secondary" onClick={() => void navigate(path.movieRating(id))}>
            {movie.myRating?.comment ? '한 줄 감상 고치기' : '한 줄 감상 남기기 (선택)'}
          </Button>
        </section>

        {/* 유사한 영화 추천 — 탭 없이 단일 목록 (API-031) */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>유사한 영화</h2>

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
