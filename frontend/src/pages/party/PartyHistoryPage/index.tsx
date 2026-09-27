/*
 * 5.10 파티 추천 이력 (업무 S15P21E106-826)
 *
 * 이동표
 * 5.1 → 5.10 지난 파티 고르기
 * 5.8 → 5.10 지난 추천 보기
 * 5.10 ⇢ 2.4 추천받았던 영화 선택
 *
 * **와이어프레임에 없이 새로 만든 화면이다.** API-050은 종료 파티를 `status=CLOSED`로
 * 내려주고 계약이 그 목적을 `추천 이력 확인용`이라고 적어 두었는데, 파티가 추천받았던
 * 영화를 다시 볼 자리가 어디에도 없었다. 백엔드가 API-083을 만들며(업무 -815) 그 목적지가
 * 생겼다.
 *
 * **활성 파티도 같은 화면이다.** API-083은 파티 상태를 보지 않고 멤버십만 본다. 5.5가
 * 보여주는 것은 지금 묶음 하나뿐이라, 그 파티가 지금까지 본 영화 전부는 여기에만 있다.
 *
 * 세트와 묶음
 * - **세트가 절이다.** 응답이 세트 최신순이고 페이지도 세트 단위다. 절의 머리는 세트가
 *   만들어진 날(`generatedAt`)이다
 * - 한 세트를 여러 번에 걸쳐 받았으면(다시 받기, API-058 `refresh`) 항목의 `exposedAt`이
 *   갈린다. **묶음이 둘 이상인 세트에만** 묶음마다 노출 시각을 적는다 — 한 묶음뿐인
 *   보통의 세트에 시각 줄을 붙이면 절의 머리와 같은 말을 두 번 하게 된다
 * - 묶음에 `다시 받기` 같은 이름을 붙이지 않는 이유는 `utils/date.ts`의
 *   `formatTimeOfDay` 머리말에 있다
 *
 * 같은 영화는 서버가 **가장 최신 세트 1건**만 주므로 페이지를 이어 붙여도 두 번 나오지
 * 않는다(API-083 처리 규칙). 화면이 따로 거르지 않는다.
 *
 * 빈 이력과 실패를 가른다. 가를 수 있어야 사용자가 다음에 할 일을 안다.
 * - 이력이 비었다 → 활성 파티면 파티로 보내 추천을 받게 하고, 끝난 파티면 내 파티로
 * - `404` 없는 파티이거나 파티원이 아니다 → 내 파티로. **마지막 파티원이 나가서 끝난
 *   파티는 멤버십이 남지 않아 아무도 못 읽는다**(API-055 · API-083 권한)
 * - 그 밖의 실패 → 다시 시도. 원인은 `failureDescription`이 가른다
 *
 * 다음 장은 `더 보기`로 받는다. 세트마다 편 수가 달라 스크롤로 자동으로 이어 붙이면
 * 사용자가 얼마나 남았는지 가늠할 수 없고, 지난 추천은 끝까지 훑는 목록이 아니다.
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import PartyMixCard from '../../../components/PartyMixCard';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ChevronRightIcon } from '../../../components/Icon';
import { ApiError } from '../../../api/apiError';
import { failureDescription } from '../../../components/StatusView/failure';
import { getParty, getPartyRecommendationHistory } from '../../../api/parties';
import { formatDate, formatTimeOfDay } from '../../../utils/date';
import { path } from '../../../routes/paths';
import type { Party, PartyHistoryItem, PartyHistorySet } from '../../../types/party';
import styles from './PartyHistoryPage.module.css';

/** 목록의 포스터 크기. 5.5의 추천 줄과 같다 */
const POSTER_WIDTH = 56;
const POSTER_HEIGHT = 80;

/** 줄에 적는 장르 수. 셋을 넘으면 제목 아래가 두 줄이 된다 */
const GENRE_COUNT = 2;

/** 한 노출 묶음. 같은 `exposedAt`을 가진 항목들이다 */
type Batch = { exposedAt: string; items: PartyHistoryItem[] };

/**
 * 세트 안의 항목을 노출 시각으로 묶는다.
 *
 * 서버가 `exposedAt` 오름차순으로 주므로 이웃끼리만 비교하면 된다. 다시 정렬하지 않는
 * 것은 같은 시각 안의 순위(`position`)까지 서버가 정해 두었기 때문이다.
 */
function batchesOf(set: PartyHistorySet): Batch[] {
  const batches: Batch[] = [];

  for (const item of set.items) {
    const last = batches.at(-1);

    if (last && last.exposedAt === item.exposedAt) {
      last.items.push(item);
    } else {
      batches.push({ exposedAt: item.exposedAt, items: [item] });
    }
  }

  return batches;
}

/** 제목 아래 한 줄. 개봉 연도와 장르는 없을 수 있다(계약의 `null` · 빈 배열) */
function metaOf(movie: PartyHistoryItem['movie']): string {
  const parts = [
    movie.releaseYear === null ? null : String(movie.releaseYear),
    movie.genres.slice(0, GENRE_COUNT).join(' · ') || null,
  ];

  return parts.filter((part): part is string => part !== null).join(' · ');
}

export default function PartyHistoryPage() {
  const navigate = useNavigate();
  const { partyId } = useParams();
  const id = Number(partyId);

  const [sets, setSets] = useState<PartyHistorySet[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasNext, setHasNext] = useState(false);
  const [party, setParty] = useState<Party | null>(null);
  /** 첫 장을 못 받은 까닭. `null`이면 아직 실패하지 않았다 */
  const [failure, setFailure] = useState<{ notFound: boolean; description: string } | null>(null);
  /** 다음 장을 받는 중인지 · 받다 실패했는지. 첫 장과 달리 화면을 덮지 않는다 */
  const [more, setMore] = useState<'idle' | 'loading' | 'failed'>('idle');
  const [retryCount, setRetryCount] = useState(0);

  useEffect(() => {
    let active = true;

    getPartyRecommendationHistory(id)
      .then((page) => {
        if (!active) return;

        setSets(page.items);
        setCursor(page.nextCursor);
        setHasNext(page.hasNext && page.nextCursor !== null);
      })
      .catch((error: unknown) => {
        if (!active) return;

        setFailure({
          notFound: error instanceof ApiError && error.status === 404,
          description: failureDescription(error),
        });
      });

    return () => {
      active = false;
    };
  }, [id, retryCount]);

  useEffect(() => {
    let active = true;

    // 끝난 파티는 기본 목록에 없다. 이름을 못 받아도 이력은 그대로 보여준다
    getParty(id, { includeClosed: true })
      .then((value) => {
        if (active) setParty(value);
      })
      .catch(() => {
        // 그대로 둔다
      });

    return () => {
      active = false;
    };
  }, [id]);

  function loadMore() {
    if (cursor === null || more === 'loading') return;

    setMore('loading');

    getPartyRecommendationHistory(id, { cursor })
      .then((page) => {
        setSets((current) => [...(current ?? []), ...page.items]);
        setCursor(page.nextCursor);
        setHasNext(page.hasNext && page.nextCursor !== null);
        setMore('idle');
      })
      .catch(() => {
        setMore('failed');
      });
  }

  const header = (
    <ScreenHeader
      title="지난 추천"
      subtitle={party ? party.name : undefined}
      showBack
      fallbackTo={path.party()}
    />
  );

  if (failure) {
    return (
      <>
        {header}

        <div className={styles.page}>
          <StatusView
            title={failure.notFound ? '추천 이력을 볼 수 없어요' : '추천 이력을 불러오지 못했어요'}
            description={
              failure.notFound
                ? '없는 파티이거나 파티원이 아니에요. 마지막 파티원이 나가서 끝난 파티는 아무도 볼 수 없어요.'
                : failure.description
            }
          >
            {failure.notFound ? (
              <Button variant="text" onClick={() => void navigate(path.party())}>
                내 파티로 돌아가기
              </Button>
            ) : (
              <Button
                variant="text"
                onClick={() => {
                  // 실패 자리를 먼저 비운다. 효과 안에서 되돌리면 렌더가 한 번 더 돈다
                  setFailure(null);
                  setRetryCount((count) => count + 1);
                }}
              >
                다시 시도
              </Button>
            )}
          </StatusView>
        </div>
      </>
    );
  }

  // 받기 전에는 아무것도 그리지 않는다(`StatusView` 머리말, 팀 결정 2026-09-08)
  if (!sets) return null;

  if (sets.length === 0) {
    const closed = party !== null && party.status === 'CLOSED';

    return (
      <>
        {header}

        <div className={styles.page}>
          <StatusView
            variant="empty"
            title="아직 추천받은 영화가 없어요"
            description={
              closed
                ? '이 파티는 함께 볼 영화를 추천받기 전에 끝났어요.'
                : '파티에서 함께 볼 영화를 추천받으면 여기에 쌓여요.'
            }
          >
            {closed ? (
              <Button variant="text" onClick={() => void navigate(path.party())}>
                내 파티로 돌아가기
              </Button>
            ) : (
              <Button variant="text" onClick={() => void navigate(path.partyPopcorn(id))}>
                파티로 가기
              </Button>
            )}
          </StatusView>
        </div>
      </>
    );
  }

  return (
    <>
      {header}

      <div className={styles.page}>
        {sets.map((set) => {
          const batches = batchesOf(set);

          return (
            <section key={set.recommendationSetId} className={styles.set}>
              <h2 className={styles.setHead}>
                <span className={styles.setDate}>{formatDate(set.generatedAt)}</span>
                <span className={styles.setCount}>{set.items.length}편</span>
              </h2>

              {batches.map((batch) => (
                <div key={batch.exposedAt} className={styles.batch}>
                  {/* 한 묶음뿐인 세트는 절의 머리가 이미 언제인지 말한다 */}
                  {batches.length > 1 ? (
                    <p className={styles.batchTime}>{formatTimeOfDay(batch.exposedAt)}</p>
                  ) : null}

                  {(() => {
                    const mix = set.popcornSnapshots?.find(
                      (snapshot) => snapshot.exposedAt === batch.exposedAt,
                    );
                    return mix ? <PartyMixCard mix={mix} /> : null;
                  })()}
                  <ul className={styles.list}>
                    {batch.items.map((item) => (
                      <li key={item.recommendationItemId}>
                        <Link to={path.movieDetail(item.movie.movieId)} className={styles.row}>
                          <Poster
                            width={POSTER_WIDTH}
                            height={POSTER_HEIGHT}
                            title={item.movie.title}
                            seed={item.movie.movieId}
                            src={item.movie.posterUrl ?? undefined}
                          />

                          <span className={styles.body}>
                            <span className={styles.title}>{item.movie.title}</span>
                            <span className={styles.meta}>{metaOf(item.movie)}</span>
                          </span>

                          <span className={styles.chevron} aria-hidden="true">
                            <ChevronRightIcon />
                          </span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </section>
          );
        })}

        {hasNext ? (
          <div className={styles.actions}>
            <Button variant="text" disabled={more === 'loading'} onClick={loadMore}>
              {more === 'failed' ? '다시 불러오기' : '더 보기'}
            </Button>
          </div>
        ) : null}
      </div>
    </>
  );
}
