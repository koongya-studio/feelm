/*
 * 프로필 사진 메뉴 시트 (업무 S15P21E106-616)
 *
 * 7.2에서 사진을 누르면 아래에서 올라온다. 선택지는 넷이다.
 *
 * - `사진 촬영` · `앨범에서 선택` — 파일을 고르면 자르기 화면으로 간다
 * - `기본 이미지로 변경` — 바로 기본 이미지로 돌린다
 * - `현재 사진 삭제` — 한 번 더 묻고 지운다
 *
 * 뒤의 둘은 올린 사진이 있을 때만 누를 수 있다. 사진이 없으면 할 일이 없다.
 * 삭제는 글자로 위험을 알린다 — 색만으로 구분하지 않는다.
 */
import { useEffect, useRef } from 'react';
import styles from './ProfileEditPage.module.css';

type Props = {
  hasPhoto: boolean;
  onCamera: () => void;
  onAlbum: () => void;
  onDefault: () => void;
  onDelete: () => void;
  onClose: () => void;
};

export default function AvatarSheet({
  hasPhoto,
  onCamera,
  onAlbum,
  onDefault,
  onDelete,
  onClose,
}: Props) {
  const firstRef = useRef<HTMLButtonElement>(null);

  // 시트가 열리면 초점을 안으로 들인다. 밖에 두면 뒤 화면을 계속 읽는다
  useEffect(() => {
    firstRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose();
    }

    window.addEventListener('keydown', onKeyDown);

    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className={styles.sheetLayer}>
      <button type="button" className={styles.scrim} aria-label="닫기" onClick={onClose} />

      <div className={styles.sheet} role="dialog" aria-modal="true" aria-label="프로필 사진">
        <p className={styles.sheetTitle}>프로필 사진</p>

        <ul className={styles.sheetList}>
          <li>
            <button ref={firstRef} type="button" className={styles.sheetItem} onClick={onCamera}>
              사진 촬영
            </button>
          </li>
          <li>
            <button type="button" className={styles.sheetItem} onClick={onAlbum}>
              앨범에서 선택
            </button>
          </li>
          <li>
            <button
              type="button"
              className={styles.sheetItem}
              disabled={!hasPhoto}
              onClick={onDefault}
            >
              기본 이미지로 변경
            </button>
          </li>
          <li>
            <button
              type="button"
              className={styles.sheetItem}
              data-tone="danger"
              disabled={!hasPhoto}
              onClick={onDelete}
            >
              현재 사진 삭제
            </button>
          </li>
        </ul>

        <button type="button" className={styles.sheetCancel} onClick={onClose}>
          취소
        </button>
      </div>
    </div>
  );
}
