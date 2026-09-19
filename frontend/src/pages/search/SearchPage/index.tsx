/*
 * 4.1 검색 홈 · 4.2 영화 검색 결과 · 4.4 검색 결과 없음
 *
 * 세 프레임이 한 화면의 세 상태다. 검색어도 필터도 없으면 4.1, 결과가 있으면 4.2, 조건에
 * 맞는 것이 없으면 4.4다. 4.3 필터는 이 화면 위에 겹치는 시트이므로 `SearchFilterSheet`로
 * 뒀다.
 *
 * 이동표
 * 4.1 → 4.2 검색어 입력 · 4.1 ⇢ 4.3 필터 버튼
 * 4.2 → 2.4 검색 결과 영화 선택 · 4.2 ⇢ 4.3 필터 버튼 · 4.2 ⇢ 4.4 결과 없음
 * 4.3 → 4.2 결과 보기 · 4.4 ⇢ 4.1 검색어 지우기 또는 다시 검색
 *
 * **업무 -612에서 다시 짰다.** 바뀐 것은 다음과 같다.
 *
 * - **급상승(API-064)은 없다.** 백엔드에 없는 계약을 목업으로 보여 주던 자리였다. 검색 전
 *   화면은 최근 검색어뿐이고, 그것도 없으면 짧은 안내 한 줄이다.
 * - **타이핑하면 검색한다.** 공백을 뺀 한 글자부터다. 연속 입력은 `DEBOUNCE_MS`로 묶고,
 *   한글 조합 중(`compositionstart` ~ `compositionend`)에는 보내지 않는다 — 조합 중의
 *   자모(`ㄷ` → `다` → `달`)마다 요청이 나가면 결과가 세 번 깜빡인다. 키보드의 검색
 *   버튼(제출)은 기다리지 않고 바로 보낸다.
 * - **오래된 응답은 버린다.** 조건을 빠르게 바꾸면 먼저 보낸 요청이 나중에 도착할 수
 *   있다. 요청마다 번호를 매기고 마지막 번호가 아닌 응답은 무시한다. 계약 호출에 취소
 *   수단(`AbortSignal`)이 없어 무시하는 쪽으로 했다.
 * - **결과는 3열 포스터 격자다.** 칸에는 포스터와 제목만 있다(`SearchResultGrid`).
 * - **정렬은 결과 머리줄에서 고른다.** 시트에서는 뺐다.
 * - **다음 장은 스크롤로 이어 받는다.** 마지막 칸 아래의 감시 요소가 보이면 다음 장을
 *   부른다. `IntersectionObserver`가 없는 환경과 실패 뒤에는 버튼으로 잇는다.
 *
 * **검색 조건은 주소에 남는다**(업무 -634). 4.2 → 2.4 → 뒤로 가기가 4.1로 떨어지지 않게,
 * 검색어 · 필터 · 정렬을 `?q=` 등으로 적는다. 타이핑마다 기록이 쌓이지 않도록 늘 **바꿔 쓴다**
 * (`replace`). 상세로 가기 직전의 결과와 스크롤은 `searchSnapshot`에 적어 두었다가, 뒤로 와서
 * 주소가 같으면 다시 부르지 않고 그대로 되살린다.
 *
 * **최근 검색어는 제출했거나 결과를 눌렀을 때만 남긴다.** 타이핑마다 남기면 `ㄷ` · `달` ·
 * `달의`가 전부 쌓인다. 같은 말은 다시 저장하지 않고 맨 앞으로 옮긴다(`sessionStore`).
 */
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { ChevronDownIcon, ClearIcon, FilterIcon, SearchIcon } from '../../../components/Icon';
import { countSearchResults, getFilterOptions, searchMovies } from '../../../api/search';
import {
  addRecentSearchQuery,
  clearRecentSearchQueries,
  getRecentSearchQueries,
} from '../../../mocks/sessionStore';
import SearchFilterSheet from './SearchFilterSheet';
import SearchResultGrid, { SearchResultSkeleton } from './SearchResultGrid';
import {
  EMPTY_FILTER,
  fromSearchParams,
  isEmptyFilter,
  SORT_OPTIONS,
  toSearchParams,
  toSearchQuery,
} from './searchFilter';
import { saveSnapshot, takeSnapshot } from './searchSnapshot';
import type { SearchFilterState } from './searchFilter';
import type {
  FilterOptions,
  MovieSearchItem,
  MovieSearchQuery,
  MovieSearchSort,
} from '../../../types/search';
import styles from './SearchPage.module.css';

/** 연속 입력을 묶는 시간. 요구는 250~300ms다 */
const DEBOUNCE_MS = 280;
/** 다음 장을 이어 받는 동안 격자 아래에 그리는 스켈레톤 칸 수. 한 행이다 */
const MORE_SKELETON_CELLS = 3;

/** 스크롤로 다음 장을 잇는 수단이 있는 환경인지. 없으면 버튼으로 잇는다 */
const SUPPORTS_OBSERVER = typeof IntersectionObserver !== 'undefined';

/** `더 보기` 한 번의 상태. 실패해도 이미 받은 목록은 남는다 */
type MoreState = 'idle' | 'loading' | 'failed';

/**
 * 검색의 상태.
 *
 * `idle`은 아직 검색하지 않은 4.1이다. `ready`에 `items`가 비어 있으면 4.4다. 둘을
 * 한 값으로 두면 검색하지 않은 화면과 결과가 없는 화면이 뒤바뀐다.
 *
 * `query`를 함께 들고 다니는 것은 **커서가 조건에 묶여 있기 때문**이다(API-028 처리
 * 규칙). 다음 장은 이 조건 그대로 불러야 하고, 조건이 바뀌면 커서를 버리고 첫 장부터
 * 다시 받는다.
 */
type SearchState =
  | { status: 'idle' }
  | { status: 'loading'; query: MovieSearchQuery }
  | { status: 'failed'; query: MovieSearchQuery }
  | {
      status: 'ready';
      query: MovieSearchQuery;
      /** cursor · size를 적용하기 전 전체 건수. 받아 온 `items`의 길이가 아니다 */
      totalCount: number;
      items: MovieSearchItem[];
      nextCursor: string | null;
      hasNext: boolean;
      more: MoreState;
    };

const IDLE: SearchState = { status: 'idle' };

/** 4.4 결과 없음 */
function EmptyResults({
  keyword,
  filtered,
  onResetFilter,
}: {
  keyword: string | undefined;
  /** 필터가 걸려 있는지. 걸려 있으면 그것부터 풀어 보게 한다 */
  filtered: boolean;
  onResetFilter: () => void;
}) {
  return (
    <StatusView
      variant="empty"
      title={keyword ? `‘${keyword}’에 대한 결과가 없어요` : '조건에 맞는 영화가 없어요'}
      description="맞춤법을 확인하거나 다른 검색어를 입력해 보세요"
    >
      {filtered ? (
        <Button variant="text" onClick={onResetFilter}>
          필터 초기화
        </Button>
      ) : null}
    </StatusView>
  );
}

/**
 * 처음 그릴 상태. 주소에 조건이 있으면 그 조건으로 시작한다.
 *
 * - 상세에서 뒤로 왔고 적어 둔 결과가 있으면 그대로 되살린다
 * - 조건만 있으면(주소로 바로 들어옴 · 적어 둔 것이 없음) 첫 장을 받는 중으로 시작한다
 * - 조건이 없으면 4.1이다
 */
function initialState(params: URLSearchParams) {
  const { keyword, filter } = fromSearchParams(params);
  const conditioned = Boolean(keyword.trim()) || !isEmptyFilter(filter);
  const restored = conditioned ? takeSnapshot<SearchState>(params.toString()) : null;

  if (restored && restored.state.status === 'ready') {
    return {
      keyword,
      filter,
      search: { ...restored.state, more: 'idle' as const },
      scrollTop: restored.scrollTop,
      fetchOnMount: false,
    };
  }

  return {
    keyword,
    filter,
    search: conditioned
      ? ({ status: 'loading', query: toSearchQuery(keyword, filter, null) } as SearchState)
      : IDLE,
    scrollTop: 0,
    fetchOnMount: conditioned,
  };
}

export default function SearchPage() {
  const [params, setParams] = useSearchParams();
  // 처음 한 번만 계산한다. 이후의 주소는 이 화면이 바꿔 쓰는 결과일 뿐이다
  const [initial] = useState(() => initialState(params));

  const [keyword, setKeyword] = useState(initial.keyword);
  const [filter, setFilter] = useState<SearchFilterState>(initial.filter);
  const [filterOpen, setFilterOpen] = useState(false);
  const [options, setOptions] = useState<FilterOptions | null>(null);
  /** 필터 옵션 요청이 끝났는지(성공 · 실패 모두). `그 이전` 첫 검색이 이것을 기다린다 */
  const [optionsSettled, setOptionsSettled] = useState(false);
  const [search, setSearch] = useState<SearchState>(initial.search);
  /** 시트의 `결과 보기 · N편`에 쓰는 미리 센 개수 */
  const [previewCount, setPreviewCount] = useState<number | null>(null);
  const [recent, setRecent] = useState<string[]>(() => [...getRecentSearchQueries()]);

  const inputRef = useRef<HTMLInputElement>(null);
  const filterButtonRef = useRef<HTMLButtonElement>(null);
  const filterSheetId = useId();
  const filterStateId = useId();
  const pageRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  /** 한글 조합 중인지. 상태가 아니라 ref인 것은 렌더와 무관한 입력 단계이기 때문이다 */
  const composing = useRef(false);
  /** 디바운스 타이머 */
  const timer = useRef<number | null>(null);
  /** 마지막으로 보낸 검색의 번호. 다른 번호의 응답은 버린다 */
  const runSeq = useRef(0);

  /*
   * 들어오자마자 입력창에 포커스를 둔다(사용자 지시 2026-09-16, 업무 -461).
   *
   * 검색은 3.1 필름 헤더의 돋보기에서만 들어오는 하위 화면이다. 돋보기를 누른 사람은
   * 이미 검색어를 칠 참이므로 한 번 더 두드리게 하지 않는다.
   */
  useEffect(() => {
    // 뒤로 와서 결과를 되살렸으면 보던 자리로 스크롤한다. 그때는 입력창에 초점을 두지 않는다 —
    // 초점이 가면 화면이 입력창까지 끌려 올라간다
    if (initial.scrollTop > 0 && pageRef.current) {
      pageRef.current.scrollTop = initial.scrollTop;

      return;
    }

    if (initial.search.status === 'idle') inputRef.current?.focus();
  }, [initial]);

  useEffect(() => {
    let active = true;

    getFilterOptions()
      .then((value) => {
        if (!active) return;

        setOptions(value);
        setOptionsSettled(true);
      })
      .catch(() => {
        // 필터 옵션이 없으면 시트를 열 수 없을 뿐 검색은 된다
        if (active) setOptionsSettled(true);
      });

    return () => {
      active = false;
    };
  }, []);

  // 화면을 떠날 때 기다리던 검색은 보내지 않는다
  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    },
    [],
  );

  /** `그 이전` 덩어리의 시작값. 연도가 있는 영화가 없으면 계약도 `null`을 준다 */
  const yearMin = options?.yearRange.min ?? null;

  const cancelPending = () => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  };

  /** 첫 장을 받아 상태에 넣는다. 그 사이 새 검색이 나갔으면 이 응답은 버린다 */
  const fetchFirstPage = useCallback((query: MovieSearchQuery, seq: number) => {
    searchMovies(query)
      .then((page) => {
        if (seq !== runSeq.current) return;

        setSearch({ status: 'ready', query, ...page, more: 'idle' });
      })
      .catch(() => {
        if (seq !== runSeq.current) return;

        setSearch({ status: 'failed', query });
      });
  }, []);

  /** 주소의 조건으로 첫 장을 이미 보냈는지. 한 번만 보낸다 */
  const initialFetched = useRef(false);

  /*
   * 주소로 들어왔고 되살릴 결과가 없으면 주소의 조건으로 첫 장을 받는다. 상태는 이미
   * `받는 중`으로 시작했으므로 여기서는 요청만 보낸다.
   *
   * `그 이전` 연도는 시작 연도가 필터 옵션(API-029 `yearRange.min`)에 있어 **옵션을 받은 뒤에**
   * 보낸다. 먼저 보내면 연도 조건이 빠진 채 검색된다(!405 리뷰). 옵션을 못 받으면 계약대로
   * 시작 없이 끝만 보낸다.
   */
  useEffect(() => {
    if (initialFetched.current || !initial.fetchOnMount) return;
    if (initial.filter.year === 'BEFORE' && !optionsSettled) return;

    initialFetched.current = true;

    // 기다리는 사이 사용자가 새로 검색했으면 주소의 첫 조건은 이미 지난 것이다
    if (runSeq.current > 0) return;

    fetchFirstPage(toSearchQuery(initial.keyword, initial.filter, yearMin), (runSeq.current += 1));
  }, [initial, optionsSettled, yearMin, fetchFirstPage]);

  /** 조건으로 첫 장을 받는다. 검색어도 필터도 없으면 4.1로 돌아간다 */
  const run = useCallback(
    (nextKeyword: string, nextFilter: SearchFilterState) => {
      const seq = (runSeq.current += 1);

      // 조건을 주소에 적는다. 늘 바꿔 쓰므로 뒤로 가기 기록이 쌓이지 않는다
      setParams(toSearchParams(nextKeyword, nextFilter), { replace: true });

      if (!nextKeyword.trim() && isEmptyFilter(nextFilter)) {
        // 계약도 이 조합을 400으로 거절한다
        setSearch(IDLE);

        return;
      }

      const query = toSearchQuery(nextKeyword, nextFilter, yearMin);

      setSearch({ status: 'loading', query });
      fetchFirstPage(query, seq);
    },
    [yearMin, setParams, fetchFirstPage],
  );

  /** 잠시 기다렸다가 검색한다. 그 사이 또 입력이 오면 처음부터 다시 기다린다 */
  const schedule = (nextKeyword: string) => {
    cancelPending();
    timer.current = window.setTimeout(() => {
      timer.current = null;
      run(nextKeyword, filter);
    }, DEBOUNCE_MS);
  };

  /** 제출 · 결과 선택처럼 검색을 확정한 때만 최근 검색어로 남긴다 */
  const remember = (value: string) => {
    if (!value.trim()) return;

    addRecentSearchQuery(value);
    setRecent([...getRecentSearchQueries()]);
  };

  /** 기다리지 않고 바로 검색한다. 제출 · 최근 검색어 · 필터 · 정렬이 쓴다 */
  const runNow = (nextKeyword: string, nextFilter: SearchFilterState) => {
    cancelPending();
    run(nextKeyword, nextFilter);
  };

  /**
   * 다음 장을 이어 받는다.
   *
   * 실패해도 이미 그린 목록은 두고 이음새만 `다시 시도`로 바꾼다. 여기서 목록을 비우면
   * 사용자가 보고 있던 결과가 통째로 사라진다.
   */
  const loadMore = useCallback(() => {
    if (search.status !== 'ready' || !search.nextCursor || search.more === 'loading') return;

    const { query, nextCursor } = search;

    setSearch({ ...search, more: 'loading' });

    searchMovies(query, nextCursor)
      .then((page) => {
        setSearch((prev) =>
          // 그 사이 조건이 바뀌었으면 이어 붙이지 않는다. 다른 조건의 결과가 섞인다
          prev.status === 'ready' && prev.query === query
            ? {
                ...prev,
                totalCount: page.totalCount,
                items: [...prev.items, ...page.items],
                nextCursor: page.nextCursor,
                hasNext: page.hasNext,
                more: 'idle',
              }
            : prev,
        );
      })
      .catch(() => {
        setSearch((prev) =>
          prev.status === 'ready' && prev.query === query ? { ...prev, more: 'failed' } : prev,
        );
      });
  }, [search]);

  /*
   * 격자 끝의 감시 요소가 보이면 다음 장을 부른다.
   *
   * 실패한 뒤에는 자동으로 다시 부르지 않는다 — 네트워크가 끊긴 채 스크롤 끝에 머물면
   * 요청이 끝없이 반복된다. 그때는 버튼이 잇는다.
   */
  const canAutoLoad =
    SUPPORTS_OBSERVER && search.status === 'ready' && search.hasNext && search.more === 'idle';

  useEffect(() => {
    const node = sentinelRef.current;

    if (!canAutoLoad || !node) return;

    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) loadMore();
    });

    observer.observe(node);

    return () => observer.disconnect();
  }, [canAutoLoad, loadMore]);

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

  /**
   * X 버튼. 검색어를 비우고 다시 칠 수 있게 초점을 돌려준다.
   *
   * 필터가 남아 있으면 **필터만으로 다시 찾는다.** 화면만 4.1로 돌리면 주소에는 필터가 남아
   * 새로고침할 때 방금 지운 결과가 되살아났다(!405 리뷰). 필터도 없으면 4.1이다.
   */
  const clear = () => {
    setKeyword('');
    runNow('', filter);
    inputRef.current?.focus();
  };

  /** 상세로 가기 직전. 받아 둔 결과와 스크롤 위치를 적어 두어 뒤로 오면 되살린다 */
  const leaveToDetail = () => {
    remember(keyword);

    if (search.status === 'ready') {
      saveSnapshot(params.toString(), search, pageRef.current?.scrollTop ?? 0);
    }
  };

  const changeFilter = (next: SearchFilterState) => {
    setFilter(next);
    runNow(keyword, next);
  };

  const changeSort = (sort: MovieSearchSort) => changeFilter({ ...filter, sort });

  const filtered = !isEmptyFilter(filter);

  return (
    <>
      <div className={styles.page} ref={pageRef}>
        <ScreenHeader title="검색" />

        <form
          className={styles.searchBar}
          role="search"
          onSubmit={(event) => {
            event.preventDefault();
            remember(keyword);
            runNow(keyword, filter);
          }}
        >
          <div className={styles.field}>
            <span className={styles.fieldIcon}>
              <SearchIcon />
            </span>

            <input
              ref={inputRef}
              type="search"
              enterKeyHint="search"
              autoComplete="off"
              className={styles.input}
              value={keyword}
              aria-label="영화 검색"
              placeholder="영화 제목, 감독, 배우 검색"
              onCompositionStart={() => {
                composing.current = true;
              }}
              onCompositionEnd={(event) => {
                composing.current = false;
                schedule(event.currentTarget.value);
              }}
              onChange={(event) => {
                setKeyword(event.target.value);
                // 조합 중의 자모는 보내지 않는다. 조합이 끝나면 `onCompositionEnd`가 잇는다
                if (!composing.current) schedule(event.target.value);
              }}
            />

            {keyword ? (
              <button
                type="button"
                className={styles.clear}
                aria-label="검색어 지우기"
                onClick={clear}
              >
                <ClearIcon />
              </button>
            ) : null}
          </div>

          {/*
           * 시트를 여는 버튼이다(업무 -674). 한때 `aria-pressed`만 있어 보조기술이 켜고 끄는
           * 토글로 읽었다. 지금은 대화상자를 연다는 것 · 열렸는지 · 어느 시트인지를 알리고,
           * 필터가 걸려 있는지는 설명(`aria-describedby`)으로 따로 읽힌다
           */}
          <button
            ref={filterButtonRef}
            type="button"
            className={styles.filterButton}
            aria-label="필터"
            disabled={!options}
            aria-haspopup="dialog"
            aria-expanded={filterOpen}
            aria-controls={filterOpen ? filterSheetId : undefined}
            aria-describedby={filtered ? filterStateId : undefined}
            data-filtered={filtered || undefined}
            onClick={() => setFilterOpen(true)}
          >
            <FilterIcon />
          </button>
          {filtered ? (
            <span id={filterStateId} hidden>
              필터 적용 중
            </span>
          ) : null}
        </form>

        <p className={styles.scope}>제목 · 감독 · 배우 통합 검색</p>

        {search.status === 'idle' ? (
          recent.length > 0 ? (
            <section className={styles.recent}>
              <div className={styles.recentHead}>
                <h2 className={styles.sectionLabel}>최근 검색어</h2>
                <button
                  type="button"
                  className={styles.recentClear}
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
                      remember(query);
                      runNow(query, filter);
                    }}
                  >
                    {query}
                  </button>
                ))}
              </div>
            </section>
          ) : (
            <p className={styles.guide}>영화 제목, 감독, 배우 이름으로 검색해 보세요</p>
          )
        ) : null}

        {search.status === 'loading' ? <SearchResultSkeleton /> : null}

        {search.status === 'failed' ? (
          <StatusView
            title="검색 결과를 불러오지 못했어요"
            description="잠시 후 다시 시도해 주세요."
          >
            <Button variant="text" onClick={() => runNow(keyword, filter)}>
              다시 시도
            </Button>
          </StatusView>
        ) : null}

        {search.status === 'ready' && search.items.length === 0 ? (
          <EmptyResults
            keyword={search.query.q}
            filtered={filtered}
            onResetFilter={() => changeFilter(EMPTY_FILTER)}
          />
        ) : null}

        {search.status === 'ready' && search.items.length > 0 ? (
          <section className={styles.results}>
            <div className={styles.resultsHead}>
              <h2 className={styles.resultsTitle}>
                검색 결과 <span className={styles.count}>{search.totalCount}</span>
              </h2>

              {/*
               * 정렬은 브라우저의 `select`다. 시트를 하나 더 만들지 않고도 키보드 · 보조기술이
               * 그대로 다룬다. 겉모양만 버튼처럼 두고 화살표는 따로 그린다.
               */}
              <label className={styles.sort}>
                <VisuallyHidden>정렬</VisuallyHidden>
                <select
                  className={styles.sortSelect}
                  value={filter.sort}
                  onChange={(event) => changeSort(event.target.value as MovieSearchSort)}
                >
                  {SORT_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <span className={styles.sortChevron}>
                  <ChevronDownIcon />
                </span>
              </label>
            </div>

            <SearchResultGrid items={search.items} onSelect={leaveToDetail} />

            {search.more === 'loading' ? (
              <SearchResultSkeleton cells={MORE_SKELETON_CELLS} />
            ) : null}

            {search.hasNext &&
            search.more !== 'loading' &&
            (!SUPPORTS_OBSERVER || search.more === 'failed') ? (
              <div className={styles.more}>
                <Button variant="secondary" onClick={loadMore}>
                  {search.more === 'failed' ? '다시 시도' : '더 보기'}
                </Button>

                {search.more === 'failed' ? (
                  <p className={styles.moreError}>다음 결과를 불러오지 못했어요.</p>
                ) : null}
              </div>
            ) : null}

            {/* 스크롤 끝 감시 요소. 보이면 다음 장을 부른다 */}
            <div ref={sentinelRef} className={styles.sentinel} aria-hidden="true" />
          </section>
        ) : null}
      </div>

      {filterOpen && options ? (
        <SearchFilterSheet
          id={filterSheetId}
          options={options}
          value={filter}
          resultCount={previewCount}
          onChange={setFilter}
          onReset={() => setFilter(EMPTY_FILTER)}
          onClose={() => {
            setFilterOpen(false);
            runNow(keyword, filter);
            // 닫기 · Escape · 결과 보기 어느 쪽이든 초점을 연 자리로 돌려놓는다(업무 -674)
            filterButtonRef.current?.focus();
          }}
        />
      ) : null}
    </>
  );
}
