/*
 * 7.3 취향 4컷 목록
 *
 * 이동표: 7.1 → 7.3 취향 리포트 · 3.1 ⇢ 7.3 취향 리포트 보기 · 7.3 → 7.4 리포트 선택
 *
 * **모눈 노트에 반기마다 받은 4컷을 테이프로 붙여 모아 둔 장면이다**(사용자 시안 2026-09-15,
 * 업무 -446). 첫 목록(와이어프레임의 필름 칸)을 바꿨다.
 *
 * - 머리는 가운데 `취향 4컷` · `차곡차곡 모아둔 나의 영화 취향`
 * - 연도마다 손글씨 연도와 가로줄, 그 아래 두 반기의 4컷을 나란히 붙인다. 연도는 최신이 위,
 *   한 해 안에서는 **최근 반기가 왼쪽**이다(시안). 4컷 아래에 인화한 날짜를 적는다
 * - 4컷은 3.4 · 7.4 상세와 같은 `ReportFourCut`을 머리 없이 작게 쓴다. 누르면 7.4로 간다
 * - 4컷마다 조금씩 기울이고 윗변에 빨간 테이프를 붙인다
 * - `GENERATING`(아직 끝나지 않은 반기)은 누를 수 없다(API-060이 409다). 빈 인화지에 `인화 중`을 적는다.
 *   계약은 이 반기를 `items`가 아니라 `current`로만 알려 주므로(API-059) 목록 앞에 따로 붙인다
 * - 4컷 한 장을 못 불러오면 그 자리만 빈 인화지로 두고 상세로는 갈 수 있게 한다
 *
 * `[!]` API-059 목록에는 4컷 값이 없다. 반기마다 API-060 상세를 한 번씩 더 부른다. 반기가 쌓이면
 * 호출이 늘어나므로 목록에 4컷 요약을 넣을지 정해야 한다(후속).
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ReportFourCut from '../../../components/ReportFourCut';
import ReportTape from '../../../components/ReportTape';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getMyReports, getReportDetail } from '../../../api/reports';
import { path } from '../../../routes/paths';
import { formatHalf, formatPrintDate } from '../../../utils/report';
import styles from './ReportListPage.module.css';
import type { ReportDetail, ReportPeriod, ReportSummary } from '../../../types/report';

const TITLE = '취향 4컷';

/**
 * 반기 한 장.
 *
 * `summary`는 목록에 공개된 반기만 있다(API-059 `items`). 인화 중인 반기는 `current`에서만
 * 오므로 반기와 상태만 안다. 상세를 못 불러왔으면 `detail`이 null이다.
 */
type Entry = {
  reportId: number;
  period: ReportPeriod;
  summary: ReportSummary | null;
  detail: ReportDetail | null;
};

export default function ReportListPage() {
  const navigate = useNavigate();
  const [entries, setEntries] = useState<Entry[] | null>(null);
  /** 최신 반기를 아직 만들고 있는지(API-059 `preparing`) */
  const [preparing, setPreparing] = useState(false);
  /** 공개됐지만 아직 첫 발급하지 않은 최신 리포트(API-059 current) */
  const [readyReportId, setReadyReportId] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMyReports()
      .then(async ({ items, current, preparing: inProgress }) => {
        if (active) {
          setPreparing(inProgress);
          setReadyReportId(
            current?.status === 'READY' && current.issuedAt === null ? current.reportId : null,
          );
        }

        const opened = await Promise.all(
          items.map(async (summary): Promise<Entry> => ({
            reportId: summary.reportId,
            period: summary.period,
            summary,
            detail:
              summary.status === 'READY'
                ? await getReportDetail(summary.reportId).catch(() => null)
                : null,
          })),
        );

        // 인화 중인 반기는 목록에 없다. 가장 최근 반기이므로 맨 앞에 둔다
        if (current?.status === 'GENERATING') {
          return [
            { reportId: current.reportId, period: current.period, summary: null, detail: null },
            ...opened,
          ];
        }

        return opened;
      })
      .then((value) => {
        if (active) {
          setEntries(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  const header = (
    <ScreenHeader
      title={TITLE}
      subtitle="차곡차곡 모아둔 나의 영화 취향"
      align="center"
      showBack
      fallbackTo={path.my()}
    />
  );

  if (failed) {
    return (
      <>
        {header}
        <StatusView title="리포트를 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  if (!entries) {
    return header;
  }

  if (entries.length === 0) {
    if (readyReportId !== null) {
      return (
        <div className={styles.screen}>
          {header}

          <main className={styles.readyPage}>
            <section className={styles.readyNote} aria-labelledby="ready-report-title">
              <ReportTape />

              <div className={styles.readyStrip} aria-hidden="true">
                <span />
                <span />
                <span />
                <span />
              </div>

              <p className={styles.readyLabel}>
                <span className={styles.readyDot} aria-hidden="true" />새 리포트
              </p>
              <h2 className={styles.readyTitle} id="ready-report-title">
                새 취향 4컷이 도착했어요
              </h2>
              <p className={styles.readyDescription}>
                이번 반기에 쌓인 영화 취향을
                <br />한 장씩 인화해 보세요
              </p>

              <div className={styles.readyAction}>
                <Button onClick={() => void navigate(path.reportIssued(readyReportId))}>
                  확인하러 가기
                </Button>
              </div>
            </section>
          </main>
        </div>
      );
    }

    return (
      <>
        {header}
        <StatusView
          variant="empty"
          title={preparing ? '취향 4컷을 인화하는 중이에요' : '아직 인화된 취향 4컷이 없어요'}
          description={
            preparing ? '반기 집계가 끝나면 알려 드려요' : '반기마다 한 번 취향 4컷을 인화해 드려요'
          }
        />
      </>
    );
  }

  return (
    <div className={styles.screen}>
      {header}

      <div className={styles.page}>
        {groupByYear(entries).map(({ year, halves }) => (
          <section key={year} className={styles.year} aria-labelledby={`report-year-${year}`}>
            <h2 id={`report-year-${year}`} className={styles.yearLabel}>
              <span className={styles.yearHand}>{year}</span>
            </h2>

            <ul className={styles.board}>
              {halves.map((entry, index) => (
                <li key={entry.reportId} className={styles.slot}>
                  <PinnedStrip entry={entry} tilt={index % 2 === 0 ? 'left' : 'right'} />
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  );
}

/**
 * 연도로 묶는다. 연도는 최신이 위, 한 해 안에서는 최근 반기(하반기)가 왼쪽이다(시안).
 * API-059는 반기를 최신순 한 줄로 주므로 화면에서 다시 묶는다.
 */
function groupByYear(entries: Entry[]) {
  const years = [...new Set(entries.map(({ period }) => period.year))].sort((a, b) => b - a);

  return years.map((year) => ({
    year,
    halves: entries
      .filter(({ period }) => period.year === year)
      .sort((a, b) => b.period.half.localeCompare(a.period.half)),
  }));
}

/** 테이프로 붙인 4컷 한 장과 그 아래 날짜 */
function PinnedStrip({ entry, tilt }: { entry: Entry; tilt: 'left' | 'right' }) {
  const { summary, detail, period } = entry;
  const half = `${period.year} ${formatHalf(period)}`;

  if (!summary || summary.status === 'GENERATING') {
    return (
      <div className={styles.pinned} data-tilt={tilt} data-generating="true">
        <ReportTape />
        <div className={styles.blank}>
          <span className={styles.blankText}>인화 중</span>
        </div>
        <span className={styles.date}>
          {half} · <span>만드는 중</span>
        </span>
      </div>
    );
  }

  return (
    <Link
      to={path.myReport(summary.reportId)}
      className={styles.pinned}
      data-tilt={tilt}
      aria-label={summary.title}
    >
      <ReportTape />
      {detail ? (
        <div className={styles.strip} aria-hidden="true">
          <ReportFourCut
            cuts={detail.fourCut}
            createdAt={detail.createdAt}
            head={false}
            characterCode={detail.characterCard?.code}
          />
        </div>
      ) : (
        <div className={styles.blank} aria-hidden="true">
          <span className={styles.blankText}>{half}</span>
        </div>
      )}
      <span className={styles.date} aria-hidden="true">
        {formatPrintDate(summary.createdAt).stamp}
      </span>
    </Link>
  );
}
