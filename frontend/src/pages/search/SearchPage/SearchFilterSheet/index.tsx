/*
 * 4.3 검색 필터
 *
 * 이동표: 4.1 ⇢ 4.3 · 4.2 ⇢ 4.3 필터 버튼 · 4.3 → 4.2 결과 보기
 *
 * 와이어프레임은 검색 화면 위에 겹치는 시트로 그렸다(화면 유형 `오버레이 · 시트`).
 * 별도 라우트가 아니므로 SearchPage 폴더 안에 둔다(`common-components.md` 3.1 —
 * 한 화면에서만 쓰이면 그 화면 폴더 안).
 *
 * `결과 보기 · N편`의 개수는 **고른 조건으로 미리 세어 본 값**이다. 열려 있는 동안
 * 조건을 바꿀 때마다 다시 센다.
 *
 * 고르는 것은 **장르 · 개봉연도 · OTT** 셋이다(업무 -612). 국가는 뺐다 — API-029가 실제
 * 영화와 연결된 나라를 전부 주는데 그 수가 백을 넘어 시트가 국가 목록으로 뒤덮였다.
 * 정렬도 여기 없다. 결과 머리줄의 정렬 컨트롤이 맡는다 — 한 값을 두 자리에서 고르게 두면
 * 어느 쪽이 기준인지 헷갈린다.
 *
 * **초점이 시트 밖으로 새지 않는다**(업무 -674, UI/UX 분석 리포트 P1-06). 열리면 `닫기`로
 * 들어오고, 열린 동안 Tab · Shift+Tab은 시트 안에서만 돈다. 뒤 막(scrim)은 손가락으로 누르는
 * 자리라 Tab 순서와 보조기술에서 뺀다 — 같은 일을 `닫기`가 한다. 닫은 뒤 초점을 여는 버튼으로
 * 되돌리는 일은 여는 쪽(SearchPage)이 맡는다.
 */
import { useEffect, useRef } from 'react';
import Button from '../../../../components/Button';
import OttBadge from '../../../../components/OttBadge';
import { CheckIcon } from '../../../../components/Icon';
import { toggle, YEAR_BUCKETS } from '../searchFilter';
import type { SearchFilterState, YearBucketId } from '../searchFilter';
import type { FilterOptions } from '../../../../types/search';
import styles from './SearchFilterSheet.module.css';

type Props = {
  /** 시트 `id`. 여는 버튼의 `aria-controls`가 가리킨다 */
  id: string;
  options: FilterOptions;
  value: SearchFilterState;
  onChange: (next: SearchFilterState) => void;
  onReset: () => void;
  onClose: () => void;
  /** `결과 보기 · N편`에 쓰는 개수. 세는 중이면 null */
  resultCount: number | null;
};

const FOCUSABLE =
  'button:not(:disabled), [href], input:not(:disabled), [tabindex]:not([tabindex="-1"])';

export default function SearchFilterSheet({
  id,
  options,
  value,
  onChange,
  onReset,
  onClose,
  resultCount,
}: Props) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const sheetRef = useRef<HTMLDivElement>(null);

  // 시트가 열리면 초점을 안으로 들인다. 밖에 두면 뒤 목록을 계속 읽는다
  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
        return;
      }

      if (event.key !== 'Tab' || !sheetRef.current) return;

      // 시트 안에서만 돈다. 끝에서 넘기면 처음으로, 처음에서 거꾸로 넘기면 끝으로 간다
      const items = [...sheetRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)];

      if (items.length === 0) return;

      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      const inside = sheetRef.current.contains(active);

      if (event.shiftKey && (active === first || !inside)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !inside)) {
        event.preventDefault();
        first.focus();
      }
    }

    window.addEventListener('keydown', onKeyDown);

    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className={styles.overlay}>
      <button
        type="button"
        className={styles.scrim}
        tabIndex={-1}
        aria-hidden="true"
        onClick={onClose}
      />

      <div
        ref={sheetRef}
        id={id}
        className={styles.sheet}
        role="dialog"
        aria-modal="true"
        aria-label="검색 필터"
      >
        <header className={styles.header}>
          <h2 className={styles.title}>필터</h2>
          <button type="button" ref={closeRef} className={styles.close} onClick={onClose}>
            닫기
          </button>
        </header>

        <div className={styles.body}>
          <fieldset className={styles.group}>
            <legend className={styles.legend}>장르</legend>
            <div className={styles.options}>
              {options.genres.map((genre) => (
                <button
                  key={genre.code}
                  type="button"
                  className={styles.option}
                  aria-pressed={value.genre.includes(genre.code)}
                  onClick={() => onChange({ ...value, genre: toggle(value.genre, genre.code) })}
                >
                  {value.genre.includes(genre.code) ? <OptionCheck /> : null}
                  {genre.name}
                </button>
              ))}
            </div>
          </fieldset>

          <fieldset className={styles.group}>
            <legend className={styles.legend}>개봉연도</legend>
            <div className={styles.options}>
              {YEAR_BUCKETS.map((bucket) => (
                <button
                  key={bucket.id}
                  type="button"
                  className={styles.option}
                  aria-pressed={value.year === bucket.id}
                  onClick={() => onChange({ ...value, year: bucket.id as YearBucketId })}
                >
                  {value.year === bucket.id ? <OptionCheck /> : null}
                  {bucket.label}
                </button>
              ))}
            </div>
          </fieldset>

          <fieldset className={styles.group}>
            <legend className={styles.legend}>OTT</legend>
            <div className={styles.options}>
              {options.otts.map((ott) => (
                <button
                  key={ott.ottId}
                  type="button"
                  className={styles.option}
                  data-kind="ott"
                  aria-pressed={value.ott.includes(ott.ottId)}
                  aria-label={ott.name}
                  onClick={() => onChange({ ...value, ott: toggle(value.ott, ott.ottId) })}
                >
                  {value.ott.includes(ott.ottId) ? <OptionCheck /> : null}
                  <OttBadge code={ott.code} name={ott.name} size={24} radius={6} labelled={false} />
                  {ott.name}
                </button>
              ))}
            </div>
          </fieldset>
        </div>

        <footer className={styles.footer}>
          <Button variant="secondary" onClick={onReset}>
            초기화
          </Button>

          <Button onClick={onClose}>
            {resultCount === null ? '결과 보기' : `결과 보기 · ${resultCount}편`}
          </Button>
        </footer>
      </div>
    </div>
  );
}

/** 고른 선택지의 체크. 색이 아닌 모양으로도 고른 것을 알린다(업무 -697) */
function OptionCheck() {
  return (
    <span className={styles.check}>
      <CheckIcon />
    </span>
  );
}
