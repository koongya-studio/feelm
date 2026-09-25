import { useState } from 'react';
import { Link } from 'react-router';
import { path } from '../../../routes/paths';
import styles from './ServiceFeedbackPrompt.module.css';

function promptKey(userId: number) {
  return `feelm:service-feedback-prompt:${userId}`;
}

function hasDismissed(userId: number) {
  try {
    return window.localStorage.getItem(promptKey(userId)) === 'dismissed';
  } catch {
    return false;
  }
}

export default function ServiceFeedbackPrompt({
  userId,
  desktop,
}: {
  userId: number;
  desktop: boolean;
}) {
  const [dismissed, setDismissed] = useState(() => hasDismissed(userId));

  function dismiss() {
    try {
      window.localStorage.setItem(promptKey(userId), 'dismissed');
    } catch {
      // 저장소가 막혀 있어도 현재 화면에서는 다시 띄우지 않는다.
    }
    setDismissed(true);
  }

  if (dismissed) return null;

  return (
    <aside
      className={`${styles.prompt} ${desktop ? styles.desktop : ''}`}
      aria-label="서비스 의견 안내"
    >
      <div>
        <p className={styles.title}>Feelm을 써보니 어떠셨나요?</p>
        <p className={styles.description}>좋았던 점이나 불편했던 점을 편하게 알려주세요.</p>
      </div>
      <div className={styles.actions}>
        <Link to={path.feedback('/app/my')} onClick={dismiss} className={styles.link}>
          서비스 의견 보내기
        </Link>
        <button type="button" onClick={dismiss} className={styles.dismiss}>
          나중에
        </button>
      </div>
    </aside>
  );
}
