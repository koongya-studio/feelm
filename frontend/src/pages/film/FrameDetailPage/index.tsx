/*
 * 3.1-A 프레임 상세
 * 헤더와 레이아웃만 연결한 상태다. 와이어프레임 기반 UI는 S15P21E106-260에서 구현한다.
 */
import { useParams } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';

export default function FrameDetailPage() {
  const { frameId } = useParams();

  return (
    <>
      <ScreenHeader title="프레임 상세" subtitle="내 감상 기록 중심" showBack />
      <section data-screen="3.1-A">
        <p>frameId: {frameId}</p>
        <p>3.1-A 프레임 상세 — UI 미구현</p>
      </section>
    </>
  );
}
