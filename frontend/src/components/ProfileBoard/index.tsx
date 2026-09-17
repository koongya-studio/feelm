/*
 * 공개 프로필 화면. 7.2-A · 7.2-B가 같은 구성을 쓴다(업무 -605).
 *
 * **2.4 영화 상세와 같은 화면이다**(사용자 지시 2026-09-18 — "영화 상세페이지 화면처럼
 * 디자인해줘"). 어두운 바탕 · 위에 떠 있는 유리 버튼 · 무대 · 이어지는 본문 시트 · 얇은 선으로
 * 나눈 구역. 2.4가 한 영화 안에 들어가는 화면이듯 이 화면은 **한 사람 안에 들어가는 화면**이고,
 * 값과 층 구성은 `MovieDetailPage.module.css`를 따른다.
 *
 * 무대에는 포스터 대신 세로 프로필 사진이 선다. 사진은 **자리만 있다**(API-062에 `avatarUrl`이
 * 없다) — 2.4에서 포스터가 없는 영화가 어두운 단색으로 떨어지듯 여기도 무채색 자리표시자다.
 * 그래서 스크롤에 따라 흐려질 배경이 없고 2.4의 스크롤 연출은 두지 않는다.
 *
 * 본문은 2.4의 머리와 같다 — 제목(이름) · 메타(취향 키워드를 ` · `로 이은 한 줄) · 수치 한 줄
 * (프레임 · 팝콘 · 한줄평). 구역은 `취향 팝콘` · `취향 필름` · `공개 한줄평` 순이고, 필름은
 * 2.4의 `이런 영화도 좋아할 거예요`와 같은 가로 포스터 줄이다.
 *
 * 7.2-A만 필름을 눌러 프레임 상세(3.1-A)로 간다 — 그 화면은 내 감상 기록을 보여주는 곳이라
 * 다른 사람의 프레임으로는 들어갈 수 없다. `한줄평` 구역은 7.2-B만 둔다 — 본인 미리보기의
 * `reviews`는 항상 빈 배열이다(`types/unspecified.ts`).
 */
import { Link, useNavigate } from 'react-router';
import Avatar from '../Avatar';
import IconButton from '../IconButton';
import { ChevronLeftIcon } from '../Icon';
import PopcornBucket from '../PopcornBucket';
import { kernelCounts } from '../PopcornBucket/kernels';
import Poster from '../Poster';
import RatingStars from '../RatingStars';
import { path } from '../../routes/paths';
import styles from './ProfileBoard.module.css';
import type { ReactNode } from 'react';
import type { PublicProfileView } from '../../types/unspecified';

type Props = {
  profile: PublicProfileView;
  /** 이름 위에 작게 적는 한 줄. 7.2-A가 `미리보기 · 다른 사용자에게 이렇게 보여요`를 둔다 */
  eyebrow?: string;
  /** 위 오른쪽에 뜨는 버튼. 7.2-B의 ⋯(차단 · 신고)이다 */
  topAction?: ReactNode;
  /** 필름을 눌러 프레임 상세로 갈 수 있는지. 내 프로필(7.2-A)만 true다 */
  frameLinks?: boolean;
  /** `공개 한줄평` 구역을 두는지. 7.2-B만 true다 */
  reviews?: boolean;
  /** 본문 맨 아래에 붙는 것. 7.2-B의 안내문이다 */
  children?: ReactNode;
};

/** 2.4와 같은 값 */
const TOP_BUTTON_SIZE = 39;
const FILM_POSTER_WIDTH = 96;
const FILM_POSTER_HEIGHT = 144;

/** 무대의 프로필 사진 폭. 7.1(84)보다 크다 — 여기서는 포스터 자리다 */
const AVATAR_WIDTH = 132;

/** 통 폭. 3.2(232)와 7.1 바로가기(64) 사이 — 구역 하나를 채우되 화면을 다 먹지 않는다 */
const BUCKET_WIDTH = 148;

export default function ProfileBoard({
  profile,
  eyebrow,
  topAction,
  frameLinks = false,
  reviews = false,
  children,
}: Props) {
  const navigate = useNavigate();

  const meta = profile.keywords.map((keyword) => keyword.label).join(' · ');

  return (
    <div className={styles.page}>
      <div className={styles.top}>
        <IconButton
          label="뒤로 가기"
          icon={<ChevronLeftIcon />}
          size={TOP_BUTTON_SIZE}
          variant="translucent"
          onClick={() => void navigate(-1)}
        />

        {topAction ? <div className={styles.topAction}>{topAction}</div> : null}
      </div>

      <div className={styles.stage}>
        <Avatar size={AVATAR_WIDTH} shape="portrait" nickname={profile.nickname} />
      </div>

      <div className={styles.sheet}>
        <header className={styles.head}>
          {eyebrow ? <p className={styles.eyebrow}>{eyebrow}</p> : null}

          <h1 className={styles.title}>{profile.nickname}</h1>

          {meta ? <p className={styles.meta}>{meta}</p> : null}

          <p className={styles.stats}>
            <span>
              <strong>{profile.stats.frameCount}</strong> 프레임
            </span>
            <span>
              <strong>{profile.stats.popcornCount}</strong> 팝콘
            </span>
            {reviews ? (
              <span>
                <strong>{profile.stats.publicReviewCount}</strong> 한줄평
              </span>
            ) : null}
          </p>
        </header>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>취향 팝콘</h2>

          <div className={styles.popcorn}>
            <PopcornBucket
              width={BUCKET_WIDTH}
              flavors={kernelCounts(profile.popcorn)}
              label={`팝콘 ${profile.stats.popcornCount}알`}
            />
            <p className={styles.popcornCount}>팝콘 {profile.stats.popcornCount}알</p>
          </div>
        </section>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>취향 필름</h2>

          {profile.frames.length === 0 ? (
            <p className={styles.emptyNote}>공개된 프레임이 없어요</p>
          ) : (
            <ul className={styles.filmList}>
              {profile.frames.map((frame) => {
                const poster = (
                  <Poster
                    width={FILM_POSTER_WIDTH}
                    height={FILM_POSTER_HEIGHT}
                    radius={10}
                    src={frame.posterUrl ?? undefined}
                    title={frame.title}
                    seed={frame.movieId}
                  />
                );

                return (
                  <li key={frame.frameId}>
                    {frameLinks ? (
                      <Link to={path.frameDetail(frame.frameId)} className={styles.filmItem}>
                        {poster}
                        <span className={styles.filmTitle}>{frame.title}</span>
                      </Link>
                    ) : (
                      <span className={styles.filmItem}>
                        {poster}
                        <span className={styles.filmTitle}>{frame.title}</span>
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </section>

        {reviews ? (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>공개 한줄평</h2>

            {profile.reviews.length === 0 ? (
              <p className={styles.emptyNote}>공개 한줄평이 없어요</p>
            ) : (
              <ul className={styles.reviewList}>
                {profile.reviews.map((review) => (
                  <li key={review.ratingId} className={styles.reviewItem}>
                    <p className={styles.reviewHead}>
                      <RatingStars score={review.score} size={14} gap={3} />
                      <span className={styles.reviewScore}>{review.score.toFixed(1)}</span>
                      <span className={styles.reviewMovie}>{review.movieTitle}</span>
                    </p>
                    <p className={styles.reviewComment}>{review.comment}</p>
                  </li>
                ))}
              </ul>
            )}
          </section>
        ) : null}

        {children}
      </div>
    </div>
  );
}
