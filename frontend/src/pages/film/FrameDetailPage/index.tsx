/*
 * 3.1-A 프레임 상세
 *
 * 요소와 순서는 와이어프레임 3.1-A 그대로다(screen-spec.md 7장 3.1-A).
 * 헤더(제목·부제) · 필름 프레임 · 영화 정보 · 기록 카드 · 버튼 3개
 *
 * 연동 API는 API-043 프레임 상세 조회다. 감상 기록 없이 평가한 경우 `ott`·`clickedAt`이
 * `null`이므로(API-043 처리 규칙) 해당 줄을 표시하지 않는다. 한줄평이 없으면 한줄평
 * 줄도 표시하지 않는다. screen-spec.md 3.1-A의 미확정 항목이며, 없는 값을 빈 줄로
 * 남기지 않는 쪽을 택했다.
 *
 * `[?]` 상영 시간(`115분`)은 API-043 `movie`에 없다. 와이어프레임에 있으므로
 * types/unspecified.ts 기준에 따라 목업에만 둔다.
 * `[?]` `영화 상세 보기`의 이동 대상이 이동표에 없다. 2.4로 본다(screen-spec.md 3.1-A).
 * `[?]` `한줄평 관리`(7.6-A)는 범위 밖이라 비활성으로 둔다.
 *
 * 와이어프레임은 제목을 실제 영화(`나우 유 씨 미`)로 적지만 3.1의 목록은
 * `영화 제목 2` 자리표시자를 쓴다. 같은 영화가 화면마다 다르게 보이면 안 되므로
 * mocks/movies.ts의 값을 따른다. 연도 · 장르 · 상영 시간은 와이어프레임과 같다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import FilmStrip from '../../../components/FilmStrip';
import OttBadge from '../../../components/OttBadge';
import Poster from '../../../components/Poster';
import RatingStars from '../../../components/RatingStars';
import ScreenHeader from '../../../components/ScreenHeader';
import { getFrameDetail } from '../../../api/taste';
import { path } from '../../../routes/paths';
import { formatDate } from '../../../utils/date';
import { ottBadgeLetter } from '../../../utils/ott';
import styles from './FrameDetailPage.module.css';
import type { FrameDetailView } from '../../../types/unspecified';

export default function FrameDetailPage() {
  const { frameId } = useParams();
  const navigate = useNavigate();

  const id = Number(frameId);

  const [frame, setFrame] = useState<FrameDetailView | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getFrameDetail(id)
      .then((detail) => {
        if (active) {
          setFrame(detail);
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
  }, [id]);

  if (failed) {
    // 오류 화면은 상태 UI 업무(S15P21E106-264) 범위다. 여기서는 최소 안내만 둔다
    return (
      <div className={styles.notFound}>
        <p>프레임을 찾을 수 없습니다.</p>
      </div>
    );
  }

  if (!frame) {
    return null;
  }

  const { movie, rating, watchRecord } = frame;
  const meta = [String(movie.releaseYear), ...movie.genres, `${movie.runtimeMinutes}분`].join(
    ' · ',
  );
  const visibilityLabel = rating.visibility === 'PUBLIC' ? '공개' : '비공개';

  return (
    <>
      <ScreenHeader title="프레임 상세" subtitle="내 감상 기록 중심" showBack />

      <div className={styles.page}>
        <div className={styles.frameArea}>
          <FilmStrip orientation="horizontal" tone="dark" width={178}>
            <Poster
              width={150}
              height={210}
              radius={4}
              src={movie.posterUrl ?? undefined}
              title={movie.title}
            />
          </FilmStrip>
        </div>

        <div className={styles.movie}>
          <h2 className={styles.movieTitle}>{movie.title}</h2>
          <p className={styles.movieMeta}>{meta}</p>
        </div>

        <section className={styles.record}>
          <div className={styles.row}>
            <span className={styles.rowLabel}>내 별점</span>
            <span className={styles.rowValue}>
              <RatingStars score={rating.score} size={15} gap={3} />
              <span className={styles.score}>{rating.score.toFixed(1)}</span>
            </span>
          </div>

          {/* 감상 기록이 없으면 감상 날짜가 없다 */}
          {watchRecord.watchedAt ? (
            <div className={styles.row}>
              <span className={styles.rowLabel}>감상 날짜</span>
              <span className={styles.rowStrong}>{formatDate(watchRecord.watchedAt)}</span>
            </div>
          ) : null}

          {watchRecord.ott ? (
            <div className={styles.row}>
              <span className={styles.rowLabel}>시청한 OTT</span>
              <span className={styles.rowValue}>
                <OttBadge
                  letter={ottBadgeLetter(watchRecord.ott.code)}
                  name={watchRecord.ott.name}
                  size={22}
                  radius={6}
                  labelled={false}
                />
                <span className={styles.rowStrong}>{watchRecord.ott.name}</span>
              </span>
            </div>
          ) : null}

          {rating.comment ? (
            <div className={styles.comment}>
              <span className={styles.rowLabel}>내 한줄평 · {visibilityLabel}</span>
              <p className={styles.commentBody}>&ldquo;{rating.comment}&rdquo;</p>
            </div>
          ) : null}
        </section>

        <div className={styles.actions}>
          <Button
            variant="secondary"
            onClick={() => void navigate(path.movieRating(movie.movieId))}
          >
            평가 수정
          </Button>

          {/* 7.6-A 한줄평 관리는 범위 밖이다 */}
          <Button variant="secondary" disabled>
            한줄평 관리
          </Button>
        </div>

        <button
          type="button"
          className={styles.detailLink}
          onClick={() => void navigate(path.movieDetail(movie.movieId))}
        >
          영화 상세 보기
        </button>
      </div>
    </>
  );
}
