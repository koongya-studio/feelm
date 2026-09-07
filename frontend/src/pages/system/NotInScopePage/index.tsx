/*
 * 이번 단계 구현 범위가 아닌 탭(검색 · 파티 · MY)의 자리 화면.
 * 와이어프레임에 없는 개발 확인용 화면이며 새 디자인 요소를 추가하지 않는다.
 */
import { Link, useLocation } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';
import { path } from '../../../routes/paths';

export default function NotInScopePage() {
  const { pathname } = useLocation();

  return (
    <>
      <ScreenHeader title="준비 중인 화면" />
      <section data-screen="not-in-scope">
        <p>{pathname}은 이번 단계 구현 범위가 아닙니다.</p>
        <Link to={path.home()}>홈으로 이동</Link>
      </section>
    </>
  );
}
