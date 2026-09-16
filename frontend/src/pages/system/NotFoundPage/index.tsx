/*
 * 없는 경로.
 *
 * 와이어프레임에 이 화면이 없다(frontend-structure.md 3.6). 상태 화면의 기준은 2.2-A
 * 하나뿐이므로 그 형태를 그대로 쓰고 문구만 최소로 새로 쓴다
 * (`common-components.md` 6.6 — 새로 쓰는 문구는 최소로).
 *
 * 어느 경로가 없었는지 보여 주면 무엇을 잘못 눌렀는지 알 수 있어 설명에 남긴다.
 */
import { useNavigate, useLocation } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { path } from '../../../routes/paths';

export default function NotFoundPage() {
  const { pathname } = useLocation();
  const navigate = useNavigate();

  return (
    <>
      <ScreenHeader title="페이지를 찾을 수 없습니다" />

      <StatusView
        title="이 주소에는 화면이 없어요"
        description={`${pathname} 경로를 찾지 못했어요`}
      >
        <Button onClick={() => void navigate(path.film())}>필름으로 이동</Button>
      </StatusView>
    </>
  );
}
