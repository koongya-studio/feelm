/*
 * 4.2 검색 — 걸려 있는 필터 칩 (업무 S15P21E106-784).
 *
 * 2026-09-21 PC 시안이 검색줄 아래에 둔 `한국 ✕` · `2010년대 ✕` 줄이다. 넓은 화면에서는
 * 시트를 열지 않아도 무엇이 걸려 있는지 보이고, 칩 하나를 눌러 그것만 풀 수 있다.
 * 412px 이하에서는 그리지 않는다 — 그 폭의 필터 상태는 시트와 필터 단추의 표식이 말한다.
 *
 * `[!]` **시안의 `한국`은 국가 필터인데 이 화면은 국가를 고르게 하지 않는다**(업무 -612 —
 * 계약이 주는 국가가 백을 넘어 시트가 국가 목록으로 뒤덮였다). 그 결정을 이 업무가 되돌리지
 * 않으므로(사용자 결정 2026-09-22) 칩은 **지금 고를 수 있는 것**만 보여준다 — 장르 · OTT ·
 * 개봉연도다. 국가 필터는 후속 업무로 둔다.
 *
 * 정렬은 칩이 아니다. 결과 머리줄의 정렬 컨트롤이 늘 현재 값을 보여주고 있어, 칩으로 또
 * 적으면 같은 값이 두 자리에 선다.
 */
import { EMPTY_FILTER, YEAR_BUCKETS } from '../searchFilter';
import styles from './SearchFilterChips.module.css';
import type { SearchFilterState } from '../searchFilter';
import type { FilterOptions } from '../../../../types/search';

type Chip = {
  key: string;
  label: string;
  /** 이 칩만 풀었을 때의 필터 */
  next: SearchFilterState;
};

type Props = {
  filter: SearchFilterState;
  /** 코드를 이름으로 바꿀 기준정보(API-029). 못 받았으면 이름 대신 코드가 남으므로 그린다 */
  options: FilterOptions | null;
  onChange: (next: SearchFilterState) => void;
  onReset: () => void;
};

function chipsOf(filter: SearchFilterState, options: FilterOptions | null): Chip[] {
  const chips: Chip[] = [];

  for (const code of filter.genre) {
    chips.push({
      key: `genre:${code}`,
      label: options?.genres.find((genre) => genre.code === code)?.name ?? code,
      next: { ...filter, genre: filter.genre.filter((value) => value !== code) },
    });
  }

  for (const ottId of filter.ott) {
    chips.push({
      key: `ott:${ottId}`,
      label: options?.otts.find((ott) => ott.ottId === ottId)?.name ?? `OTT ${ottId}`,
      next: { ...filter, ott: filter.ott.filter((value) => value !== ottId) },
    });
  }

  if (filter.year !== EMPTY_FILTER.year) {
    const bucket = YEAR_BUCKETS.find((item) => item.id === filter.year);

    chips.push({
      key: `year:${filter.year}`,
      label: bucket?.label ?? filter.year,
      next: { ...filter, year: EMPTY_FILTER.year },
    });
  }

  return chips;
}

export default function SearchFilterChips({ filter, options, onChange, onReset }: Props) {
  const chips = chipsOf(filter, options);

  if (chips.length === 0) return null;

  return (
    <div className={styles.row}>
      <ul className={styles.chips}>
        {chips.map((chip) => (
          <li key={chip.key}>
            <button
              type="button"
              className={styles.chip}
              aria-label={`${chip.label} 필터 해제`}
              onClick={() => onChange(chip.next)}
            >
              {chip.label}
              <span className={styles.remove} aria-hidden="true">
                ✕
              </span>
            </button>
          </li>
        ))}
      </ul>

      <button type="button" className={styles.reset} onClick={onReset}>
        필터 초기화
      </button>
    </div>
  );
}
