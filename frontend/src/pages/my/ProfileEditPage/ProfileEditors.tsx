/*
 * 7.2 항목별 편집기 (업무 S15P21E106-616)
 *
 * 연필을 누른 구역의 보기 자리에 그대로 들어선다(`ProfileLayout` `editing`). 편집기는 값을
 * 들고 있지 않는다 — 초안 · 저장 · 취소는 화면(7.2)이 갖고, 여기는 그리는 일만 한다.
 *
 * 저장 · 취소는 모든 편집기에서 같은 자리 · 같은 모양이다(`EditorActions`). 저장은 글자와
 * 빨간 면, 취소는 글자와 테두리로 구분한다 — 색만으로 가르지 않는다.
 */
import Poster from '../../../components/Poster';
import TextField from '../../../components/TextField';
import { CheckIcon } from '../../../components/Icon';
import { BIO_MAX_LENGTH } from '../../../api/users';
import styles from './ProfileEditPage.module.css';
import type { FormEvent, ReactNode } from 'react';
import type { ProfileFrame, ProfileKeyword } from '../../../types/unspecified';

type ActionProps = {
  saving: boolean;
  onCancel: () => void;
  /** 저장 실패 문구. 입력값은 그대로 두고 여기에만 알린다 */
  error?: string;
};

/** 모든 편집기의 아래 줄. 저장 중에는 저장만 잠긴다 — 취소로 빠져나갈 수는 있다 */
function EditorActions({ saving, onCancel, error }: ActionProps) {
  return (
    <>
      {error ? (
        <p className={styles.editorError} role="alert">
          {error}
        </p>
      ) : null}

      <div className={styles.editorActions}>
        <button type="button" className={styles.cancelButton} onClick={onCancel}>
          취소
        </button>
        <button
          type="submit"
          className={styles.saveButton}
          disabled={saving}
          aria-busy={saving || undefined}
        >
          {saving ? '저장 중' : '저장'}
        </button>
      </div>
    </>
  );
}

/** 편집기의 겉. Enter · 저장 버튼 모두 `onSave`로 간다 */
function EditorForm({
  label,
  onSave,
  children,
  className,
}: {
  label: string;
  onSave: () => void;
  children: ReactNode;
  className?: string;
}) {
  return (
    <form
      className={className ?? styles.editor}
      aria-label={label}
      onSubmit={(event: FormEvent) => {
        event.preventDefault();
        onSave();
      }}
    >
      {children}
    </form>
  );
}

/** 닉네임 상한. 가입(API-001)과 같은 규칙이다 */
const NICKNAME_MAX = 10;

/** 규칙 안내. 계약(API-001 · API-015)의 문자 규칙 그대로다 */
export const NICKNAME_HINT = '한글, 영문, 숫자, _ 2~10자';

export function NicknameEditor({
  value,
  onChange,
  onSave,
  hint,
  error,
  ...actions
}: ActionProps & {
  value: string;
  onChange: (value: string) => void;
  onSave: () => void;
  /** 사용 가능 확인 결과처럼 오류가 아닌 안내 */
  hint?: string;
}) {
  return (
    <EditorForm label="닉네임 수정" onSave={onSave}>
      <TextField
        label="닉네임"
        value={value}
        maxLength={NICKNAME_MAX}
        autoFocus
        autoComplete="nickname"
        onChange={(event) => onChange(event.target.value)}
        trailing={`${[...value].length}/${NICKNAME_MAX}`}
        hint={hint ?? NICKNAME_HINT}
        error={error}
      />
      <EditorActions {...actions} />
    </EditorForm>
  );
}

export function BioEditor({
  value,
  onChange,
  onSave,
  ...actions
}: ActionProps & { value: string; onChange: (value: string) => void; onSave: () => void }) {
  const length = [...value].length;

  return (
    <EditorForm label="소개 수정" onSave={onSave} className={styles.bioCard}>
      {/* 글자 수는 이름에 섞이지 않게 입력칸 밖에 두고, 이름은 입력칸에 직접 붙인다 */}
      <div className={styles.bioField}>
        <textarea
          aria-label="자기소개"
          className={styles.bioInput}
          value={value}
          rows={2}
          maxLength={BIO_MAX_LENGTH}
          autoFocus
          placeholder="나를 소개하는 한 줄을 적어 보세요"
          onChange={(event) => onChange(event.target.value)}
        />
        <span className={styles.counter} aria-live="polite">
          {length}/{BIO_MAX_LENGTH}
        </span>
      </div>
      <EditorActions {...actions} />
    </EditorForm>
  );
}

/**
 * 공개할 키워드 고르기. 모든 키워드를 칩으로 늘어놓고 누른 것만 공개한다.
 *
 * 고른 칩은 채운 면 **과 체크 표시**로 보인다. 색만으로 구분하지 않는다.
 */
export function KeywordsEditor({
  keywords,
  selected,
  onToggle,
  onSave,
  ...actions
}: ActionProps & {
  keywords: ProfileKeyword[];
  selected: string[];
  onToggle: (code: string) => void;
  onSave: () => void;
}) {
  return (
    <EditorForm label="공개할 취향 키워드 수정" onSave={onSave}>
      <p className={styles.editorHint}>다른 사용자에게 보여 줄 키워드를 골라요</p>

      <ul className={styles.toggleChips}>
        {keywords.map((keyword) => {
          const on = selected.includes(keyword.code);

          return (
            <li key={keyword.code}>
              <button
                type="button"
                className={styles.toggleChip}
                aria-pressed={on}
                onClick={() => onToggle(keyword.code)}
              >
                {on ? <CheckIcon /> : null}
                {keyword.label}
              </button>
            </li>
          );
        })}
      </ul>

      <EditorActions {...actions} />
    </EditorForm>
  );
}

/** 공개할 필름 고르기. 포스터를 눌러 켜고 끈다. 공개 프로필에는 앞의 세 편까지 보인다 */
export function FilmsEditor({
  frames,
  selected,
  onToggle,
  onSave,
  ...actions
}: ActionProps & {
  frames: ProfileFrame[];
  selected: number[];
  onToggle: (frameId: number) => void;
  onSave: () => void;
}) {
  return (
    <EditorForm label="공개할 취향 필름 수정" onSave={onSave}>
      <p className={styles.editorHint}>
        다른 사용자에게 보여 줄 필름을 골라요. 최근 3편까지 보여요
      </p>

      <ul className={styles.toggleFilms}>
        {frames.map((frame) => {
          const on = selected.includes(frame.frameId);

          return (
            <li key={frame.frameId}>
              <button
                type="button"
                className={styles.toggleFilm}
                aria-pressed={on}
                aria-label={frame.title}
                onClick={() => onToggle(frame.frameId)}
              >
                <Poster
                  fluid
                  width={2}
                  height={3}
                  radius={10}
                  src={frame.posterUrl ?? undefined}
                  title={frame.title}
                  seed={frame.movieId}
                />
                <span className={styles.toggleMark} aria-hidden="true">
                  {on ? <CheckIcon /> : null}
                </span>
              </button>
            </li>
          );
        })}
      </ul>

      <EditorActions {...actions} />
    </EditorForm>
  );
}
