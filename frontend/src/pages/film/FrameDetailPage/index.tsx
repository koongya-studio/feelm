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
 * `[!]` 상영 시간(`115분`)은 API-043 `movie`에 없어 **표시하지 않는다.** 목업일 때는
 * 영화 기준 목업에서 채웠지만 실제 응답에는 채울 값이 없다(S15P21E106-392).
 * 개봉 연도와 장르도 응답에 따라 비어 있을 수 있어, 있는 항목만 이어 붙인다.
 * `[?]` `영화 상세 보기`의 이동 대상이 이동표에 없다. 2.4로 본다(screen-spec.md 3.1-A).
 * `한줄평 관리`는 7.6-A 시트를 연다(업무 -306). 한줄평이 없으면 비활성이다.
 * `평가 수정`은 2.5로 가며, 2.5는 이미 평가한 영화를 수정으로 처리한다.
 *
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import CommentManageSheet from '../../../components/CommentManageSheet';
import FilmStrip from '../../../components/FilmStrip';
import OttBadge from '../../../components/OttBadge';
import Poster from '../../../components/Poster';
import RatingStars from '../../../components/RatingStars';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getFrameDetail } from '../../../api/taste';
import { path } from '../../../routes/paths';
import { formatDate } from '../../../utils/date';
import styles from './FrameDetailPage.module.css';
import type { FrameDetail } from '../../../types/taste';

export default function FrameDetailPage() {
  const { frameId } = useParams();
  const navigate = useNavigate();

  const id = Number(frameId);

  const [frame, setFrame] = useState<FrameDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [managingComment, setManagingComment] = useState(false);

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
  }, [id, reloadCount]);

  if (failed) {
    return (
      <>
        <ScreenHeader title="프레임 상세" showBack />

        <StatusView
          title="프레임을 불러오지 못했어요"
          description="주소가 잘못되었거나 잠시 문제가 생겼어요"
        >
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  if (!frame) {
    return null;
  }

  const { movie, rating, watchRecord } = frame;
  // 개봉 연도가 없는 영화가 있고 장르도 비어 있을 수 있다. 빈 값은 줄에서 뺀다
  const meta = [movie.releaseYear === null ? null : String(movie.releaseYear), ...movie.genres]
    .filter((part): part is string => Boolean(part))
    .join(' · ');
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
              seed={movie.movieId}
            />
          </FilmStrip>
        </div>

        <div className={styles.movie}>
          <h2 className={styles.movieTitle}>{movie.title}</h2>
          {meta ? <p className={styles.movieMeta}>{meta}</p> : null}
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
                  code={watchRecord.ott.code}
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

          {/* 3.1-A ⇢ 7.6-A. 한줄평이 없으면 관리할 것이 없다 */}
          <Button
            variant="secondary"
            disabled={!rating.comment}
            onClick={() => setManagingComment(true)}
          >
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

      {managingComment ? (
        <CommentManageSheet
          movie={movie}
          rating={rating}
          onClose={() => setManagingComment(false)}
          onSaved={() => {
            setManagingComment(false);
            setReloadCount((count) => count + 1);
          }}
        />
      ) : null}
    </>
  );
}
