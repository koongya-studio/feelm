import styles from './FeedbackChoiceIcon.module.css';

type Mood = 'GOOD' | 'MIXED' | 'BAD';

export default function FeedbackChoiceIcon({ mood }: { mood: Mood }) {
  return (
    <svg
      className={styles.icon}
      data-mood={mood}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      {mood === 'MIXED' ? (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="M9.6 9a2.5 2.5 0 1 1 4.1 1.9c-.9.7-1.7 1.1-1.7 2.6" />
          <path d="M12 17.2h.01" strokeWidth="2.5" />
        </>
      ) : (
        <>
          <path d="M8 10v11H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3Z" />
          <path d="m8 10 4.2-6.9a2 2 0 0 1 3.7 1.3L15.2 9H19a2.5 2.5 0 0 1 2.4 3.2l-1.7 6.6a3 3 0 0 1-2.9 2.2H8" />
        </>
      )}
    </svg>
  );
}
