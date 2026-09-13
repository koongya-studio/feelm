/*
 * 3.3 새 취향 리포트 발급
 *
 * 이동표: 3.3 → 3.4 새 리포트 확인 · 3.3 ⇢ 3.4 반기(6개월)마다 1회 발급 · 이전 반기와 비교
 *
 * 요소는 와이어프레임 그대로다 — `반기 취향 리포트` · `2026년 상반기 취향 필름을 인화했어요`,
 * 인화기에서 나오는 4컷(한 칸에 하나씩), 요약 세 줄, `리포트 보기`.
 *
 * **들어오는 화살표가 이동표에 없다.** 2.1 `취향 리포트 보기`가 아직 이 화면을 보지 않은
 * 리포트면 여기를 거치게 했다(`api/reports.ts` `getLatestReadyReport`, 업무 -308).
 * `리포트 보기`를 누르면 본 것으로 남기고 3.4로 **바꿔 끼운다** — 뒤로 가기가 이 장면으로
 * 돌아오면 같은 발급을 두 번 보게 된다.
 *
 * 어두운 블록은 4컷 띠 하나다. 인화기 배출구는 밝은 면의 틈으로만 그린다 — 둘 다 어두우면
 * 한 화면에 어두운 블록이 둘이 된다. 4컷이 내려오는 움직임은 `prefers-reduced-motion`이면 끈다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import ReportFourCut from '../../../components/ReportFourCut';
import StatusView from '../../../components/StatusView';
import { getReportDetail, markReportIntroSeen } from '../../../api/reports';
import { path } from '../../../routes/paths';
import { formatHalf } from '../../../utils/report';
import styles from './ReportIssuedPage.module.css';
import type { ReportDetailView } from '../../../types/unspecified';

export default function ReportIssuedPage() {
  const navigate = useNavigate();
  const { reportId } = useParams();
  const id = Number(reportId);

  const [report, setReport] = useState<ReportDetailView | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getReportDetail(id)
      .then((value) => {
        if (active) {
          setReport(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  async function openReport() {
    await markReportIntroSeen(id);
    void navigate(path.report(id), { replace: true });
  }

  if (failed) {
    return (
      <div className={styles.page}>
        <StatusView
          titleAs="h1"
          title="리포트를 불러오지 못했어요"
          description="주소가 잘못되었거나 잠시 문제가 생겼어요"
        >
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
          <Button variant="text" onClick={() => void navigate(path.home())}>
            홈으로 이동
          </Button>
        </StatusView>
      </div>
    );
  }

  if (!report) {
    return null;
  }

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>반기 취향 리포트</h1>
        <p className={styles.lead}>
          {report.period.year}년 {formatHalf(report.period)} 취향 필름을 인화했어요
        </p>
      </header>

      <div className={styles.printer}>
        {/* 인화기의 배출구. 4컷이 이 틈에서 내려온다 */}
        <div className={styles.slot} aria-hidden="true">
          <span className={styles.slotLine} />
        </div>

        <div className={styles.print}>
          <ReportFourCut
            headline={report.headline}
            caption={`${report.period.year} ${formatHalf(report.period)}`}
          />
        </div>
      </div>

      <ul className={styles.highlights}>
        {report.highlights.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>

      <div className={styles.actions}>
        <Button onClick={() => void openReport()}>리포트 보기</Button>
      </div>
    </div>
  );
}
