/*
 * 3.1 취향 필름의 우측 보조 패널 (업무 S15P21E106-783).
 *
 * 2026-09-21 시안이 이 자리에 카드 셋을 둔다 — **나의 취향 팝콘 · 취향 리포트 · 이어보기**다.
 * 앞의 둘은 모바일 본문에 줄로 있던 것을 카드로 옮긴 것이고(넓은 폭에서는 본문에서 뺀다,
 * `FilmPage`), 셋째만 새로 만든다.
 *
 * **`이어보기`는 `보셨나요?`가 됐다**(사용자 결정 2026-09-22). 시안은 재생 위치와 남은 시간을
 * 보여주지만 이 앱은 재생을 하지 않는다 — 계약이 저장하는 것은 OTT 링크를 눌렀다는 사실과
 * 그 뒤의 감상 확인뿐이다(API-033 · API-034 · API-035). 없는 수치를 지어내지 않고, 대신 같은
 * 자리에서 **링크를 눌러 놓고 아직 봤는지 답하지 않은 영화**를 물어본다. 답할 것이 없으면
 * 카드를 그리지 않는다.
 *
 * 이 패널은 1280px 이상에서만 그려진다(`components/DesktopAside`). 감상 확인 기록도 그때만
 * 부른다 — 이 컴포넌트가 그려지지 않으면 안의 `useEffect`도 돌지 않는다.
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import AsidePanel from '../../../../components/AsidePanel';
import Badge from '../../../../components/Badge';
import FilmReel from '../../../../components/FilmReel';
import Poster from '../../../../components/Poster';
import { kernelSprite } from '../../../../components/PopcornBucket/sprites';
import { getWatchRecords } from '../../../../api/watch';
import { formatMonthDay } from '../../../../utils/date';
import { path } from '../../../../routes/paths';
import styles from './FilmAside.module.css';
import type { FrameSummary, PopcornBucket } from '../../../../types/taste';
import type { WatchRecordListItem } from '../../../../types/watch';

/** 리포트 카드 안의 작은 필름. 시안이 포스터 넷을 보여준다 */
const REPORT_FRAME_COUNT = 4;
const REPORT_FRAME_WIDTH = 60;
const REPORT_FRAME_HEIGHT = 90;

type Props = {
  /** API-041 팝콘 버킷. 받지 못했으면 팝콘 카드를 그리지 않는다 */
  bucket: PopcornBucket | null;
  /** API-042 프레임 목록. 리포트 카드의 작은 필름에 앞쪽 넷을 쓴다 */
  frames: FrameSummary[];
  /** 아직 받지 않은 새 리포트. 있으면 리포트 카드가 발급(3.3)으로 간다 */
  newReportId: number | null;
};

export default function FilmAside({ bucket, frames, newReportId }: Props) {
  /** 링크를 눌러 놓고 아직 봤는지 답하지 않은 기록 하나. 없으면 `null` */
  const [pending, setPending] = useState<WatchRecordListItem | null>(null);

  useEffect(() => {
    let active = true;

    // 보조 카드다. 받지 못하면 그 카드만 비운다
    getWatchRecords({ confirmDue: true, size: 1 })
      .then((list) => {
        if (active) setPending(list.items[0] ?? null);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  // 많이 나온 맛부터. 0알인 맛은 아직 발견하지 않은 맛이라 뺀다
  const flavors = bucket
    ? [...bucket.flavors]
        .filter((flavor) => flavor.popcornCount > 0)
        .sort((a, b) => b.popcornCount - a.popcornCount)
    : [];
  const reportFrames = frames.slice(0, REPORT_FRAME_COUNT);

  return (
    <>
      {flavors.length > 0 && bucket ? (
        <AsidePanel
          title="나의 취향 팝콘"
          to={path.popcornBucket()}
          linkLabel={`나의 취향 팝콘 ${bucket.totalCount}알 자세히 보기`}
          tone="dark"
        >
          <table className={styles.flavorTable}>
            <thead>
              <tr>
                <th scope="col" className={styles.flavorNameHead}>
                  맛
                </th>
                <th scope="col">감상량</th>
                <th scope="col">선호도</th>
              </tr>
            </thead>

            <tbody>
              {flavors.map((flavor) => (
                <tr key={flavor.flavorCode}>
                  <th scope="row" className={styles.flavorName}>
                    <img className={styles.kernel} src={kernelSprite(flavor.flavorCode)} alt="" />
                    {flavor.displayName}
                  </th>

                  {/*
                   * 감상량은 팝콘 알 수의 비율, 선호도는 그 맛 영화들의 평균 별점이다(FR-06,
                   * 3.2가 같은 구분을 쓴다). **선호도는 별점 그대로 적는다**(사용자 결정
                   * 2026-09-22) — 시안은 백분율로 그리지만 계약이 주는 값은 5점 만점 별점이고,
                   * 백분율로 환산하면 같은 값이 화면마다 다른 수로 보인다.
                   */}
                  <td className={styles.flavorShare}>
                    {Math.round((flavor.popcornCount / bucket.totalCount) * 100)}%
                  </td>

                  <td className={styles.flavorScore}>
                    {flavor.averageScore === null ? '—' : `★ ${flavor.averageScore.toFixed(1)}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsidePanel>
      ) : null}

      <AsidePanel
        title="취향 리포트"
        to={newReportId !== null ? path.reportIssued(newReportId) : path.tasteReports()}
        linkLabel={newReportId !== null ? '새 취향 리포트 받기' : '취향 리포트 보기'}
        mark={newReportId !== null ? <Badge tone="accent">새 리포트</Badge> : undefined}
      >
        {reportFrames.length > 0 ? (
          <div className={styles.reportReel}>
            <FilmReel
              tone="plain"
              frameWidth={REPORT_FRAME_WIDTH}
              frameHeight={REPORT_FRAME_HEIGHT}
              label="리포트에 담긴 영화"
            >
              {reportFrames.map((frame) => (
                <Poster
                  key={frame.frameId}
                  width={REPORT_FRAME_WIDTH}
                  height={REPORT_FRAME_HEIGHT}
                  radius={0}
                  src={frame.movie.posterUrl ?? undefined}
                  title=""
                  seed={frame.movie.movieId}
                />
              ))}
            </FilmReel>
          </div>
        ) : null}

        <p className={styles.reportText}>
          {newReportId !== null
            ? '나의 영화 취향을 분석한 리포트가 도착했어요'
            : '지금까지 본 영화로 만든 나만의 취향 분석'}
        </p>
      </AsidePanel>

      {pending ? (
        <AsidePanel title="보셨나요?">
          <div className={styles.pending}>
            <Poster
              width={48}
              height={72}
              radius={6}
              src={pending.movie.posterUrl ?? undefined}
              title=""
              seed={pending.movie.movieId}
            />

            <div className={styles.pendingInfo}>
              <p className={styles.pendingTitle}>{pending.movie.title}</p>
              <p className={styles.pendingMeta}>
                {pending.clickedAt
                  ? `${formatMonthDay(pending.clickedAt)}에 보러 갔어요`
                  : '보러 간 기록이 있어요'}
              </p>

              <Link to={path.watchConfirm(pending.watchRecordId)} className={styles.pendingAction}>
                감상 확인하기
              </Link>
            </div>
          </div>
        </AsidePanel>
      ) : null}
    </>
  );
}
