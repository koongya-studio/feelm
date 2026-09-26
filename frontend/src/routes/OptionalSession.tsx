import { Outlet } from 'react-router';
import { useAuth } from '../contexts/authContext';
import { useSessionRestore } from '../contexts/sessionRestoreContext';
import SessionRestoreFailure from './SessionRestoreFailure';

/** 선택 인증 API도 새로고침 시 세션 복구를 기다려 차단 관계를 정확히 판정한다. */
export default function OptionalSession() {
  const { status } = useAuth();
  const { failed } = useSessionRestore();
  if (status === 'restoring') return failed ? <SessionRestoreFailure standalone /> : null;
  return <Outlet />;
}
