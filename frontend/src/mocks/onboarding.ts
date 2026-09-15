/*
 * 온보딩 카드 목업. API-023 초기 취향 카드 목록
 *
 * 카드 수는 와이어프레임 1.1의 6장을 따른다. API-023의 `size`는 기본 20 · 최대 40이고
 * 화면에 몇 장을 동시에 노출할지는 미정 사항이다(screen-spec.md 1.1 미확정).
 *
 * `flavorCode`는 계약 필드다. 8개 팝콘 맛이 고루 포함되도록 카드를 구성한다는 처리
 * 규칙(API-023)에 맞춰 서로 다른 맛 코드를 붙였다. 표시명과 색상은 API-044 미정
 * 사항이므로 화면에서는 코드만 쓰고 이름을 하드코딩하지 않는다.
 */
import { findMockMovie } from './movies';
import type { OnboardingCard } from '../types/onboarding';

const cardFlavors: Record<number, string> = {
  301: 'FLAVOR_ACTION',
  302: 'FLAVOR_THRILL',
  303: 'FLAVOR_DRAMA',
  304: 'FLAVOR_CRIME',
  305: 'FLAVOR_COMEDY',
  306: 'FLAVOR_SF',
};

export const mockOnboardingCards: OnboardingCard[] = [301, 302, 303, 304, 305, 306].map(
  (movieId) => {
    const movie = findMockMovie(movieId);

    return {
      movieId: movie.movieId,
      title: movie.title,
      posterUrl: movie.posterUrl,
      releaseYear: movie.releaseYear,
      genres: movie.genres,
      flavorCode: cardFlavors[movieId],
    };
  },
);
