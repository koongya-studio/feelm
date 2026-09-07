/*
 * 2.6 필름 추가 중 · 2.7 필름 추가 완료
 * 헤더와 레이아웃만 연결한 상태다. 와이어프레임 기반 UI는 S15P21E106-259에서 구현한다.
 */
import { useParams } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';

export default function RatingResultPage() {
  const { movieId } = useParams();

  return (
    <>
      <ScreenHeader title="취향 필름" />
      <section data-screen="2.6">
        <p>movieId: {movieId}</p>
        <p>2.6 필름 추가 중 · 2.7 필름 추가 완료 — UI 미구현</p>
      </section>
    </>
  );
}
