/*
 * 2.2 추천 영화의 우측 보조 패널 (업무 S15P21E106-783).
 *
 * 2026-09-21 시안이 이 자리에 카드 둘을 둔다 — **추천 기준 · 최근 본 영화**다.
 *
 * `추천 기준`은 받은 추천 카드들이 들고 온 근거다(API-045 `reason.factors[].label`). 카드마다
 * 이유 한 줄이 이미 있지만, 시안은 오늘의 추천 전체가 무엇을 보고 만들어졌는지를 한자리에
 * 모아 보여준다. **목록을 지어내지 않는다** — 서버가 준 근거가 없으면 이 카드를 그리지 않는다.
 *
 * `최근 본 영화`는 감상 기록(API-034)이다. **시안의 시청 시간과 진행 막대는 그리지 않는다**
 * (사용자 결정 2026-09-22). 이 앱은 재생을 하지 않아 얼마나 봤는지를 알 수 없고, 계약이 주는
 * 것은 어떤 영화를 언제 봤는지까지다. 개봉연도 · 장르도 이 목록에는 오지 않아(계약의 `movie`는
 * 번호 · 제목 · 포스터뿐) 제목과 날짜만 적는다.
 *
 * 이 패널은 1280px 이상에서만 그려진다(`components/DesktopAside`). 감상 기록도 그때만 부른다.
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import AsidePanel from '../../../../components/AsidePanel';
import Poster from '../../../../components/Poster';
import { getWatchRecords } from '../../../../api/watch';
import { formatMonthDay } from '../../../../utils/date';
import { path } from '../../../../routes/paths';
import styles from './RecommendationsAside.module.css';
import type { RecommendationItem } from '../../../../types/recommendation';
import type { WatchRecordListItem } from '../../../../types/watch';

/** 최근 본 영화에 올리는 수. 시안이 셋을 보여준다 */
const RECENT_COUNT = 3;

/** 추천 기준 칩의 최대 수. 그보다 많으면 카드가 한 화면을 넘긴다 */
const FACTOR_LIMIT = 8;

type Props = {
  /** 지금 화면에 있는 추천 카드. 근거를 여기서 모은다 */
  items: RecommendationItem[];
};

export default function RecommendationsAside({ items }: Props) {
  const [recent, setRecent] = useState<WatchRecordListItem[]>([]);

  useEffect(() => {
    let active = true;

    // 보조 카드다. 받지 못하면 그 카드만 비운다
    getWatchRecords({ status: ['RATED', 'WATCHED_UNRATED'], size: RECENT_COUNT })
      .then((list) => {
        if (active) setRecent(list.items);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  // 같은 근거가 여러 카드에 걸리므로 한 번씩만 남긴다. 순서는 카드 순서 그대로다
  const factors = [
    ...new Set(items.flatMap((item) => item.reason.factors.map((factor) => factor.label))),
  ].slice(0, FACTOR_LIMIT);

  return (
    <>
      {factors.length > 0 ? (
        <AsidePanel title="추천 기준">
          <ul className={styles.factors}>
            {factors.map((label) => (
              <li key={label} className={styles.factor}>
                #{label}
              </li>
            ))}
          </ul>

          <p className={styles.factorNote}>지금까지의 감상 기록과 평가를 바탕으로 추천했어요</p>
        </AsidePanel>
      ) : null}

      {recent.length > 0 ? (
        <AsidePanel title="최근 본 영화" to={path.my()} linkLabel="내 감상 기록 보기">
          <ul className={styles.recentList}>
            {recent.map((record) => (
              <li key={record.watchRecordId}>
                <Link to={path.movieDetail(record.movie.movieId)} className={styles.recent}>
                  <Poster
                    width={44}
                    height={66}
                    radius={6}
                    src={record.movie.posterUrl ?? undefined}
                    title=""
                    seed={record.movie.movieId}
                  />

                  <span className={styles.recentInfo}>
                    <span className={styles.recentTitle}>{record.movie.title}</span>

                    {/*
                      본 날짜. 감상 확인을 한 기록은 그 시각이고, 없으면 링크를 누른 시각이다
                      (API-034 — 평가가 먼저 만든 기록은 클릭 시각이 없다)
                    */}
                    {(record.watchedAt ?? record.clickedAt) ? (
                      <span className={styles.recentMeta}>
                        {formatMonthDay((record.watchedAt ?? record.clickedAt) as string)} 감상
                      </span>
                    ) : null}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </AsidePanel>
      ) : null}
    </>
  );
}
