import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import Poster from '../../../components/Poster';
import TextField from '../../../components/TextField';
import { CheckIcon } from '../../../components/Icon';
import styles from './FilmsEditor.module.css';
import type { ProfileFrame } from '../../../types/unspecified';

const PAGE_SIZE = 12;

type Props = {
  frames: ProfileFrame[];
  selected: number[];
  saving: boolean;
  suspended?: boolean;
  error?: string;
  onToggle: (frameId: number) => void;
  onSave: () => void;
  onCancel: () => void;
};

/** S15P21E106-952: 필름 선택 초안은 페이지가 유지하고 이 창은 탐색만 담당한다. */
export default function FilmsEditor({
  frames,
  selected,
  saving,
  suspended = false,
  error,
  onToggle,
  onSave,
  onCancel,
}: Props) {
  const [query, setQuery] = useState('');
  const [publicOnly, setPublicOnly] = useState(false);
  const [page, setPage] = useState(1);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const selectedIds = new Set(selected);
  const term = query.trim().toLocaleLowerCase();
  const filtered = frames.filter(
    (frame) =>
      (!publicOnly || selectedIds.has(frame.frameId)) &&
      frame.title.toLocaleLowerCase().includes(term),
  );
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  const shown = filtered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

  useEffect(() => {
    const opener = document.activeElement;
    const overflow = document.body.style.overflow;
    const dialog = dialogRef.current;
    document.body.style.overflow = 'hidden';
    return () => {
      dialog?.close();
      document.body.style.overflow = overflow;
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, []);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (suspended) dialog?.close();
    else {
      dialog?.showModal();
      dialog?.querySelector('input')?.focus();
    }
  }, [suspended]);

  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = 0;
  }, [currentPage, query, publicOnly]);

  return createPortal(
    <dialog
      ref={dialogRef}
      className={styles.dialog}
      aria-label="공개할 취향 필름 관리"
      aria-modal="true"
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
    >
      <form
        className={styles.form}
        aria-label="공개할 취향 필름 수정"
        onSubmit={(event) => {
          event.preventDefault();
          onSave();
        }}
      >
        <div className={styles.header}>
          <h2>공개할 취향 필름 관리</h2>
          <p>다른 사용자에게 보여 줄 필름을 골라요. 최근 3편까지 보여요</p>
          <TextField
            label="영화 제목 검색"
            value={query}
            placeholder="영화 제목으로 찾아보세요"
            onChange={(event) => {
              setQuery(event.target.value);
              setPage(1);
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') event.preventDefault();
            }}
          />
          <div className={styles.filters} aria-label="필름 표시 필터">
            <button
              type="button"
              aria-pressed={!publicOnly}
              onClick={() => {
                setPublicOnly(false);
                setPage(1);
              }}
            >
              전체 {frames.length}편
            </button>
            <button
              type="button"
              aria-pressed={publicOnly}
              onClick={() => {
                setPublicOnly(true);
                setPage(1);
              }}
            >
              공개 중 {selected.length}편
            </button>
          </div>
        </div>
        <div ref={listRef} className={styles.results}>
          {shown.length ? (
            <ul className={styles.films} aria-label="공개 설정할 필름">
              {shown.map((frame) => {
                const on = selectedIds.has(frame.frameId);
                return (
                  <li key={frame.frameId}>
                    <button
                      type="button"
                      className={styles.film}
                      aria-pressed={on}
                      aria-label={frame.title}
                      onClick={() => onToggle(frame.frameId)}
                    >
                      <span className={styles.poster}>
                        <Poster
                          fluid
                          width={2}
                          height={3}
                          radius={10}
                          src={frame.posterUrl ?? undefined}
                          title={frame.title}
                          seed={frame.movieId}
                        />
                        <span className={styles.mark} aria-hidden="true">
                          {on ? <CheckIcon /> : null}
                        </span>
                      </span>
                      <span className={styles.title}>{frame.title}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : (
            <p className={styles.empty} role="status">
              {term
                ? '검색 결과가 없어요'
                : publicOnly
                  ? '공개 중인 필름이 없어요'
                  : '아직 취향 필름이 없어요'}
            </p>
          )}
        </div>
        <div className={styles.footer}>
          <nav className={styles.pagination} aria-label="필름 페이지 이동">
            <button
              type="button"
              disabled={currentPage === 1}
              onClick={() => setPage(currentPage - 1)}
            >
              이전
            </button>
            <span role="status">
              {currentPage} / {totalPages} 페이지 · {filtered.length}편
            </span>
            <button
              type="button"
              disabled={currentPage === totalPages}
              onClick={() => setPage(currentPage + 1)}
            >
              다음
            </button>
          </nav>
          {error ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}
          <div className={styles.actions}>
            <button type="button" onClick={onCancel}>
              취소
            </button>
            <button
              type="submit"
              className={styles.save}
              disabled={saving}
              aria-busy={saving || undefined}
            >
              {saving ? '저장 중' : '저장'}
            </button>
          </div>
        </div>
      </form>
    </dialog>,
    document.body,
  );
}
