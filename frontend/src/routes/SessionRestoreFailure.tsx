/*
 * 새로고침 세션 복구가 일시 오류로 끝났을 때 (업무 S15P21E106-908)
 *
 * 세션은 살아 있는데 내 프로필을 끝내 못 받았다(`contexts/sessionRestoreContext`). 로그인
 * 화면으로 보내지 않고 여기서 다시 시도를 받는다. `RequireAuth` · `HomeEntry`가 복구 중
 * 비워 두던 자리에 선다.
 */
import Button from '../components/Button';
import StatusView from '../components/StatusView';
import { useSessionRestore } from '../contexts/sessionRestoreContext';
import styles from './SessionRestoreFailure.module.css';

export default function SessionRestoreFailure({
  standalone = false,
}: {
  /** 레이아웃 바깥이라 화면 높이를 스스로 잡는다(`RequireAuth`) */
  standalone?: boolean;
}) {
  const { retry } = useSessionRestore();

  return (
    <div className={styles.screen} data-standalone={standalone || undefined}>
      <StatusView
        title="로그인 정보를 불러오지 못했어요"
        description="연결이 잠시 불안정해요. 다시 시도해 주세요."
      >
        <Button onClick={retry}>다시 시도</Button>
      </StatusView>
    </div>
  );
}
