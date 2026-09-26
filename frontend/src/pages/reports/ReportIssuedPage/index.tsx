/*
 * 3.3 새 취향 리포트 발급
 *
 * 이동표: 필름의 생성 결과 → 3.3 기존 인화 모션 → 3.4 실제 결과 확인
 *
 * 요소는 와이어프레임 그대로다 — `반기 취향 리포트` · `2026년 상반기 취향 필름을 인화했어요`,
 * 인화기에서 나오는 4컷(한 칸에 하나씩), 요약 세 줄, `리포트 보기`.
 *
 * 신규 FILM은 모션 완료·건너뛰기 뒤 API-080으로 첫 인화를 기록하며, 미완료 결과는 목록에서
 * 이어 받을 수 있다. 기존 HALF는 `리포트 보기`에서 완료를 기록한다. 상세로 **바꿔 끼운다** — 뒤로 가기가
 * 이 장면으로 돌아오면 같은 발급을 두 번 보게 된다. 기록에 실패하면 넘어가지 않고 그 자리에서
 * 다시 시도한다(계약 처리 규칙). 재요청은 처음 기록한 시각을 그대로 주므로 여러 번 눌러도
 * 발급 시각이 밀리지 않는다.
 *
 * SAME_CONTENT는 실제 재계산 결과가 같은 경우다. 기존 모션 뒤 동일 결과 팝업을 한 번
 * 표시하고 발급 API를 호출하지 않는다. 원본 결과와 목록은 그대로 보존한다.
 *
 * **버건디 벽 위의 4컷 인화기** (팀 결정 2026-09-14 · 사용자 시안 2026-09-15, S15P21E106-321).
 * - 인화기는 받은 사진(`4컷리포트_기계.png`)을 그대로 쓴다. 4컷 인화지가 아래 롤러 밑에서
 *   **끝부터 한 컷씩** 멈추며 나와 금속 판을 타고 받침대 앞턱 **위로** 넘어 내려온다(시안). 나온
 *   칸은 빈 인화지에서 사진으로 현상된다. 롤러 위로는 보이지 않는다
 * - 인화지는 한 장의 그림이 아니라 3.4 상세와 같은 `ReportFourCut`이다. 폭만 인화기에 맞춘다
 * - **이 화면만 배경이 버건디다.** 앱은 한 벽(`--gradient-page`)을 쓰지만(`design-concept.md`
 *   2.1), 사진의 버건디 벽과 이어지도록 이 화면만 예외로 칠한다
 * - 4컷이 내려오는 움직임은 `prefers-reduced-motion`이면 끈다
 * - 인화지가 나오는 동안 머리 오른쪽에 `건너뛰기`가 있다(사용자 요청 2026-09-15). 누르면 다 나온
 *   모습으로 바로 넘어가고, 다 나오면 사라진다
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import printerArt from '../../../assets/reports/report-printer.webp';
import Button from '../../../components/Button';
import ReportFourCut from '../../../components/ReportFourCut';
import StatusView from '../../../components/StatusView';
import Snackbar from '../../../components/Snackbar';
import { getReportDetail, issueReport } from '../../../api/reports';
import { isApiError } from '../../../api/apiError';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import { formatHalf } from '../../../utils/report';
import { useReportWall } from './useReportWall';
import styles from './ReportIssuedPage.module.css';
import type { ReportDetail } from '../../../types/report';

export default function ReportIssuedPage() {
  const navigate = useNavigate();
  const { reportId } = useParams();
  const id = Number(reportId);
  const [searchParams] = useSearchParams();
  const sameContent = searchParams.get('same') === '1';
  const [sameNotice, setSameNotice] = useState(() => sameContent && prefersReducedMotion());
  const notified = useRef(sameNotice);

  const [report, setReport] = useState<ReportDetail | null>(null);
  const [nickname, setNickname] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  /** 인화지가 나오는 중인지. 움직임 줄이기면 처음부터 다 나온 모습이다 */
  const [printing, setPrinting] = useState(() => !prefersReducedMotion());
  /** 발급을 기록하는 동안 버튼을 잠근다 */
  const [issuing, setIssuing] = useState(false);
  const [issueFailure, setIssueFailure] = useState<string | null>(null);
  const issueState = useRef({ id, pending: false, complete: false });

  // 불러오지 못한 상태는 인화기가 없어 공통 벽이다
  useReportWall(!failed);

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

  // 4컷 머리의 `{닉네임}의 취향 4컷`. 못 불러오면 `나의 취향 4컷`으로 둔다
  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((profile) => {
        if (active) setNickname(profile.nickname);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  const recordIssue = useCallback(async () => {
    if (sameContent) return true;
    if (issueState.current.id !== id) issueState.current = { id, pending: false, complete: false };
    if (issueState.current.complete) return true;
    if (issueState.current.pending) return false;
    issueState.current.pending = true;
    setIssuing(true);
    setIssueFailure(null);
    try {
      await issueReport(id);
      issueState.current.complete = true;
      return true;
    } catch (error) {
      setIssueFailure(issueMessage(error));
      return false;
    } finally {
      issueState.current.pending = false;
      setIssuing(false);
    }
  }, [id, sameContent]);

  useEffect(() => {
    if (!sameContent && report?.analysisScope === 'FILM' && !printing) {
      void Promise.resolve().then(recordIssue);
    }
  }, [report, printing, recordIssue, sameContent]);

  function finishPrinting() {
    setPrinting(false);
    if (sameContent && !notified.current) {
      notified.current = true;
      setSameNotice(true);
    }
  }

  async function openReport() {
    if (await recordIssue()) void navigate(path.report(id), { replace: true });
  }

  if (failed) {
    return (
      <div className={styles.statusPage}>
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
          <Button variant="text" onClick={() => void navigate(path.film())}>
            필름으로 이동
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
        <div className={styles.heading}>
          <h1 className={styles.title}>
            {report.analysisScope === 'FILM' ? '취향 리포트' : '반기 취향 리포트'}
          </h1>
          <p className={styles.lead}>
            {report.analysisScope === 'FILM'
              ? '지금까지 쌓인 취향 필름을 인화했어요'
              : `${report.period.year}년 ${formatHalf(report.period)} 취향 필름을 인화했어요`}
          </p>
        </div>

        {printing ? (
          <button type="button" className={styles.skip} onClick={finishPrinting}>
            건너뛰기
          </button>
        ) : null}
      </header>

      <div
        className={styles.printer}
        data-printed={printing ? undefined : 'true'}
        style={{ '--printer-image': `url(${printerArt})` } as CSSProperties}
      >
        <div className={styles.machine} aria-hidden="true" />

        {/* 아래 롤러 밑부터 보인다. 위만 자르고 아래로는 인화기 밖까지 내려온다 */}
        <div className={styles.slot}>
          <div className={styles.print}>
            {/* 마지막으로 현상되는 칸(01 장르)이 끝나면 다 나온 것이다 */}
            <div
              className={styles.feed}
              onAnimationEnd={(event) => {
                if (event.target instanceof HTMLElement && event.target.dataset.cut === 'genre') {
                  finishPrinting();
                }
              }}
            >
              <ReportFourCut
                cuts={report.fourCut}
                owner={nickname}
                createdAt={report.createdAt}
                characterCode={report.characterCard?.code}
              />
            </div>
          </div>
        </div>
      </div>

      <ul className={styles.highlights}>
        {report.highlights.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>

      <div className={styles.actions}>
        {issueFailure ? (
          <p className={styles.failure} role="alert">
            {issueFailure}
          </p>
        ) : null}
        <Button
          disabled={issuing || (report.analysisScope === 'FILM' && printing)}
          onClick={() => void openReport()}
        >
          {issueFailure ? '다시 시도' : '리포트 보기'}
        </Button>
      </div>
      {sameNotice ? (
        <div className={styles.sameNotice}>
          <Snackbar actionLabel="확인" onAction={() => setSameNotice(false)}>
            지난 리포트와 같아요!
          </Snackbar>
        </div>
      ) : null}
    </div>
  );
}

/** API-080 실패 안내. 계약의 오류는 404 · 409 둘뿐이라 나머지는 한 문구로 묶는다 */
function issueMessage(error: unknown): string {
  if (isApiError(error)) {
    if (error.code === 'REPORT_GENERATING') {
      return '리포트를 아직 만드는 중이에요. 잠시 후 다시 시도해 주세요.';
    }

    if (error.status === 409) {
      return '지금은 이 리포트를 뽑을 수 없어요. 취향 4컷 목록에서 확인해 주세요.';
    }
  }

  return '발급을 기록하지 못했어요. 잠시 후 다시 시도해 주세요.';
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}
