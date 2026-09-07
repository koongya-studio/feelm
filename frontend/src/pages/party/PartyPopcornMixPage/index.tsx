/*
 * 5.9 파티 팝콘 섞기
 * 헤더와 레이아웃만 연결한 상태다. 와이어프레임 기반 UI는 S15P21E106-261에서 구현한다.
 */
import { useParams } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';

export default function PartyPopcornMixPage() {
  const { partyId } = useParams();

  return (
    <>
      <ScreenHeader title="파티 팝콘 섞기" showBack />
      <section data-screen="5.9">
        <p>partyId: {partyId}</p>
        <p>5.9 파티 팝콘 섞기 — UI 미구현</p>
      </section>
    </>
  );
}
