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
 */
import { useEffect, useRef } from 'react';
import Button from '../../../../components/Button';
import OttBadge from '../../../../components/OttBadge';
import { ottBadgeLetter } from '../../../../utils/ott';
import { SORT_OPTIONS, toggle, YEAR_BUCKETS } from '../searchFilter';
import type { SearchFilterState, YearBucketId } from '../searchFilter';
import type { FilterOptions } from '../../../../types/search';
import styles from './SearchFilterSheet.module.css';

type Props = {
  options: FilterOptions;
  value: SearchFilterState;
  onChange: (next: SearchFilterState) => void;
  onReset: () => void;
  onClose: () => void;
  /** `결과 보기 · N편`에 쓰는 개수. 세는 중이면 null */
  resultCount: number | null;
};

export default function SearchFilterSheet({
  options,
  value,
  onChange,
  onReset,
  onClose,
  resultCount,
}: Props) {
  const closeRef = useRef<HTMLButtonElement>(null);

  // 시트가 열리면 초점을 안으로 들인다. 밖에 두면 뒤 목록을 계속 읽는다
  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose();
    }

    window.addEventListener('keydown', onKeyDown);

    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className={styles.overlay}>
      <button type="button" className={styles.scrim} aria-label="필터 닫기" onClick={onClose} />

      <div className={styles.sheet} role="dialog" aria-modal="true" aria-label="검색 필터">
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
                  {genre.name}
                </button>
              ))}
            </div>
          </fieldset>

          <fieldset className={styles.group}>
            <legend className={styles.legend}>국가</legend>
            <div className={styles.options}>
              <button
                type="button"
                className={styles.option}
                aria-pressed={value.country.length === 0}
                onClick={() => onChange({ ...value, country: [] })}
              >
                전체
              </button>

              {options.countries.map((country) => (
                <button
                  key={country.code}
                  type="button"
                  className={styles.option}
                  aria-pressed={value.country.includes(country.code)}
                  onClick={() =>
                    onChange({ ...value, country: toggle(value.country, country.code) })
                  }
                >
                  {country.name}
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
                  className={styles.ott}
                  aria-pressed={value.ott.includes(ott.ottId)}
                  aria-label={ott.name}
                  onClick={() => onChange({ ...value, ott: toggle(value.ott, ott.ottId) })}
                >
                  <OttBadge
                    letter={ottBadgeLetter(ott.code)}
                    name={ott.name}
                    size={32}
                    labelled={false}
                    variant={value.ott.includes(ott.ottId) ? 'selected' : 'unselected'}
                  />
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
                  {bucket.label}
                </button>
              ))}
            </div>
          </fieldset>

          <fieldset className={styles.group}>
            <legend className={styles.legend}>정렬 기준</legend>
            <div className={styles.options}>
              {SORT_OPTIONS.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className={styles.option}
                  aria-pressed={value.sort === option.value}
                  onClick={() => onChange({ ...value, sort: option.value })}
                >
                  {option.label}
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
