/*
 * 1.2 구독 OTT 초기 설정
 * 헤더와 레이아웃만 연결한 상태다. 와이어프레임 기반 UI는 S15P21E106-258에서 구현한다.
 */
import ScreenHeader from '../../../components/ScreenHeader';

export default function OttOnboardingPage() {
  return (
    <>
      <ScreenHeader title="구독 중인 OTT가 있으신가요?" />
      <section data-screen="1.2">
        <p>1.2 구독 OTT 초기 설정 — UI 미구현</p>
      </section>
    </>
  );
}
