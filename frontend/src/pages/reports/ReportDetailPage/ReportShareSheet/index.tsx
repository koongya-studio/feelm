/*
 * 3.5 취향 리포트 공유
 *
 * 이동표: 3.4 → 3.5 공유하기 · 7.4 ⇢ 3.5 공유하기 · 3.5 ⇢ 3.4 공유 완료 또는 공유 취소
 *
 * 와이어프레임은 3.4 위에 올라온 시트로 그렸다. 3.4 · 7.4가 같은 화면이라 그 폴더 안에 둔다.
 * 요소 — `공유하기`, `이미지로 저장`, 안내 두 줄.
 *
 * - `공유하기`는 기기의 공유 메뉴(Web Share API)를 연다. 지원하지 않는 브라우저면 알린다
 * - `이미지로 저장`은 API-061로 이미지 카드를 만든다. 계약은 서명된 짧은 주소를 주고
 *   클라이언트가 내려받는 방식이다. **목업에는 실제 이미지가 없어** 만든 뒤 안내만 한다
 * - 공유를 마치거나 취소하면 시트를 닫는다(`3.5 ⇢ 3.4`)
 *
 * `[?]` 공유할 내용이 이미지인지 주소인지 정해지지 않았다. 와이어프레임 안내는 `공유 이미지`라고
 * 적었지만 이미지 파일 공유는 브라우저마다 지원이 다르다. 지금은 제목 글자만 넘긴다.
 */
import { useEffect, useId, useRef, useState } from 'react';
import Button from '../../../../components/Button';
import { createReportImage } from '../../../../api/reports';
import styles from './ReportShareSheet.module.css';

type Props = {
  reportId: number;
  title: string;
  onClose: () => void;
};

export default function ReportShareSheet({ reportId, title, onClose }: Props) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const titleId = useId();
  const titleRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !busy) onClose();
    }

    window.addEventListener('keydown', onKeyDown);

    return () => window.removeEventListener('keydown', onKeyDown);
  }, [busy, onClose]);

  async function share() {
    if (typeof navigator.share !== 'function') {
      setNotice('이 브라우저에서는 공유 메뉴를 열 수 없어요');

      return;
    }

    setBusy(true);

    try {
      await navigator.share({ title, text: `Feelm ${title}` });
      onClose();
    } catch {
      // 사용자가 공유 메뉴를 닫은 경우도 여기로 온다. 시트는 그대로 둔다
      setBusy(false);
    }
  }

  async function saveImage() {
    setBusy(true);
    setNotice('');

    try {
      await createReportImage(reportId);
      setNotice('이미지를 만들었어요');
    } catch {
      setNotice('이미지를 만들지 못했어요. 잠시 후 다시 시도해 주세요');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.overlay}>
      <button
        type="button"
        className={styles.scrim}
        aria-label="공유 닫기"
        disabled={busy}
        onClick={onClose}
      />

      <div className={styles.sheet} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <span className={styles.handle} aria-hidden="true" />

        <h2 id={titleId} ref={titleRef} tabIndex={-1} className={styles.title}>
          공유하기
        </h2>

        <div className={styles.actions}>
          <Button disabled={busy} onClick={() => void share()}>
            공유하기
          </Button>
          <Button variant="secondary" disabled={busy} onClick={() => void saveImage()}>
            이미지로 저장
          </Button>
        </div>

        {notice ? (
          <p className={styles.notice} role="status">
            {notice}
          </p>
        ) : null}

        <ul className={styles.notes}>
          <li>기기의 기본 공유 메뉴에서 카카오톡 · 인스타그램 등 원하는 앱을 선택해요</li>
          <li>공유 이미지에는 개인정보 없이 취향 정보만 담겨요</li>
        </ul>
      </div>
    </div>
  );
}
