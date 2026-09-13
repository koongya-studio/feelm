/*
 * 7.6 평가한 영화 · 7.7 평가하지 않은 영화
 *
 * 이동표
 * - 7.1 → 7.6 평가
 * - 7.6 → 7.7 평가 안 남긴 영화 탭
 * - 7.6 ⇢ 2.5 기존 평가 선택 및 수정
 * - 7.6 ⇢ 7.6-A 한줄평 수정 · 삭제 (시트, components/CommentManageSheet)
 * - 7.7 ⇢ 2.5 평가하기
 *
 * 와이어프레임은 두 화면을 같은 헤더(`평가`)와 같은 탭(`평가 96 | 안 남긴 영화 32`)으로
 * 그렸다. 한 컴포넌트가 두 경로를 맡고 `tab`으로 목록만 바꾼다. 탭은 이동이지만
 * `replace`로 바꿔 끼운다 — 뒤로 가기가 탭 사이를 오가지 않고 7.1로 돌아가야 한다.
 *
 * | 탭 | API | 수 |
 * | --- | --- | --- |
 * | 평가 | API-039 내 평가 목록 | API-042 `totalCount`. 평가와 프레임은 1:1이다(API-039 처리 규칙) |
 * | 안 남긴 영화 | API-034 `status=WATCHED_UNRATED` | 받은 목록의 길이 |
 *
 * `[?]` 7.6 줄 오른쪽의 동그란 버튼이 무엇인지 와이어프레임에 적혀 있지 않다. 채운 것과
 * 빈 것이 섞여 있고 이동표에 `7.6 ⇢ 7.6-A`가 있어 **한줄평 관리**로 보았다. 한줄평이
 * 있는 줄에만 둔다 — 없는 한줄평은 관리할 것이 없다.
 * `[?]` `안 남긴 영화 N`의 N은 계약에 총수가 없어 받은 목록을 센 값이다.
 *
 * 어두운 블록은 없다. 각 줄의 동작은 테두리 버튼이다 — 검정 알약은 화면당 하나인데
 * (`design-concept.md` 2.4) 줄마다 같은 동작이 반복되므로 어느 하나를 주 동작으로
 * 올릴 수 없다.
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import Button from '../../../components/Button';
import CommentManageSheet from '../../../components/CommentManageSheet';
import MovieRow from '../../../components/MovieRow';
import RatingStars from '../../../components/RatingStars';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getOtts } from '../../../api/otts';
import { getMyFrames } from '../../../api/taste';
import { getMyRatings, getWatchRecords } from '../../../api/watch';
import { path } from '../../../routes/paths';
import { formatShortDate } from '../../../utils/date';
import styles from './MyRatingsPage.module.css';
import type { Ott } from '../../../types/ott';
import type { MyRatingListItem, WatchRecordListItem } from '../../../types/watch';

type Props = {
  tab: 'rated' | 'unrated';
};

type Loaded = {
  ratings: MyRatingListItem[];
  ratingCount: number;
  unrated: WatchRecordListItem[];
  otts: Ott[];
};

export default function MyRatingsPage({ tab }: Props) {
  const [data, setData] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  /** 7.6-A 시트를 연 평가 */
  const [managing, setManaging] = useState<MyRatingListItem | null>(null);

  /*
   * 두 탭의 수가 탭 머리에 함께 나오므로 어느 탭이든 둘 다 읽는다. 7.7에서 평가하고
   * 돌아오면 `평가 N`이 하나 늘고 `안 남긴 영화 N`이 하나 줄어야 한다.
   */
  useEffect(() => {
    let active = true;

    Promise.all([
      getMyRatings(),
      getMyFrames(),
      getWatchRecords({ status: ['WATCHED_UNRATED'] }),
      getOtts(),
    ])
      .then(([ratings, frames, unrated, otts]) => {
        if (!active) return;

        setData({
          ratings: ratings.items,
          ratingCount: frames.totalCount,
          unrated: unrated.items,
          otts,
        });
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  const header = <ScreenHeader title="평가" showBack />;

  if (failed) {
    return (
      <>
        {header}

        <StatusView title="평가 기록을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
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

  if (!data) {
    return header;
  }

  return (
    <>
      {header}

      <div className={styles.page}>
        <nav className={styles.tabs} aria-label="평가 기록">
          <Link
            to={path.myRatings()}
            replace
            className={styles.tab}
            aria-current={tab === 'rated' ? 'page' : undefined}
          >
            평가 {data.ratingCount}
          </Link>
          <Link
            to={path.unratedMovies()}
            replace
            className={styles.tab}
            aria-current={tab === 'unrated' ? 'page' : undefined}
          >
            안 남긴 영화 {data.unrated.length}
          </Link>
        </nav>

        {tab === 'rated' ? (
          <RatedList ratings={data.ratings} onManageComment={setManaging} />
        ) : (
          <UnratedList records={data.unrated} otts={data.otts} />
        )}
      </div>

      {managing ? (
        <CommentManageSheet
          movie={managing.movie}
          rating={managing}
          onClose={() => setManaging(null)}
          onSaved={() => {
            setManaging(null);
            setReloadCount((count) => count + 1);
          }}
        />
      ) : null}
    </>
  );
}

function RatedList({
  ratings,
  onManageComment,
}: {
  ratings: MyRatingListItem[];
  onManageComment: (rating: MyRatingListItem) => void;
}) {
  if (ratings.length === 0) {
    return (
      <StatusView
        variant="empty"
        size="inline"
        title="아직 평가한 영화가 없어요"
        description="영화를 평가하면 필름에 컷이 쌓여요"
      />
    );
  }

  return (
    <>
      <ul className={styles.list}>
        {ratings.map((rating) => (
          <li key={rating.ratingId}>
            <MovieRow
              movie={rating.movie}
              to={path.movieRating(rating.movie.movieId)}
              trailing={
                rating.comment ? (
                  <button
                    type="button"
                    className={styles.rowAction}
                    aria-label={`${rating.movie.title} 한줄평 관리`}
                    onClick={() => onManageComment(rating)}
                  >
                    한줄평
                  </button>
                ) : undefined
              }
            >
              <span className={styles.score}>
                <RatingStars score={rating.score} size={12} gap={2} />
                {rating.score.toFixed(1)}
              </span>

              <span className={styles.meta}>
                {formatShortDate(rating.createdAt)}
                {rating.comment ? ` · “${rating.comment}”` : null}
              </span>
            </MovieRow>
          </li>
        ))}
      </ul>

      <p className={styles.hint}>평가를 누르면 수정할 수 있어요</p>
    </>
  );
}

function UnratedList({ records, otts }: { records: WatchRecordListItem[]; otts: Ott[] }) {
  if (records.length === 0) {
    return (
      <StatusView
        variant="empty"
        size="inline"
        title="평가를 기다리는 영화가 없어요"
        description="본 영화는 모두 평가했어요"
      />
    );
  }

  return (
    <>
      <ul className={styles.list}>
        {records.map((record) => {
          const ottName = otts.find((ott) => ott.ottId === record.ott.ottId)?.name;
          // 감상을 확인한 기록이라 `watchedAt`이 있다. 없으면 링크를 누른 날로 대신한다
          const date = formatShortDate(record.watchedAt ?? record.clickedAt);

          return (
            <li key={record.watchRecordId}>
              <MovieRow
                movie={record.movie}
                trailing={
                  <Link
                    to={path.movieRating(record.movie.movieId)}
                    className={styles.rowAction}
                    aria-label={`${record.movie.title} 평가하기`}
                  >
                    평가하기
                  </Link>
                }
              >
                <span className={styles.meta}>
                  {ottName ? `${date} · ${ottName}에서 시청` : date}
                </span>
              </MovieRow>
            </li>
          );
        })}
      </ul>

      <p className={styles.hint}>평가하면 필름에 컷이 추가돼요</p>
    </>
  );
}
