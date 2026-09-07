/*
 * 1.1 영화 취향 초기 설정
 * 헤더와 레이아웃만 연결한 상태다. 와이어프레임 기반 UI는 S15P21E106-258에서 구현한다.
 */
import ScreenHeader from '../../../components/ScreenHeader';

export default function TasteOnboardingPage() {
  return (
    <>
      <ScreenHeader
        title="취향 초기 설정"
        subtitle="공간 안에 담으면 좋아한 영화, 밖에 두면 안 좋아한 영화예요"
      />
      <section data-screen="1.1">
        <p>1.1 영화 취향 초기 설정 — UI 미구현</p>
      </section>
    </>
  );
}
