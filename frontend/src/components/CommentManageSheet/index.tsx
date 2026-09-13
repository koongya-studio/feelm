/*
 * 7.6-A 한줄평 관리
 *
 * 이동표: 7.6 ⇢ 7.6-A 한줄평 수정 · 삭제 (삭제 확인) · 3.1-A ⇢ 7.6-A 한줄평 관리
 *
 * **별도 화면이 아니라 시트다.** 와이어프레임 7.6-A는 흐려진 7.6 목록 위에 아래에서
 * 올라온 시트와 삭제 확인 창을 겹쳐 그렸다. 두 화면(7.6 · 3.1-A)에서 열리므로 공용
 * 컴포넌트로 둔다(`common-components.md` 3.1).
 *
 * 요소는 와이어프레임 그대로다 — 영화와 내 별점, 한줄평 입력과 글자 수, 공개 스위치,
 * `수정 완료` · `삭제`, 삭제 확인(`한줄평을 삭제할까요?` · `삭제하면 되돌릴 수 없어요`).
 *
 * 연동은 API-037 평가 수정 하나다. 계약이 한줄평만 따로 고치는 API를 두지 않고
 * `comment: null`로 지우게 했다. 그래서 별점은 받은 값을 그대로 다시 보낸다.
 *
 * - 글자 수 상한은 **200**이다. 와이어프레임은 `24/100`이지만 계약이 200자다(API-036)
 * - 공개 스위치는 계약의 `visibility`다. 2.5는 이 선택 UI가 없어 비공개로 저장하므로
 *   (CR-05) 공개로 바꾸는 곳은 지금 여기뿐이다
 * - `수정 완료`는 비어 있으면 누를 수 없다. 비우는 것은 `삭제`의 몫이다
 */
import { useEffect, useId, useRef, useState } from 'react';
import Button from '../Button';
import Poster from '../Poster';
import RatingStars from '../RatingStars';
import TextArea from '../TextArea';
import { updateRating } from '../../api/watch';
import styles from './CommentManageSheet.module.css';
import type { MyRating } from '../../types/movie';

/** API-036 `comment` 200자 이하. TextArea도 같은 값으로 입력을 막는다 */
const COMMENT_MAX_LENGTH = 200;

type Props = {
  movie: { movieId: number; title: string; posterUrl: string | null };
  rating: MyRating;
  onClose: () => void;
  /** 수정이나 삭제가 저장된 뒤. 부르는 쪽이 값을 다시 읽고 시트를 닫는다 */
  onSaved: () => void;
};

export default function CommentManageSheet({ movie, rating, onClose, onSaved }: Props) {
  const [comment, setComment] = useState(rating.comment ?? '');
  const [isPublic, setIsPublic] = useState(rating.visibility === 'PUBLIC');
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);

  const id = useId();
  const titleRef = useRef<HTMLHeadingElement>(null);
  const confirmRef = useRef<HTMLDivElement>(null);

  // 시트가 열리면 초점을 안으로 들인다. 밖에 두면 뒤 목록을 계속 읽는다
  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  // 삭제 확인이 뜨면 되돌릴 수 있는 쪽(취소, 첫 버튼)에 초점을 둔다
  useEffect(() => {
    if (confirmingDelete) {
      confirmRef.current?.querySelector('button')?.focus();
    }
  }, [confirmingDelete]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Escape' || saving) return;

      // 확인 창이 떠 있으면 그것만 닫는다
      if (confirmingDelete) {
        setConfirmingDelete(false);
      } else {
        onClose();
      }
    }

    window.addEventListener('keydown', onKeyDown);

    return () => window.removeEventListener('keydown', onKeyDown);
  }, [confirmingDelete, onClose, saving]);

  async function save(nextComment: string | null) {
    setSaving(true);
    setFailed(false);

    try {
      await updateRating(rating.ratingId, {
        score: rating.score,
        comment: nextComment,
        visibility: isPublic ? 'PUBLIC' : 'PRIVATE',
      });
      onSaved();
    } catch {
      setFailed(true);
      setSaving(false);
      setConfirmingDelete(false);
    }
  }

  const trimmed = comment.trim();

  return (
    <div className={styles.overlay}>
      <button
        type="button"
        className={styles.scrim}
        aria-label="한줄평 관리 닫기"
        disabled={saving}
        onClick={onClose}
      />

      <div className={styles.sheet} role="dialog" aria-modal="true" aria-labelledby={`${id}-title`}>
        <span className={styles.handle} aria-hidden="true" />

        <h2 id={`${id}-title`} ref={titleRef} tabIndex={-1} className={styles.title}>
          한줄평 관리
        </h2>

        <div className={styles.movie}>
          <Poster
            width={40}
            height={56}
            radius={5}
            src={movie.posterUrl ?? undefined}
            title={movie.title}
            seed={movie.movieId}
          />

          <div className={styles.movieText}>
            <p className={styles.movieTitle}>{movie.title}</p>
            <p className={styles.myScore}>
              내 별점
              <RatingStars score={rating.score} size={12} gap={2} />
              <span className={styles.scoreValue}>{rating.score.toFixed(1)}</span>
            </p>
          </div>
        </div>

        <div className={styles.field}>
          <TextArea label="한줄평" value={comment} onChange={setComment} disabled={saving} />
          <span className={styles.counter}>
            {comment.length}/{COMMENT_MAX_LENGTH}
          </span>
        </div>

        <div className={styles.visibility}>
          <span id={`${id}-visibility`} className={styles.visibilityLabel}>
            공개 한줄평 · 다른 사용자에게 노출
          </span>

          <button
            type="button"
            className={styles.toggle}
            role="switch"
            aria-checked={isPublic}
            aria-labelledby={`${id}-visibility`}
            disabled={saving}
            onClick={() => setIsPublic((value) => !value)}
          >
            <span className={styles.knob} aria-hidden="true" />
          </button>
        </div>

        {failed ? (
          <p className={styles.error} role="alert">
            저장하지 못했어요. 잠시 후 다시 시도해 주세요
          </p>
        ) : null}

        <div className={styles.actions}>
          <Button disabled={!trimmed || saving} onClick={() => void save(trimmed)}>
            수정 완료
          </Button>

          <Button
            variant="secondary"
            disabled={saving || !rating.comment}
            onClick={() => setConfirmingDelete(true)}
          >
            삭제
          </Button>
        </div>
      </div>

      {confirmingDelete ? (
        <div className={styles.confirmLayer}>
          <div
            ref={confirmRef}
            className={styles.confirm}
            role="alertdialog"
            aria-modal="true"
            aria-labelledby={`${id}-delete-title`}
            aria-describedby={`${id}-delete-description`}
          >
            <p id={`${id}-delete-title`} className={styles.confirmTitle}>
              한줄평을 삭제할까요?
            </p>
            <p id={`${id}-delete-description`} className={styles.confirmDescription}>
              삭제하면 되돌릴 수 없어요
            </p>

            <div className={styles.confirmActions}>
              <Button
                variant="secondary"
                disabled={saving}
                onClick={() => setConfirmingDelete(false)}
              >
                취소
              </Button>
              <Button disabled={saving} onClick={() => void save(null)}>
                삭제
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
