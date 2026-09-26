import { useEffect, useState } from 'react';
import { onNotificationChange } from '../api/notificationStream';

/** 연속 이벤트를 모아 갱신하고 이전 REST 응답은 화면 effect cleanup으로 무효화한다. */
export function useNotificationRevision(): number {
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe = onNotificationChange(() => {
      if (timer !== undefined) return;
      timer = setTimeout(() => {
        timer = undefined;
        setRevision((value) => value + 1);
      }, 100);
    });
    return () => {
      unsubscribe();
      clearTimeout(timer);
    };
  }, []);
  return revision;
}
