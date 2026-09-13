/*
 * 7.8-A-1 회원 탈퇴 확인
 *
 * 이동표: 7.8-A → 7.8-A-1 회원 탈퇴 · 7.8-A-1 ⇢ 7.8-A 계속 이용하기
 *
 * 요소는 와이어프레임 그대로다 — 경고 표식, `정말 탈퇴하시겠어요?`, 사라지는 것 세 줄,
 * `위 내용을 모두 확인했어요` 체크, `계속 이용하기`(주 동작) · `탈퇴하기`,
 * `탈퇴하기를 누르면 한 번 더 확인해요`.
 *
 * **주 동작은 `계속 이용하기`다.** 와이어프레임도 그쪽을 검정으로 칠했다. 되돌릴 수 없는
 * 쪽을 가장 강한 버튼으로 두지 않는다. `탈퇴하기`는 체크하기 전에는 누를 수 없고,
 * 누르면 확인 창이 한 번 더 뜬다.
 *
 * 사라지는 수치는 API-014 `stats`에서 읽는다(프레임 · 팝콘 · 평가). 와이어프레임의
 * `평가 96개`는 같은 사용자의 100과 어긋나 쓰지 않았다(screen-spec-my.md 3.1).
 *
 * `[?]` API-013은 이메일 계정에 `password`를 요구하지만 와이어프레임에 입력이 없다.
 *       목업은 본문 없이 성공한다(`api/settings.ts`)
 * `[?]` 탈퇴한 뒤 어디로 가는지 이동표에 없다. 0.1 시작 화면으로 보낸다
 * `[?]` 확인 창 문구는 와이어프레임에 없다
 */
import { useEffect, useId, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ConfirmDialog from '../../../components/ConfirmDialog';
import { withdraw } from '../../../api/settings';
import { getMyProfile } from '../../../api/users';
import { useAuth } from '../../../contexts/authContext';
import { path } from '../../../routes/paths';
import styles from './WithdrawPage.module.css';
import type { MyProfileStats } from '../../../types/user';

export default function WithdrawPage() {
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const checkId = useId();

  /** 수치를 못 읽어도 탈퇴 안내는 해야 하므로 실패하면 수치 없는 문장으로 쓴다 */
  const [stats, setStats] = useState<MyProfileStats | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((profile) => {
        if (active) setStats(profile.stats);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  async function handleWithdraw() {
    setWithdrawing(true);
    setFailed(false);

    try {
      await withdraw();
    } catch {
      setWithdrawing(false);
      setConfirming(false);
      setFailed(true);

      return;
    }

    // 계정이 사라졌으니 세션도 끊는다. 서버가 쿠키를 지우지만(API-013) 화면 상태는 여기서 바꾼다
    try {
      await signOut();
    } finally {
      void navigate(path.authStart(), { replace: true });
    }
  }

  const losses = stats
    ? [
        `취향 필름 ${stats.frameCount}프레임과 팝콘 ${stats.popcornCount}알이 모두 삭제돼요`,
        `평가 ${stats.ratingCount}개와 한줄평이 삭제돼요`,
      ]
    : ['취향 필름과 팝콘이 모두 삭제돼요', '평가와 한줄평이 삭제돼요'];

  return (
    <div className={styles.page}>
      <span className={styles.mark} aria-hidden="true">
        !
      </span>

      <h1 className={styles.title}>정말 탈퇴하시겠어요?</h1>

      <ul className={styles.losses}>
        {[...losses, '삭제된 데이터는 복구할 수 없어요'].map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>

      <label htmlFor={checkId} className={styles.check}>
        <input
          id={checkId}
          type="checkbox"
          className={styles.checkbox}
          checked={acknowledged}
          disabled={withdrawing}
          onChange={(event) => setAcknowledged(event.target.checked)}
        />
        위 내용을 모두 확인했어요
      </label>

      {failed ? (
        <p className={styles.error} role="alert">
          탈퇴하지 못했어요. 잠시 후 다시 시도해 주세요
        </p>
      ) : null}

      <div className={styles.actions}>
        {/* 뒤로 가기다. 7.8 · 7.8-A 어디서 왔든 온 곳으로 돌아간다 */}
        <Button disabled={withdrawing} onClick={() => void navigate(-1)}>
          계속 이용하기
        </Button>

        <Button
          variant="secondary"
          disabled={!acknowledged || withdrawing}
          onClick={() => setConfirming(true)}
        >
          탈퇴하기
        </Button>
      </div>

      <p className={styles.hint}>탈퇴하기를 누르면 한 번 더 확인해요</p>

      {confirming ? (
        <ConfirmDialog
          title="Feelm을 탈퇴할까요?"
          description="계정과 모든 기록이 바로 삭제돼요"
          confirmLabel="탈퇴"
          busy={withdrawing}
          onConfirm={() => void handleWithdraw()}
          onCancel={() => setConfirming(false)}
        />
      ) : null}
    </div>
  );
}
