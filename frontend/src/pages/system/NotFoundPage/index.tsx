/*
 * 없는 경로.
 * 오류 화면의 최종 형태는 로딩 · 빈 데이터 · 오류 상태 업무(S15P21E106-264)에서 정한다.
 */
import { Link, useLocation } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';
import { path } from '../../../routes/paths';

export default function NotFoundPage() {
  const { pathname } = useLocation();

  return (
    <>
      <ScreenHeader title="페이지를 찾을 수 없습니다" />
      <section data-screen="not-found">
        <p>{pathname} 경로가 없습니다.</p>
        <Link to={path.home()}>홈으로 이동</Link>
      </section>
    </>
  );
}
