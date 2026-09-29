import { useState } from 'react';
import { submitFeedback, type FeedbackInput } from '../../api/feedback';
import Button from '../Button';
import FeedbackChoiceIcon from '../FeedbackChoiceIcon';
import { path } from '../../routes/paths';
import styles from './RecommendationFeedback.module.css';

const choices: { value: NonNullable<FeedbackInput['satisfaction']>; label: string }[] = [
  { value: 'GOOD', label: '마음에 들어요' },
  { value: 'MIXED', label: '애매해요' },
  { value: 'BAD', label: '아쉬워요' },
];

function alreadySent(storageKey: string) {
  try {
    return window.localStorage.getItem(storageKey) === 'sent';
  } catch {
    return false;
  }
}

export default function RecommendationFeedback({
  storageKey,
  partyId,
  aside = false,
}: {
  storageKey: string;
  partyId?: number;
  aside?: boolean;
}) {
  const party = partyId !== undefined;
  const subject = party ? '이번 파티 추천' : '나를 위한 영화 추천';
  const [sent, setSent] = useState(() => alreadySent(storageKey));
  const [expanded, setExpanded] = useState(false);
  const [satisfaction, setSatisfaction] = useState<FeedbackInput['satisfaction']>(null);
  const [comment, setComment] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');

  async function send(satisfaction: FeedbackInput['satisfaction']) {
    if (pending || sent || (!satisfaction && !comment.trim())) return;
    setPending(true);
    setError('');
    try {
      await submitFeedback({
        category: 'RECOMMENDATION',
        satisfaction,
        comment: comment.trim() || null,
        screenPath: '/app' + (party ? path.partyRecommendations(partyId) : path.recommendations()),
        movieId: null,
        recommendationType: party ? 'PARTY' : 'TODAY',
      });
      try {
        window.localStorage.setItem(storageKey, 'sent');
      } catch {
        // 저장소를 사용할 수 없어도 현재 화면에서는 다시 묻지 않는다.
      }
      setSent(true);
    } catch {
      setError('의견을 보내지 못했어요. 다시 시도해 주세요.');
    } finally {
      setPending(false);
    }
  }

  return (
    <section
      className={`${styles.card} ${party ? styles.embedded : ''} ${aside ? styles.aside : ''}`}
      aria-label={`${subject} 의견`}
    >
      {sent ? (
        <p className={styles.thanks} role="status">
          의견 고마워요! 다음 {party ? '파티 추천' : '추천'}에 참고할게요.
        </p>
      ) : (
        <>
          <p className={styles.eyebrow}>{subject}에 대한 의견으로 기록돼요</p>
          <h2 className={styles.title}>{subject}은 어땠나요?</h2>
          <p className={styles.hint}>
            {expanded ? '평가와 글을 함께 보내거나, 글만 남겨도 돼요.' : '하나만 골라도 충분해요.'}
          </p>
          <div className={styles.choices} role="group" aria-label={`${subject} 평가`}>
            {choices.map((choice) => (
              <button
                key={choice.value}
                type="button"
                disabled={pending}
                aria-pressed={expanded ? satisfaction === choice.value : undefined}
                className={`${styles.choice} ${expanded && satisfaction === choice.value ? styles.selected : ''}`}
                onClick={() => {
                  if (expanded) {
                    setSatisfaction(choice.value);
                    setError('');
                  } else {
                    void send(choice.value);
                  }
                }}
              >
                <FeedbackChoiceIcon mood={choice.value} />
                <span>{choice.label}</span>
              </button>
            ))}
          </div>
          <details className={styles.more} open={expanded}>
            <summary
              onClick={(event) => {
                event.preventDefault();
                setExpanded((value) => !value);
              }}
            >
              <span>조금 더 알려주실래요?</span>
              <span className={styles.optional}>선택</span>
            </summary>
            <label htmlFor="recommendation-feedback-comment">어떤 점이 좋거나 아쉬웠나요?</label>
            <textarea
              id="recommendation-feedback-comment"
              value={comment}
              maxLength={1000}
              rows={3}
              onChange={(event) => setComment(event.target.value)}
              placeholder="자유롭게 적어 주세요"
            />
            <div className={styles.submit}>
              <Button
                disabled={pending || (!satisfaction && !comment.trim())}
                onClick={() => void send(satisfaction)}
              >
                {pending ? '보내는 중…' : '의견 보내기'}
              </Button>
            </div>
          </details>
          {error ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}
