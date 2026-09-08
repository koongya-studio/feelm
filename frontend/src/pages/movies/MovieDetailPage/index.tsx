/*
 * 2.4 영화 상세
 *
 * 요소와 순서는 와이어프레임 2.4 그대로다(screen-spec.md 6장 2.4).
 * 배경 포스터 · 제목·메타·별점 버튼 · 번호 영역 6개 · 출연·제작 · 다른 사용자 한줄평
 *
 * 계약 기준으로 확정한 두 가지를 적용한다(screen-spec.md 8.1).
 * - 4번 영역 제목은 `시놉시스`만 쓴다. AI 영화 요약은 범위 밖이다(FR-34, API-030)
 * - 유사 영화는 탭 없이 단일 목록이다. 각 항목의 근거는 API-031 `reason.summary`다
 *
 * 5번 감상평은 입력란이 아니라 평가 화면으로 가는 버튼이다. 한줄평은 평가에 종속되므로
 * (API-036) 이 화면에서 단독으로 저장할 수 없다(팀 결정 2026-09-07).
 *
 * 3번 OTT 링크는 목업에 `watchUrl`이 없어 외부로 이동하지 않는다. 누르면 API-033으로
 * 링크 클릭만 기록하고, 만들어진 감상 기록으로 2.4-A를 확인할 수 있게 이동한다.
 * 실제 연동 시에는 기록과 함께 외부 시청 페이지를 연다.
 *
 * `[?]` 우측 상단 `더 보기` 버튼은 와이어프레임에 있으나 동작이 계약에도 Screen Spec에도
 * 없어 비활성으로 둔다.
 * `[?]` `내 별점 남기기`가 이미 평가한 영화에서 어떻게 바뀌는지는 미확정이다. 평가가
 * 있으면 그 점수를 함께 보여주고 문구는 그대로 둔다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import IconButton from '../../../components/IconButton';
import OttBadge from '../../../components/OttBadge';
import Poster from '../../../components/Poster';
import RatingStars from '../../../components/RatingStars';
import Button from '../../../components/Button';
import StatusView from '../../../components/StatusView';
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  ExternalLinkIcon,
  MoreVerticalIcon,
} from '../../../components/Icon';
import { getMovieDetail, getMovieReviews, getSimilarMovies } from '../../../api/movies';
import { recordOttClick } from '../../../api/watch';
import { path } from '../../../routes/paths';
import { ottBadgeLetter } from '../../../utils/ott';
import styles from './MovieDetailPage.module.css';
import type { MovieDetail, SimilarMovie } from '../../../types/movie';
import type { PublicReview } from '../../../types/watch';

/** 와이어프레임의 번호 영역. 4번 제목은 계약에 맞춰 `시놉시스`만 쓴다 */
const SECTION_TITLES = ['추천 이유', 'OTT', 'OTT 링크', '시놉시스', '감상평', '유사한 영화 추천'];

export default function MovieDetailPage() {
  const { movieId } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const id = Number(movieId);
  const recommendationItemId = Number(searchParams.get('recommendationItemId')) || undefined;

  const [movie, setMovie] = useState<MovieDetail | null>(null);
  const [similar, setSimilar] = useState<SimilarMovie[]>([]);
  const [reviews, setReviews] = useState<PublicReview[]>([]);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMovieDetail(id, recommendationItemId)
      .then((detail) => {
        if (active) {
          setMovie(detail);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
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

  function handleRetry() {
    setFailed(false);
    setReloadCount((count) => count + 1);
  }

  async function handleOttClick(ottId: number) {
    const record = await recordOttClick(id, ottId);

    // 목업에는 시청 페이지 주소가 없다. 만들어진 감상 기록으로 2.4-A를 확인한다
    void navigate(path.watchConfirm(record.watchRecordId));
  }

  if (failed) {
    /*
     * 이 화면은 상단 제목 없이 떠 있는 뒤로 가기 버튼만 있다. 오류 상태에서도 같은
     * 버튼을 남겨 돌아갈 길을 만든다.
     */
    return (
      <div className={styles.page}>
        <div className={styles.floating}>
          <IconButton
            label="뒤로 가기"
            icon={<ChevronLeftIcon />}
            size={34}
            onClick={() => void navigate(-1)}
          />
        </div>

        <StatusView
          title="영화 정보를 불러오지 못했어요"
          description="주소가 잘못되었거나 잠시 문제가 생겼어요"
        >
          <Button onClick={handleRetry}>다시 시도</Button>
        </StatusView>
      </div>
    );
  }

  if (!movie) {
    return null;
  }

  const releaseYear = movie.releaseDate.slice(0, 4);
  const meta = [
    releaseYear,
    ...movie.genres.map((genre) => genre.name),
    `${movie.runtimeMinutes}분`,
    `평균 ★ ${movie.averageRating.toFixed(1)}`,
  ].join(' · ');
  const primaryOtt = movie.ottProviders[0] ?? null;
  const people = [...movie.directors, ...movie.cast];

  return (
    <div className={styles.page}>
      <div className={styles.floating}>
        <IconButton
          label="뒤로 가기"
          icon={<ChevronLeftIcon />}
          size={34}
          onClick={() => void navigate(-1)}
        />
        {/* 동작이 정의되지 않아 비활성이다 */}
        <IconButton label="더 보기" icon={<MoreVerticalIcon />} size={34} disabled />
      </div>

      <div className={styles.scroll}>
        <div className={styles.backdrop}>
          <Poster width={412} height={340} radius={0} src={movie.posterUrl ?? undefined} title="" />
          <span className={styles.backdropFade} aria-hidden="true" />
        </div>

        <div className={styles.body}>
          <div className={styles.titleRow}>
            <h1 className={styles.title}>{movie.title}</h1>

            <button
              type="button"
              className={styles.ratingAction}
              onClick={() => void navigate(path.movieRating(movie.movieId))}
            >
              <RatingStars score={movie.myRating?.score ?? 0} size={17} gap={3} />
              <span className={styles.ratingActionLabel}>내 별점 남기기</span>
            </button>
          </div>

          <p className={styles.meta}>{meta}</p>

          {/* 1 추천 이유 — 추천 경로로 진입했을 때만 있다 */}
          {movie.recommendation ? (
            <section className={styles.card}>
              <h2 className={styles.cardTitle}>
                <span className={styles.cardNumber} aria-hidden="true">
                  1
                </span>
                {SECTION_TITLES[0]}
              </h2>
              <p className={styles.cardBody}>{movie.recommendation.reason.summary}</p>
            </section>
          ) : null}

          {/* 2 OTT */}
          <section className={styles.card}>
            <h2 className={styles.cardTitle}>
              <span className={styles.cardNumber} aria-hidden="true">
                2
              </span>
              {SECTION_TITLES[1]}
            </h2>
            <ul className={styles.ottList}>
              {movie.ottProviders.map((ott) => (
                <li key={ott.ottId} className={styles.ottItem}>
                  <OttBadge
                    letter={ottBadgeLetter(ott.code)}
                    name={ott.name}
                    size={26}
                    radius={7}
                    labelled={false}
                  />
                  <span className={styles.ottName}>{ott.name}</span>
                </li>
              ))}
            </ul>
          </section>

          {/* 3 OTT 링크 */}
          {primaryOtt ? (
            <section className={styles.card}>
              <h2 className={styles.cardTitle}>
                <span className={styles.cardNumber} aria-hidden="true">
                  3
                </span>
                {SECTION_TITLES[2]}
              </h2>
              <p className={styles.cardBody}>
                <button
                  type="button"
                  className={styles.ottLink}
                  onClick={() => void handleOttClick(primaryOtt.ottId)}
                >
                  {primaryOtt.name}에서 바로 보기
                </button>
                <span className={styles.ottLinkNote}>· 이동 시 링크 클릭함으로 기록돼요</span>
                <span className={styles.ottLinkIcon}>
                  <ExternalLinkIcon />
                </span>
              </p>
            </section>
          ) : null}

          {/* 4 시놉시스 — AI 영화 요약은 제외 (FR-34) */}
          <section className={styles.card}>
            <h2 className={styles.cardTitle}>
              <span className={styles.cardNumber} aria-hidden="true">
                4
              </span>
              {SECTION_TITLES[3]}
            </h2>
            <p className={styles.cardBody}>{movie.synopsis}</p>
          </section>

          {/* 5 감상평 — 입력이 아니라 평가 화면으로 이동한다 */}
          <section className={styles.card}>
            <h2 className={styles.cardTitle}>
              <span className={styles.cardNumber} aria-hidden="true">
                5
              </span>
              {SECTION_TITLES[4]}
            </h2>
            <button
              type="button"
              className={styles.commentPlaceholder}
              onClick={() => void navigate(path.movieRating(movie.movieId))}
            >
              {movie.myRating?.comment ?? '한 줄 감상 남기기 (선택)'}
            </button>
          </section>

          {/* 6 유사한 영화 추천 — 탭 없이 단일 목록 (API-031) */}
          <section className={styles.card}>
            <h2 className={styles.cardTitle}>
              <span className={styles.cardNumber} aria-hidden="true">
                6
              </span>
              {SECTION_TITLES[5]}
              <span className={styles.cardChevron} aria-hidden="true">
                <ChevronRightIcon />
              </span>
            </h2>

            {similar.length === 0 ? (
              <p className={styles.emptyNote}>비슷한 영화를 아직 찾지 못했어요</p>
            ) : null}

            <ul className={styles.similarList}>
              {similar.map((item) => (
                <li key={item.movieId} className={styles.similarItem}>
                  <button
                    type="button"
                    className={styles.similarButton}
                    onClick={() => void navigate(path.movieDetail(item.movieId))}
                  >
                    <Poster
                      width={64}
                      height={90}
                      radius={6}
                      src={item.posterUrl ?? undefined}
                      title=""
                    />
                    <span className={styles.similarReason}>{item.reason.summary}</span>
                    <span className={styles.similarTitle}>{item.title}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>

          {/* 출연 · 제작 */}
          <section className={styles.card}>
            <h2 className={styles.cardTitle}>출연 · 제작</h2>
            <ul className={styles.peopleList}>
              {people.map((person) => (
                <li key={person.personId} className={styles.personItem}>
                  <span className={styles.personPhoto} aria-hidden="true" />
                  <span className={styles.personName}>{person.name}</span>
                </li>
              ))}
            </ul>
          </section>

          {/* 다른 사용자 한줄평 — 차단 관계는 서버가 걸러 준다 (API-032) */}
          <section className={styles.card}>
            <h2 className={styles.cardTitle}>다른 사용자 한줄평</h2>

            {reviews.length === 0 ? (
              <p className={styles.emptyNote}>아직 공개된 한줄평이 없어요</p>
            ) : null}

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
          </section>
        </div>
      </div>
    </div>
  );
}
