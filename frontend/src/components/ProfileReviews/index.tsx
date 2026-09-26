import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { getProfileReviews, getPublicProfile } from '../../api/users';
import { ApiError } from '../../api/apiError';
import RatingStars from '../RatingStars';
import { path } from '../../routes/paths';
import type { ProfileReview } from '../../types/profileReviews';
import styles from './ProfileReviews.module.css';

function Review({ review }: { review: ProfileReview }) {
  const [revealed, setRevealed] = useState(!review.spoiler);
  const [expanded, setExpanded] = useState(false);
  const text = useRef<HTMLParagraphElement>(null);
  const [long, setLong] = useState(false);
  useEffect(() => {
    const element = text.current;
    if (!element) return;
    const measure = () => setLong(element.scrollHeight > element.clientHeight + 1);
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(element);
    return () => observer?.disconnect();
  }, [revealed, review.comment]);
  return (
    <li className={styles.review}>
      <Link className={styles.title} to={path.movieDetail(review.movie.movieId)}>
        {review.movie.title}
      </Link>
      <div className={styles.score}>
        <RatingStars score={review.score} size={14} gap={3} />
        <span>{review.score.toFixed(1)}</span>
      </div>
      {revealed ? (
        <>
          <p ref={text} className={styles.comment} data-expanded={expanded || undefined}>
            {review.comment}
          </p>
          {(long || expanded) && (
            <button
              type="button"
              className={styles.textButton}
              aria-expanded={expanded}
              onClick={() => setExpanded(!expanded)}
            >
              {expanded ? '접기' : '펼치기'}
            </button>
          )}
        </>
      ) : (
        <div className={styles.spoiler}>
          <p>스포일러가 포함된 한줄평이에요</p>
          <button type="button" className={styles.textButton} onClick={() => setRevealed(true)}>
            평가 보기
          </button>
        </div>
      )}
    </li>
  );
}

/** 프로필과 전체 페이지가 같은 카드·스포일러·재시도 동작을 사용한다. */
export default function ProfileReviews({
  publicId,
  count,
  preview = false,
}: {
  publicId: string;
  count?: number;
  preview?: boolean;
}) {
  // 주소가 바뀌면 이전 사용자 목록/로컬 해제 상태를 바로 폐기한다.
  return (
    <ReviewList
      key={publicId + String(preview)}
      publicId={publicId}
      count={count}
      preview={preview}
    />
  );
}
function ReviewList({
  publicId,
  count,
  preview,
}: {
  publicId: string;
  count?: number;
  preview: boolean;
}) {
  const [items, setItems] = useState<ProfileReview[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasNext, setHasNext] = useState(false);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  const [hidden, setHidden] = useState(false);
  const [retry, setRetry] = useState(0);
  const active = useRef(true);
  const pending = useRef(false);
  const initialFailed = useRef(false);

  useEffect(() => {
    let current = true;
    active.current = true;
    pending.current = true;
    async function firstPage() {
      try {
        const profile = preview ? null : await getPublicProfile(publicId);
        if (!current) return;
        if (profile?.hidden.reviews) {
          setHidden(true);
          setItems([]);
          return;
        }
        const page = await getProfileReviews(publicId, undefined, preview ? 3 : 10);
        if (!current) return;
        if (page.hasNext && !page.nextCursor) throw new Error('invalid next cursor');
        setItems(page.items);
        setCursor(page.nextCursor);
        setHasNext(page.hasNext);
        setError(false);
        initialFailed.current = false;
      } catch (failure) {
        if (!current) return;
        initialFailed.current = true;
        if (
          failure instanceof ApiError &&
          (failure.status === 404 || failure.status === 401 || failure.status === 403)
        ) {
          setUnavailable(true);
          setItems([]);
        } else setError(true);
      } finally {
        if (current) {
          setBusy(false);
          pending.current = false;
        }
      }
    }
    void firstPage();
    return () => {
      current = false;
      active.current = false;
    };
  }, [publicId, preview, retry]);

  async function more() {
    if (pending.current || !cursor) return;
    pending.current = true;
    setBusy(true);
    setError(false);
    try {
      const profile = await getPublicProfile(publicId);
      if (!active.current) return;
      if (profile.hidden.reviews) {
        setHidden(true);
        setItems([]);
        setHasNext(false);
        return;
      }
      // 이미 표시된 항목의 개별 비공개 전환·삭제도 다시 적용한다.
      // 한 번에 최대 50개만 요청하며, 필요한 화면 길이까지만 최신 목록을 갱신한다.
      const desired = items.length + 10;
      const refreshed: ProfileReview[] = [];
      const seenCursors = new Set<string>();
      let next: string | undefined;
      let moreAvailable = false;
      let finalCursor: string | null = null;
      do {
        const page = await getProfileReviews(
          publicId,
          next,
          Math.min(50, desired - refreshed.length),
        );
        if (!active.current) return;
        if (page.hasNext && (!page.nextCursor || seenCursors.has(page.nextCursor)))
          throw new Error('invalid next cursor');
        refreshed.push(
          ...page.items.filter((item) => !refreshed.some((old) => old.ratingId === item.ratingId)),
        );
        moreAvailable = page.hasNext;
        finalCursor = page.nextCursor;
        if (page.items.length === 0) {
          refreshed.length = 0;
          moreAvailable = false;
          finalCursor = null;
          break;
        }
        if (page.nextCursor) seenCursors.add(page.nextCursor);
        next = page.nextCursor ?? undefined;
      } while (moreAvailable && refreshed.length < desired);
      setItems(refreshed);
      setCursor(finalCursor);
      setHasNext(moreAvailable);
    } catch (failure) {
      if (!active.current) return;
      if (failure instanceof ApiError && [401, 403, 404].includes(failure.status)) {
        setItems([]);
        setHasNext(false);
        setUnavailable(true);
      } else setError(true);
    } finally {
      if (active.current) {
        setBusy(false);
        pending.current = false;
      }
    }
  }
  function retryLoad() {
    if (initialFailed.current) {
      setBusy(true);
      setError(false);
      setRetry((value) => value + 1);
    } else void more();
  }
  if (unavailable) return <p role="status">한줄평을 볼 수 없어요</p>;
  if (hidden) return <p>비공개로 설정한 항목이에요</p>;
  return (
    <div className={styles.area}>
      {items.length > 0 && (
        <ul className={styles.list}>
          {items.map((review) => (
            <Review
              key={`${review.ratingId}:${review.spoiler}:${review.comment}`}
              review={review}
            />
          ))}
        </ul>
      )}
      {busy && <p role="status">한줄평을 불러오는 중이에요</p>}
      {!busy && !error && items.length === 0 && <p>아직 공개한 한줄평이 없어요</p>}
      {error && (
        <div role="status">
          <p>한줄평을 불러오지 못했어요</p>
          <button type="button" className={styles.textButton} onClick={retryLoad}>
            다시 시도
          </button>
        </div>
      )}
      {preview && hasNext && (
        <Link className={styles.more} to={path.profileReviews(publicId)}>
          공개 한줄평 전체 보기{count === undefined ? '' : ` (${count})`}
        </Link>
      )}
      {!preview && hasNext && !error && (
        <button type="button" className={styles.more} disabled={busy} onClick={() => void more()}>
          더 불러오기
        </button>
      )}
    </div>
  );
}
