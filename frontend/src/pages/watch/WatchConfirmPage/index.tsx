/*
 * 2.4-A 감상 확인
 * 헤더와 레이아웃만 연결한 상태다. 와이어프레임 기반 UI는 S15P21E106-259에서 구현한다.
 */
import { useParams } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';

export default function WatchConfirmPage() {
  const { watchRecordId } = useParams();

  return (
    <>
      <ScreenHeader title="감상 확인" />
      <section data-screen="2.4-A">
        <p>watchRecordId: {watchRecordId}</p>
        <p>2.4-A 감상 확인 — UI 미구현</p>
      </section>
    </>
  );
}
