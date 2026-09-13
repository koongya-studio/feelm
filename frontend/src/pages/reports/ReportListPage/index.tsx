/*
 * 7.3 취향 리포트 목록
 *
 * 이동표: 7.1 → 7.3 취향 리포트 · 3.1 ⇢ 7.3 취향 리포트 보기 · 7.3 → 7.4 리포트 선택
 *
 * 요소는 와이어프레임 그대로다 — 반기마다 필름 한 칸(위아래 구멍 줄), 흐린 `2026 하반기`,
 * `리포트를 누르면 상세 필름을 볼 수 있어요`. 연동은 API-059다.
 *
 * **연도로 나누고 한 해에 상반기 · 하반기 두 칸을 세로로 세워, 칸이 화면 높이를 꽉 채운다**
 * (사용자 결정 2026-09-13). 와이어프레임은 네 칸을 한 격자에 섞고 칸마다 연도를 적었다.
 *
 * - 가장 최근 리포트는 테두리를 진하게 둔다. 와이어프레임도 첫 칸만 진하게 그렸다
 * - `GENERATING`(아직 끝나지 않은 반기)은 누를 수 없다. 상세가 409다(API-060)
 * - 필름 구멍은 벽 위의 선이다. 어두운 블록을 쓰지 않는다 — 칸이 여럿이라 전부 어두우면
 *   `화면에 어두운 블록은 하나`(screen-spec-my.md 4장)가 무너진다
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getMyReports } from '../../../api/reports';
import { path } from '../../../routes/paths';
import { formatHalf } from '../../../utils/report';
import styles from './ReportListPage.module.css';
import type { ReportSummary } from '../../../types/report';

export default function ReportListPage() {
  const [reports, setReports] = useState<ReportSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMyReports()
      .then((value) => {
        if (active) {
          setReports(value.items);
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

  const header = <ScreenHeader title="취향 리포트" showBack />;

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

  if (!reports) {
    return header;
  }

  // 가장 최근의 만든 리포트. 테두리를 진하게 둔다
  const latestReadyId = reports.find((report) => report.status === 'READY')?.reportId ?? null;

  if (reports.length === 0) {
    return (
      <>
        {header}
        <StatusView
          variant="empty"
          title="아직 발급된 리포트가 없어요"
          description="반기마다 한 번 취향 리포트를 인화해 드려요"
        />
      </>
    );
  }

  const years = groupByYear(reports);

  return (
    <>
      {header}

      <div className={styles.page}>
        {years.map(({ year, halves }) => (
          <section key={year} className={styles.year} aria-labelledby={`report-year-${year}`}>
            <h2 id={`report-year-${year}`} className={styles.yearLabel}>
              {year}
            </h2>

            <ul className={styles.grid}>
              {halves.map((report) => (
                <li key={report.reportId}>
                  <ReportCard report={report} latest={report.reportId === latestReadyId} />
                </li>
              ))}
            </ul>
          </section>
        ))}

        <p className={styles.hint}>리포트를 누르면 상세 필름을 볼 수 있어요</p>
      </div>
    </>
  );
}

/**
 * 연도로 묶고 한 해 안에서는 상반기 · 하반기 순으로 둔다(사용자 결정 2026-09-13).
 * 연도는 최신이 위다. API-059는 반기를 최신순 한 줄로 주므로 화면에서 다시 묶는다.
 */
function groupByYear(reports: ReportSummary[]) {
  const years = [...new Set(reports.map((report) => report.period.year))].sort((a, b) => b - a);

  return years.map((year) => ({
    year,
    halves: reports
      .filter((report) => report.period.year === year)
      .sort((a, b) => a.period.half.localeCompare(b.period.half)),
  }));
}

/** 반기 한 칸. 만드는 중이면 누를 수 없다 */
function ReportCard({ report, latest }: { report: ReportSummary; latest: boolean }) {
  const label = formatHalf(report.period);

  if (report.status === 'GENERATING') {
    return (
      <div className={styles.film} data-generating="true">
        <Perforation />
        {/* 반기 글자가 다른 칸과 같은 높이에 오도록 상태를 한 덩어리로 묶는다 */}
        <span className={styles.body}>
          <span className={styles.half}>{label}</span>
          <span className={styles.generating}>만드는 중</span>
        </span>
        <Perforation />
      </div>
    );
  }

  return (
    <Link
      to={path.myReport(report.reportId)}
      className={styles.film}
      data-latest={latest}
      aria-label={report.title}
    >
      <Perforation />
      <span className={styles.half}>{label}</span>
      <Perforation />
    </Link>
  );
}

function Perforation() {
  return <span className={styles.perforation} aria-hidden="true" />;
}
