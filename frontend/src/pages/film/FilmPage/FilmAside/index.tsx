/*
 * 3.1 취향 필름의 우측 보조 패널 (업무 S15P21E106-783).
 *
 * 2026-09-21 시안이 이 자리에 카드 셋을 둔다 — **나의 취향 팝콘 · 취향 리포트 · 이어보기**다.
 * 팝콘 카드는 7.1 MY의 `팝콘 분포도`와 같은 표라 공용으로 옮겼다(`components/PopcornFlavorPanel`,
 * 업무 -786). 이 화면이 그리는 모양과 가는 곳은 그대로다.
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
import Button from '../../../../components/Button';
import type { useReportGeneration } from '../../../reports/useReportGeneration';
import FilmReel from '../../../../components/FilmReel';
import PopcornFlavorPanel from '../../../../components/PopcornFlavorPanel';
import Poster from '../../../../components/Poster';
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
  /** 상시 생성 버튼과 서버의 생성 가능 상태 */
  reportGeneration: ReturnType<typeof useReportGeneration>;
};

export default function FilmAside({ bucket, frames, reportGeneration }: Props) {
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

  const reportFrames = frames.slice(0, REPORT_FRAME_COUNT);

  return (
    <>
      {bucket ? (
        <PopcornFlavorPanel
          title="나의 취향 팝콘"
          bucket={bucket}
          to={path.popcornBucket()}
          linkLabel={`나의 취향 팝콘 ${bucket.totalCount}알 자세히 보기`}
        />
      ) : null}

      <AsidePanel title="취향 리포트" to={path.tasteReports()} linkLabel="리포트 목록 보기">
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

        <p className={styles.reportText}>지금까지 본 영화로 만든 나만의 취향 분석</p>
        <Button
          variant="secondary"
          disabled={reportGeneration.generating}
          onClick={() => void reportGeneration.generate()}
        >
          <span className={styles.reportAction}>
            {reportGeneration.generating ? '분석하는 중' : '리포트 뽑기'}
          </span>
        </Button>
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
