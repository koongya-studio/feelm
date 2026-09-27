import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { generateReport, getMyReports } from '../../api/reports';
import { isApiError } from '../../api/apiError';
import { path } from '../../routes/paths';
import type { ReportGeneration } from '../../types/report';

/** 실제 생성 결과를 기존 인화 화면에 전달한다. 모션은 인화 화면이 그대로 맡는다. */
export function useReportGeneration() {
  const navigate = useNavigate();
  const busy = useRef(false);
  const mounted = useRef(false);
  const [generating, setGenerating] = useState(false);
  const [availability, setAvailability] = useState<ReportGeneration | null>(null);
  const [message, setMessage] = useState('');
  const [existingId, setExistingId] = useState<number | null>(null);

  useEffect(() => {
    mounted.current = true;
    let active = true;
    void Promise.resolve()
      .then(() => getMyReports())
      .then((list) => {
        if (active) setAvailability(list.generation ?? null);
      })
      .catch(() => undefined);
    return () => {
      active = false;
      mounted.current = false;
    };
  }, []);

  async function generate() {
    if (busy.current) return;
    busy.current = true;
    setGenerating(true);
    setMessage('');
    setExistingId(null);
    try {
      const result = await generateReport();
      if (!mounted.current) return;
      if (result.reused && result.reuseReason === 'SAME_CONTENT') {
        await navigate(`${path.reportIssued(result.reportId)}?same=1`);
      } else if (!result.reused && result.frontUnchanged) {
        await navigate(`${path.reportIssued(result.reportId)}?similar=1`);
      } else if (!result.reused || result.issuedAt === null) {
        await navigate(path.reportIssued(result.reportId));
      } else {
        setMessage('지난 리포트 이후 필름 변화가 없어요.\n기존 리포트를 확인해 주세요.');
        setExistingId(result.reportId);
      }
    } catch (error) {
      if (!mounted.current) return;
      const details = isApiError(error) ? error.details : null;
      if (
        details &&
        typeof details === 'object' &&
        'reason' in details &&
        details.reason === 'INSUFFICIENT_FRAMES'
      ) {
        setMessage('첫 리포트는 평가 프레임 20개부터 뽑을 수 있어요.');
      } else {
        setMessage(
          isApiError(error) && error.code === 'REPORT_GENERATING'
            ? '리포트를 계산하고 있어요. 잠시 후 다시 눌러 주세요.'
            : '리포트를 만들지 못했어요. 다시 눌러 시도해 주세요.',
        );
      }
    } finally {
      busy.current = false;
      if (mounted.current) setGenerating(false);
    }
  }

  return { generating, availability, message, existingId, generate, dismiss: () => setMessage('') };
}
