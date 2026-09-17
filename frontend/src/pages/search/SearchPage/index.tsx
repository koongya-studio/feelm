/*
 * 4.1 검색 홈 · 4.2 영화 검색 결과 · 4.4 검색 결과 없음
 *
 * 세 프레임이 한 화면의 세 상태다. 검색어가 없으면 4.1, 결과가 있으면 4.2, 조건에
 * 맞는 것이 없으면 4.4다. 2.2와 2.2-A를 한 화면으로 둔 것과 같은 방식이다.
 * 4.3 필터는 이 화면 위에 겹치는 시트이므로 `SearchFilterSheet`로 뒀다.
 *
 * 이동표
 * 4.1 → 4.2 검색어 입력 · 4.1 ⇢ 4.3 필터 버튼 · 4.1 ⇢ 2.4 인기 영화 선택
 * 4.2 → 2.4 검색 결과 영화 선택 · 4.2 ⇢ 4.3 필터 버튼 · 4.2 ⇢ 4.4 결과 없음
 * 4.3 → 4.2 결과 보기 · 4.4 ⇢ 4.1 검색어 초기화 또는 다시 검색
 *
 * **검색은 제출했을 때만 한다.** 와이어프레임 4.2가 입력 옆에 `취소`를 두고 결과를
 * 함께 보여주므로 타이핑 중에도 결과가 보이는 것처럼 읽히지만, 글자마다 조회하면
 * 실제 연동에서 요청이 폭증한다. 계약에 디바운스 규칙이 없어 `[?]`로 남기고 제출
 * 방식으로 뒀다.
 *
 * **검색과 필터 옵션은 실제 API다**(업무 -476). 급상승만 목업으로 남는다
 * (`api/search.ts` 머리말). 검색은 회원 전용이라 로그인하지 않고 들어오면 제출이
 * `401`로 실패하고 4.2 자리에 오류 상태가 선다.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import StatusView from '../../../components/StatusView';
import {
  countSearchResults,
  getFilterOptions,
  getTrendingMovies,
  searchMovies,
} from '../../../api/search';
import { getOtts } from '../../../api/otts';
import { mockSearchMovies } from '../../../mocks/search';
import {
  addRecentSearchQuery,
  clearRecentSearchQueries,
  getRecentSearchQueries,
} from '../../../mocks/sessionStore';
import { path } from '../../../routes/paths';
import SearchFilterSheet from './SearchFilterSheet';
import SearchResultRow from './SearchResultRow';
import TrendingRow from './TrendingRow';
import { EMPTY_FILTER, isEmptyFilter, toSearchQuery } from './searchFilter';
import type { SearchFilterState } from './searchFilter';
import type { FilterOptions, MovieSearchItem, MovieSearchQuery } from '../../../types/search';
import type { TrendingItem } from '../../../types/trend';
import styles from './SearchPage.module.css';

/** 와이어프레임 4.1은 급상승을 6위까지 보여준다 */
const TRENDING_SIZE = 6;

/**
 * 한 번 제출한 검색과 그 뒤로 이어 받은 결과.
 *
 * `query`를 함께 들고 다니는 것은 **커서가 조건에 묶여 있기 때문**이다(API-028 처리
 * 규칙). `더 보기`는 이 조건 그대로 다음 장을 불러야 하고, 조건이 바뀌면 커서를 버리고
 * 첫 장부터 다시 받는다.
 */
type SearchResultState = {
  query: MovieSearchQuery;
  /** cursor · size를 적용하기 전 전체 건수. 받아 온 `items`의 길이가 아니다 */
  totalCount: number;
  items: MovieSearchItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** `더 보기` 한 번의 상태. 실패해도 이미 받은 목록은 남는다 */
type MoreState = 'idle' | 'loading' | 'failed';

/**
 * 급상승 행에 붙일 OTT 코드.
 *
 * API-064 응답에 OTT가 없어(`mocks/trending.ts` 머리말) 같은 movieId를 검색 목업에서
 * 찾아 채운다. **계약이 채워 주면 이 함수는 사라진다.**
 */
function trendingOttProviders(movieId: number): string[] {
  return mockSearchMovies.find((movie) => movie.movieId === movieId)?.ottProviders ?? [];
}

/** 4.2 결과 · 4.4 결과 없음 */
function SearchResults({
  failed,
  result,
  moreState,
  onRetry,
  onReset,
  onMore,
  onSelect,
  ottNames,
}: {
  failed: boolean;
  result: SearchResultState | null;
  moreState: MoreState;
  onRetry: () => void;
  onReset: () => void;
  onMore: () => void;
  onSelect: (movieId: number) => void;
  ottNames: Record<string, string>;
}) {
  if (failed || !result) {
    return (
      <StatusView title="검색하지 못했어요" description="잠시 후 다시 시도해 주세요.">
        <Button variant="text" onClick={onRetry}>
          다시 시도
        </Button>
      </StatusView>
    );
  }

  if (result.items.length === 0) {
    return (
      <StatusView
        variant="empty"
        title="검색 결과가 없어요"
        description={'다른 영화 제목이나 감독,\n배우 이름으로 검색해 보세요'}
      >
        <Button variant="text" onClick={onReset}>
          검색어 초기화
        </Button>
      </StatusView>
    );
  }

  return (
    <section className={styles.results}>
      <h2 className={styles.sectionLabel}>검색 결과 {result.totalCount}건</h2>

      <ul className={styles.resultList}>
        {result.items.map((movie) => (
          <li key={movie.movieId}>
            <SearchResultRow
              movie={movie}
              ottNames={ottNames}
              onSelect={() => onSelect(movie.movieId)}
            />
          </li>
        ))}
      </ul>

      {result.hasNext ? (
        <div className={styles.more}>
          <Button variant="secondary" onClick={onMore} disabled={moreState === 'loading'}>
            {moreState === 'failed' ? '다시 시도' : '더 보기'}
          </Button>

          {moreState === 'failed' ? (
            <p className={styles.moreError}>다음 결과를 불러오지 못했어요.</p>
          ) : null}
        </div>
      ) : null}

      <p className={styles.hint}>포스터를 누르면 상세페이지로 이동해요</p>
    </section>
  );
}

/** 4.1 급상승 영화 */
function TrendingSection({
  failed,
  items,
  onSelect,
  ottNames,
}: {
  failed: boolean;
  items: TrendingItem[] | null;
  /** 제목으로 검색한다. 급상승은 목업(API-064 미구현)이라 상세로 바로 가지 않는다(업무 -610) */
  onSelect: (title: string) => void;
  ottNames: Record<string, string>;
}) {
  if (failed) {
    return <StatusView size="inline" title="급상승 영화를 불러오지 못했어요" />;
  }

  // 아직 받기 전에는 아무것도 그리지 않는다 (팀 결정 2026-09-08)
  if (!items) return null;

  if (items.length === 0) {
    return <StatusView size="inline" variant="empty" title="아직 집계된 급상승 영화가 없어요" />;
  }

  return (
    <section className={styles.trending}>
      <h2 className={styles.sectionLabel}>
        급상승 영화 <span className={styles.sectionNote}>· 최근 사용자 평가 기준</span>
      </h2>

      <ul className={styles.trendingList}>
        {items.map((item) => (
          <li key={item.movie.movieId}>
            <TrendingRow
              item={item}
              ottProviders={trendingOttProviders(item.movie.movieId)}
              ottNames={ottNames}
              onSelect={() => onSelect(item.movie.title)}
            />
          </li>
        ))}
      </ul>

      <p className={styles.hint}>검색어를 입력하면 결과가 나와요</p>
    </section>
  );
}

export default function SearchPage() {
  const navigate = useNavigate();

  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState<SearchFilterState>(EMPTY_FILTER);
  const [filterOpen, setFilterOpen] = useState(false);
  const [options, setOptions] = useState<FilterOptions | null>(null);

  const [trending, setTrending] = useState<TrendingItem[] | null>(null);
  const [trendingFailed, setTrendingFailed] = useState(false);

  /** `null`은 아직 검색하지 않은 상태다. 빈 `items`는 검색했는데 결과가 없는 상태(4.4)다 */
  const [result, setResult] = useState<SearchResultState | null>(null);
  const [searchFailed, setSearchFailed] = useState(false);
  const [moreState, setMoreState] = useState<MoreState>('idle');
  /** 시트의 `결과 보기 · N편`에 쓰는 미리 센 개수 */
  const [previewCount, setPreviewCount] = useState<number | null>(null);

  const [ottNames, setOttNames] = useState<Record<string, string>>({});
  const [recent, setRecent] = useState<string[]>(() => [...getRecentSearchQueries()]);
  const inputRef = useRef<HTMLInputElement>(null);

  /**
   * 마지막으로 제출한 검색의 번호.
   *
   * 조건을 빠르게 바꾸면 먼저 보낸 요청이 나중에 도착할 수 있다. 번호가 다른 응답을
   * 버려야 **오래된 결과가 새 검색을 덮어쓰지 않는다.** 목업일 때는 같은 tick에
   * 끝나서 드러나지 않던 자리다.
   */
  const runSeq = useRef(0);

  /*
   * 들어오자마자 입력창에 포커스를 둔다(사용자 지시 2026-09-16, 업무 -461).
   *
   * 검색은 3.1 필름 헤더의 돋보기에서만 들어오는 하위 화면이다. 돋보기를 누른 사람은
   * 이미 검색어를 칠 참이므로 한 번 더 두드리게 하지 않는다.
   */
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    let active = true;

    getFilterOptions()
      .then((value) => {
        if (active) setOptions(value);
      })
      .catch(() => {
        // 필터 옵션이 없으면 시트를 열 수 없을 뿐 검색은 된다
      });

    getOtts()
      .then((otts) => {
        if (active) {
          setOttNames(Object.fromEntries(otts.map((ott) => [ott.code, ott.name])));
        }
      })
      .catch(() => {
        // 기준정보가 없으면 배지 이름에 코드가 읽힌다. 화면은 그대로 선다
      });

    getTrendingMovies(TRENDING_SIZE)
      .then((value) => {
        if (active) setTrending(value.items);
      })
      .catch(() => {
        if (active) setTrendingFailed(true);
      });

    return () => {
      active = false;
    };
  }, []);

  /** `그 이전` 덩어리의 시작값. 연도가 있는 영화가 없으면 계약도 `null`을 준다 */
  const yearMin = options?.yearRange.min ?? null;

  const run = useCallback(
    (nextKeyword: string, nextFilter: SearchFilterState) => {
      if (!nextKeyword.trim() && isEmptyFilter(nextFilter)) {
        // 검색어도 필터도 없으면 4.1로 돌아간다. 계약도 이 조합을 400으로 거절한다
        runSeq.current += 1;
        setResult(null);
        setSearchFailed(false);
        setMoreState('idle');

        return;
      }

      const query = toSearchQuery(nextKeyword, nextFilter, yearMin);
      const seq = (runSeq.current += 1);

      setMoreState('idle');

      searchMovies(query)
        .then((page) => {
          if (seq !== runSeq.current) return;

          setResult({ query, ...page });
          setSearchFailed(false);
        })
        .catch(() => {
          if (seq !== runSeq.current) return;

          setResult(null);
          setSearchFailed(true);
        });

      if (nextKeyword.trim()) {
        addRecentSearchQuery(nextKeyword);
        setRecent([...getRecentSearchQueries()]);
      }
    },
    [yearMin],
  );

  /**
   * 다음 장을 이어 받는다.
   *
   * 실패해도 이미 그린 목록은 두고 버튼만 `다시 시도`로 바꾼다. 여기서 목록을 비우면
   * 사용자가 보고 있던 결과가 통째로 사라진다.
   */
  const loadMore = () => {
    if (!result?.nextCursor || moreState === 'loading') return;

    const { query, nextCursor } = result;

    setMoreState('loading');

    searchMovies(query, nextCursor)
      .then((page) => {
        setMoreState('idle');
        setResult((prev) =>
          // 그 사이 조건이 바뀌었으면 이어 붙이지 않는다. 다른 조건의 결과가 섞인다
          prev && prev.query === query
            ? {
                ...prev,
                totalCount: page.totalCount,
                items: [...prev.items, ...page.items],
                nextCursor: page.nextCursor,
                hasNext: page.hasNext,
              }
            : prev,
        );
      })
      .catch(() => setMoreState('failed'));
  };

  /*
   * 시트가 열려 있는 동안 조건이 바뀌면 `결과 보기 · N편`을 다시 센다.
   *
   * 셀 수 없는 조합(검색어도 필터도 없음)에서도 `Promise.resolve(null)`을 태워
   * 설정을 콜백 안에서만 한다. 효과 본문에서 곧바로 setState하면 연쇄 렌더가 된다
   * (`react-hooks/set-state-in-effect`).
   */
  useEffect(() => {
    if (!filterOpen) return;

    let active = true;
    const countable = keyword.trim() || !isEmptyFilter(filter);
    const counted = countable
      ? countSearchResults(toSearchQuery(keyword, filter, yearMin)).catch(() => null)
      : Promise.resolve(null);

    void counted.then((count) => {
      if (active) setPreviewCount(count);
    });

    return () => {
      active = false;
    };
  }, [filterOpen, keyword, filter, yearMin]);

  const searched = result !== null || searchFailed;

  const reset = () => {
    setKeyword('');
    setFilter(EMPTY_FILTER);
    runSeq.current += 1;
    setResult(null);
    setSearchFailed(false);
    setMoreState('idle');
    inputRef.current?.focus();
  };

  const openDetail = (movieId: number) => void navigate(path.movieDetail(movieId));

  return (
    <>
      <div className={styles.page}>
        <form
          className={styles.searchBar}
          role="search"
          onSubmit={(event) => {
            event.preventDefault();
            run(keyword, filter);
          }}
        >
          <input
            ref={inputRef}
            type="search"
            className={styles.input}
            value={keyword}
            aria-label="영화 검색"
            placeholder="영화 제목, 감독, 배우 검색"
            onChange={(event) => setKeyword(event.target.value)}
          />

          {keyword ? (
            <button type="button" className={styles.cancel} onClick={reset}>
              취소
            </button>
          ) : null}

          <button
            type="button"
            className={styles.filterButton}
            disabled={!options}
            aria-pressed={!isEmptyFilter(filter)}
            onClick={() => setFilterOpen(true)}
          >
            필터
          </button>
        </form>

        {recent.length > 0 ? (
          <section className={styles.recent}>
            <div className={styles.recentHead}>
              <h2 className={styles.sectionLabel}>최근 검색어</h2>
              <button
                type="button"
                className={styles.clear}
                onClick={() => {
                  clearRecentSearchQueries();
                  setRecent([]);
                }}
              >
                전체 삭제
              </button>
            </div>

            <div className={styles.recentList}>
              {recent.map((query) => (
                <button
                  key={query}
                  type="button"
                  className={styles.recentItem}
                  onClick={() => {
                    setKeyword(query);
                    run(query, filter);
                  }}
                >
                  {query}
                </button>
              ))}
            </div>
          </section>
        ) : null}

        {searched ? (
          <SearchResults
            failed={searchFailed}
            result={result}
            moreState={moreState}
            onRetry={() => run(keyword, filter)}
            onReset={reset}
            onMore={loadMore}
            onSelect={openDetail}
            ottNames={ottNames}
          />
        ) : (
          <TrendingSection
            failed={trendingFailed}
            items={trending}
            onSelect={(title) => {
              setKeyword(title);
              run(title, filter);
            }}
            ottNames={ottNames}
          />
        )}
      </div>

      {filterOpen && options ? (
        <SearchFilterSheet
          options={options}
          value={filter}
          resultCount={previewCount}
          onChange={setFilter}
          onReset={() => setFilter(EMPTY_FILTER)}
          onClose={() => {
            setFilterOpen(false);
            run(keyword, filter);
          }}
        />
      ) : null}
    </>
  );
}
