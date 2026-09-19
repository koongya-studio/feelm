/*
 * EXT-02 OTT 취향 분석 중
 *
 * 이동표: EXT-01 → EXT-02 · EXT-02 → EXT-03 분석 완료 · EXT-02 ⇢ EXT-05 데이터 부족
 *
 * 요소는 와이어프레임 그대로다 — `내 취향과 OTT 콘텐츠를 비교하고 있어요`,
 * `필름을 감는 중이에요 · 잠시만요`, 단계 넷.
 *
 * **분석은 별도 계약이 아니다.** API-068이 결과를 바로 준다. 5.4처럼 응답을 기다리는 동안의
 * 전환 화면이고, 응답이 오면 EXT-03으로 **바꿔 끼운다** — 뒤로 가기가 분석 중으로 돌아오면
 * 다시 분석이 돈다.
 *
 * **EXT-05로 가는 갈림길이 여기다**(업무 -563). 계약의 `insufficientData`뿐 아니라 `items`가
 * 빈 경우도 같이 본다 — 계약은 현재 Candidate가 있으면 취향 입력이 0건이어도
 * `insufficientData: false`를 주므로 그 값만으로는 보여줄 것이 없는 상태를 가려내지 못한다.
 *
 * 단계 넷은 일정한 간격으로 하나씩 켠다. 실제 서버 진행과 이어진 값이 아니다 — 계약에
 * 진행 상태가 없다. 모두 켜질 때까지를 최소 노출 시간으로 둔다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import StatusView from '../../../components/StatusView';
import { CheckIcon } from '../../../components/Icon';
import { getOttRecommendations } from '../../../api/ottRecommendations';
import { ottFailureView } from '../failure';
import { path } from '../../../routes/paths';
import styles from './OttAnalyzingPage.module.css';

const STEPS = [
  '좋아한 장르를 확인하는 중',
  '높은 별점을 준 영화를 비교하는 중',
  '숨은 취향까지 찾는 중',
  'OTT별로 볼 수 있는 영화를 세는 중',
];

const STEP_MS = 400;

export default function OttAnalyzingPage() {
  const navigate = useNavigate();

  const [doneCount, setDoneCount] = useState(0);
  const [failure, setFailure] = useState<unknown>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    const timer = window.setInterval(() => {
      setDoneCount((count) => Math.min(count + 1, STEPS.length));
    }, STEP_MS);

    const waited = new Promise((resolve) => window.setTimeout(resolve, STEP_MS * STEPS.length));

    Promise.all([getOttRecommendations(), waited])
      .then(([result]) => {
        if (!active) return;

        const empty = result.insufficientData || result.items.length === 0;
        void navigate(empty ? path.ottInsufficient() : path.ottResult(), { replace: true });
      })
      .catch((error: unknown) => {
        if (!active) return;

        setFailure(error);
        setFailed(true);
      });

    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [navigate, reloadCount]);

  if (failed) {
    const view = ottFailureView(failure, 'OTT 취향을 분석하지 못했어요');

    return (
      <div className={styles.page}>
        <StatusView titleAs="h1" title={view.title} description={view.description}>
          <Button
            onClick={() => {
              setFailed(false);
              setDoneCount(0);
              setReloadCount((count) => count + 1);
            }}
          >
            {view.retryLabel}
          </Button>
          <Button variant="text" onClick={() => void navigate(path.ottFinder())}>
            처음으로
          </Button>
        </StatusView>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.stage}>
        {/* 감기는 필름. 5.4 현상 띠와 같은 장면이다 */}
        <div className={styles.strip} aria-hidden="true">
          <span className={styles.light} />
        </div>

        <h1 className={styles.title}>
          내 취향과 OTT 콘텐츠를
          <br />
          비교하고 있어요
        </h1>
        <p className={styles.lead}>필름을 감는 중이에요 · 잠시만요</p>

        <ol className={styles.steps} aria-live="polite">
          {STEPS.map((step, index) => (
            <li key={step} className={styles.step} data-done={index < doneCount}>
              <span className={styles.mark} aria-hidden="true">
                {index < doneCount ? <CheckIcon /> : null}
              </span>
              {step}
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}
