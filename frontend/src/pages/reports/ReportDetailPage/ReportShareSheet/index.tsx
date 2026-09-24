/*
 * 3.5 취향 리포트 공유
 *
 * 이동표: 3.4 → 3.5 공유하기 · 7.4 ⇢ 3.5 공유하기 · 3.5 ⇢ 3.4 공유 완료 또는 공유 취소
 *
 * 와이어프레임은 3.4 위에 올라온 시트로 그렸다. 3.4 · 7.4가 같은 화면이라 그 폴더 안에 둔다.
 * 요소 — `공유하기`, `이미지로 저장`, 안내 두 줄.
 *
 * - `공유하기`는 4컷 이미지 파일을 공유한다(업무 -849). 앞면 넷 한 장과 칸을 모두 뒤집은 뒷면
 *   한 장이다. 휴대폰은 기기의 공유 메뉴로 넘기고, 파일 공유를 못 쓰는 데스크톱 · 브라우저는
 *   두 장을 내려받는다(`utils/share.ts`의 `shareFiles`). 이미지는 부른 화면이 찍어 넘긴다
 * - 한때 이 화면 주소를 공유했는데(업무 -767) API-060은 타인 리포트를 404로 숨겨 **받은 사람은
 *   아무것도 볼 수 없었다.** 그래서 주소가 아니라 그림을 보낸다
 * - 공유 이미지는 브라우저에서만 쓰고 서버에 올리지 않는다. API-061은 여전히 `이미지로 저장`의
 *   앞면 한 장만 받는다
 * - 이미지를 찍는 동안 누른 동작의 효력이 끝나 브라우저가 공유를 거절할 수 있다(`blocked`).
 *   찍은 파일을 들고 있다가 한 번 더 누르면 바로 메뉴를 연다. 그래도 거절되면 내려받는다
 * - `이미지로 저장`은 화면의 `ReportFourCut` DOM을 그대로 PNG로 캡처하고 API-061에 저장한 뒤
 *   서명 URL을 실제 파일 다운로드로 연결한다(`../saveReportImage.ts`). 3.4 · 7.4의 `저장`과
 *   같은 함수라 부른 화면이 넘긴다
 * - 공유를 마치면 시트를 닫는다(`3.5 ⇢ 3.4`). 메뉴를 닫은 것(취소)은 시트를 그대로 둔다
 */
import { useEffect, useId, useRef, useState } from 'react';
import Button from '../../../../components/Button';
import { downloadBlob } from '../../../../utils/reportImage';
import { shareFiles } from '../../../../utils/share';
import { reportImageFailure } from '../saveReportImage';
import styles from './ReportShareSheet.module.css';

type Props = {
  title: string;
  /** 공유할 4컷 PNG(앞면 · 뒷면)를 찍는다. 실패하면 던진다 */
  onPrepareShare: () => Promise<File[]>;
  /** 4컷 PNG를 저장해 내려받는다. 실패하면 던진다 */
  onSaveImage: () => Promise<void>;
  onClose: () => void;
};

export default function ReportShareSheet({ title, onPrepareShare, onSaveImage, onClose }: Props) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  /** 찍어 둔 공유 이미지. 메뉴를 다시 열 때 또 찍지 않는다 */
  const [prepared, setPrepared] = useState<File[] | null>(null);
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
    setBusy(true);
    setNotice('');

    let files = prepared;
    if (!files) {
      try {
        files = await onPrepareShare();
      } catch (error) {
        setNotice(reportImageFailure(error));
        setBusy(false);

        return;
      }
    }

    const result = await shareFiles({ title, files });

    // 공유 메뉴로 보냈으면 마친 것이다
    if (result === 'shared') {
      onClose();

      return;
    }

    setBusy(false);
    if (result === 'cancelled') {
      setPrepared(files);

      return;
    }

    // 찍는 동안 누른 효력이 끝났을 수 있다. 처음 한 번은 다시 눌러 달라고 한다
    if (result === 'blocked' && !prepared) {
      setPrepared(files);
      setNotice('이미지를 준비했어요. 공유하기를 한 번 더 눌러 주세요');

      return;
    }

    setNotice(download(files));
  }

  /** 공유 메뉴를 못 쓰면 내려받는다. 부른 쪽이 알릴 문구를 돌려준다 */
  function download(files: File[]): string {
    try {
      files.forEach((file) => downloadBlob(file, file.name));
    } catch {
      return '이 브라우저에서는 공유할 수 없어요';
    }

    return files.length > 1
      ? '이미지 두 장을 저장했어요. 원하는 앱에서 직접 공유해 주세요'
      : '이미지를 저장했어요. 원하는 앱에서 직접 공유해 주세요';
  }

  async function saveImage() {
    setBusy(true);
    setNotice('');

    try {
      await onSaveImage();
      setNotice('이미지를 저장했어요. 원하는 앱에서 직접 공유해 주세요');
    } catch (error) {
      setNotice(reportImageFailure(error));
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
