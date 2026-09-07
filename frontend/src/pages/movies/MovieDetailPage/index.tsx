/*
 * 2.4 영화 상세
 * 헤더와 레이아웃만 연결한 상태다. 와이어프레임 기반 UI는 S15P21E106-259에서 구현한다.
 */
import { useParams } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';

export default function MovieDetailPage() {
  const { movieId } = useParams();

  return (
    <>
      <ScreenHeader title="영화 상세" showBack />
      <section data-screen="2.4">
        <p>movieId: {movieId}</p>
        <p>2.4 영화 상세 — UI 미구현</p>
      </section>
    </>
  );
}
