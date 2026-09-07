/*
 * 1.3 첫 취향 필름 생성 완료
 *
 * 문구는 와이어프레임 1.3 그대로다. 와이어프레임은 필름 자리에 이미지 에셋을 넣어
 * 두었으나, 필름 시각 표현은 디자인 미확정(Film motif)이라 와이어프레임이 직접 CSS로
 * 그린 2.2 스트립 사양을 쓰는 FilmStrip 컴포넌트로 대체했다(screen-spec.md 8.4).
 *
 * 프레임 칸의 내용은 온보딩에서 좋아요로 고른 영화다. 온보딩 LIKE/DISLIKE는 프레임을
 * 생성하지 않으므로(API-024 처리 규칙) 실제 프레임이 아니라 표시용이다.
 *
 * `[?]` 필름 영역 터치 시 동작(확대 · 애니메이션 · 이동)은 미확정이다. 와이어프레임의
 * `터치할 수 있어요` 힌트는 그대로 두고 동작은 넣지 않았다.
 * `[?]` `홈으로 이동` 진입점이 와이어프레임에 없어 `필름 늘리기`만 두었다.
 */
import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import FilmStrip from '../../../components/FilmStrip';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import { useOnboarding } from '../../../contexts/onboardingContext';
import { path } from '../../../routes/paths';
import styles from './OnboardingCompletePage.module.css';

export default function OnboardingCompletePage() {
  const navigate = useNavigate();
  const { ottSubmitted, preferences } = useOnboarding();

  // 1.2를 거치지 않고 이 화면을 직접 열면 되돌린다 (frontend-structure.md 6.3)
  useEffect(() => {
    if (!ottSubmitted) {
      void navigate(path.onboardingTaste(), { replace: true });
    }
  }, [ottSubmitted, navigate]);

  const likedIds = preferences
    .filter((preference) => preference.preference === 'LIKE')
    .map((preference) => preference.movieId);

  // 건너뛰었거나 좋아요가 없으면 빈 칸으로 필름 모양만 보여준다
  const frames = likedIds.length > 0 ? likedIds.slice(0, 3) : [0, 0, 0];

  return (
    <>
      <ScreenHeader title="첫 취향 필름이 감겼어요" />

      <div className={styles.page}>
        <div className={styles.stage}>
          {/* 프레임 크기는 와이어프레임 2.3의 필름 칸 실측값(138x196, radius 3)을 쓴다 */}
          <FilmStrip width={190}>
            {frames.map((movieId, index) => (
              <Poster key={`${movieId}-${index}`} width={138} height={196} radius={3} title="" />
            ))}
          </FilmStrip>

          <p className={styles.hint}>터치할 수 있어요</p>
        </div>

        <div className={styles.actions}>
          <Button onClick={() => void navigate(path.recommendations())}>필름 늘리기</Button>
        </div>
      </div>
    </>
  );
}
