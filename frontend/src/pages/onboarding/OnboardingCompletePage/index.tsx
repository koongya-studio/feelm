/*
 * 1.3 첫 취향 필름 생성 완료
 *
 * 문구는 와이어프레임 1.3 그대로다. 필름은 **3.1과 같은 릴을 세로로 세운 것**이다
 * (`FilmReel`, 업무 -605 — 사용자 지시 2026-09-18). 그전에는 밝은 판 `FilmStrip`이었는데
 * 3.1의 필름이 확정되고 나서 첫 필름과 취향 필름이 다른 재질로 보였다.
 *
 * 프레임 칸의 내용은 온보딩에서 좋아요로 고른 영화다. 온보딩 LIKE/DISLIKE는 프레임을
 * 생성하지 않으므로(API-024 처리 규칙) 실제 프레임이 아니라 표시용이다.
 *
 * 팀 결정 2026-09-10 (업무 -98)
 * - **필름 칸을 줄였다.** 와이어프레임 실측값 138x196으로 세 칸을 쌓으면 스트립이
 *   652px이 되어 375x812에서 `터치할 수 있어요` 힌트가 화면 밖으로 밀려났다.
 *   비율을 유지하며 힌트까지 들어가는 크기로 맞췄다
 *
 * `[?]` 필름 영역 터치 시 동작(확대 · 애니메이션 · 이동)은 미확정이다. 와이어프레임의
 * `터치할 수 있어요` 힌트는 **뺐다**(업무 -610) — 동작이 없는데 상호작용을 약속하는 문구였다.
 * 동작이 정해지면 문구와 함께 되살린다.
 * `[?]` `홈으로 이동` 진입점이 와이어프레임에 없어 `필름 늘리기`만 두었다.
 */
import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import FilmReel from '../../../components/FilmReel';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import { useOnboarding } from '../../../contexts/onboardingContext';
import { path } from '../../../routes/paths';
import styles from './OnboardingCompletePage.module.css';

/*
 * 필름 칸 크기. **3.1과 같은 값이다**(FilmPage) — 첫 필름과 취향 필름이 같은 릴이어야 한다.
 *
 * 세로로 세 칸이면 릴이 3 × 165 + 간격 10 + 여백 24 = 529px이다. 375 × 812에서 헤더와
 * 주 동작을 뺀 남은 높이가 약 600px이라 `터치할 수 있어요` 힌트까지 들어간다. 와이어프레임
 * 실측값 138 × 196은 이 계산에서 힌트를 화면 밖으로 밀어내 -98에서 이미 줄였던 값이다.
 */
const FRAME_WIDTH = 110;
const FRAME_HEIGHT = 165;

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
          <FilmReel
            orientation="vertical"
            frameWidth={FRAME_WIDTH}
            frameHeight={FRAME_HEIGHT}
            label="첫 취향 필름"
          >
            {frames.map((movieId, index) => (
              <Poster
                key={`${movieId}-${index}`}
                width={FRAME_WIDTH}
                height={FRAME_HEIGHT}
                radius={0}
                title=""
                seed={movieId}
              />
            ))}
          </FilmReel>
        </div>

        <div className={styles.actions}>
          <Button onClick={() => void navigate(path.recommendations())}>필름 늘리기</Button>
        </div>
      </div>
    </>
  );
}
