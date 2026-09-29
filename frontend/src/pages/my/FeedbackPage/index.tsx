import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { submitFeedback, type FeedbackInput } from '../../../api/feedback';
import Button from '../../../components/Button';
import FeedbackChoiceIcon from '../../../components/FeedbackChoiceIcon';
import ScreenHeader from '../../../components/ScreenHeader';
import { path } from '../../../routes/paths';
import styles from './FeedbackPage.module.css';

const satisfactionChoices: {
  value: NonNullable<FeedbackInput['satisfaction']>;
  label: string;
}[] = [
  { value: 'GOOD', label: '마음에 들어요' },
  { value: 'MIXED', label: '애매해요' },
  { value: 'BAD', label: '아쉬워요' },
];

function screenName(screenPath: string) {
  if (screenPath.startsWith('/app/movies/')) return '영화 상세';
  if (screenPath.startsWith('/app/film')) return '취향 필름';
  if (screenPath.startsWith('/app/recommendations')) return '추천';
  if (screenPath.startsWith('/app/part')) return '파티';
  if (screenPath.startsWith('/app/search')) return '검색';
  if (screenPath.startsWith('/app/otts')) return 'OTT';
  if (screenPath.startsWith('/app/reports') || screenPath.startsWith('/app/my/reports'))
    return '취향 리포트';
  if (screenPath.startsWith('/app/my/settings')) return '설정';
  if (screenPath.startsWith('/app/my')) return 'MY';
  return '이전';
}

export default function FeedbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const source = params.get('from');
  const screenPath = source?.startsWith('/app/') ? source : '/app' + path.settings();
  const recommendation =
    params.get('category') === 'RECOMMENDATION' || screenPath.startsWith('/app/recommendations');
  const movieId = /^\/app\/movies\/(\d+)(?:\/|$)/.exec(screenPath)?.[1];
  const sourceName = screenName(screenPath);
  const [satisfaction, setSatisfaction] = useState<FeedbackInput['satisfaction']>(null);
  const [comment, setComment] = useState('');
  const [pending, setPending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending || sent || (!satisfaction && !comment.trim())) return;
    setPending(true);
    setError('');
    try {
      await submitFeedback({
        category: recommendation ? 'RECOMMENDATION' : 'GENERAL',
        satisfaction,
        comment: comment.trim() || null,
        screenPath,
        movieId: movieId ? Number(movieId) : null,
        recommendationType: recommendation ? 'TODAY' : null,
      });
      setSent(true);
    } catch {
      setError('의견을 보내지 못했어요. 잠시 후 다시 시도해 주세요.');
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <ScreenHeader
        title="의견 보내기"
        subtitle="선택 하나 또는 한 줄만 남겨도 충분해요."
        showBack
        fallbackTo={path.settings()}
      />
      <main className={styles.page}>
        {sent ? (
          <div className={styles.done} role="status">
            <span className={styles.doneMark} aria-hidden="true">
              ✓
            </span>
            <h2>의견 감사합니다!</h2>
            <p>보내주신 의견을 서비스 개선에 참고할게요.</p>
            <Button onClick={() => navigate(-1)}>돌아가기</Button>
          </div>
        ) : (
          <form className={styles.form} onSubmit={(event) => void handleSubmit(event)}>
            <p className={styles.source}>
              {recommendation
                ? `${sourceName} 화면의 의견으로 기록돼요`
                : 'Feelm 서비스에 대한 의견으로 기록돼요'}
            </p>
            <div className={styles.sheet}>
              <fieldset className={styles.choices}>
                <legend>
                  {recommendation ? '나를 위한 영화 추천은 어땠나요?' : 'Feelm은 어땠나요?'}
                  <span className={styles.optional}>선택</span>
                </legend>
                <div className={styles.choiceGrid}>
                  {satisfactionChoices.map((choice) => (
                    <label key={choice.value} className={styles.choice}>
                      <input
                        type="radio"
                        name="satisfaction"
                        value={choice.value}
                        checked={satisfaction === choice.value}
                        onChange={() => setSatisfaction(choice.value)}
                      />
                      <FeedbackChoiceIcon mood={choice.value} />
                      <span className={styles.choiceText}>{choice.label}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className={styles.commentGroup}>
                <label className={styles.commentLabel} htmlFor="feedback-comment">
                  조금 더 알려주실래요? <span className={styles.optional}>선택</span>
                </label>
                <textarea
                  id="feedback-comment"
                  value={comment}
                  maxLength={1000}
                  rows={5}
                  onChange={(event) => setComment(event.target.value)}
                  placeholder={
                    recommendation
                      ? '어떤 점이 마음에 들거나 아쉬웠나요?'
                      : '좋았던 점, 불편했던 점, 바라는 점을 자유롭게 적어주세요.'
                  }
                />
                {comment.length >= 800 ? (
                  <span className={styles.count}>{comment.length} / 1,000</span>
                ) : null}
              </div>
            </div>
            {error ? (
              <p role="alert" className={styles.error}>
                {error}
              </p>
            ) : null}
            <Button type="submit" disabled={pending || (!satisfaction && !comment.trim())}>
              {pending ? '보내는 중…' : '의견 보내기'}
            </Button>
          </form>
        )}
      </main>
    </>
  );
}
