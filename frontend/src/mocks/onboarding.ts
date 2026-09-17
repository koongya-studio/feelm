/*
 * 온보딩 카드 목업. API-023 초기 취향 카드 목록
 *
 * 카드 수는 와이어프레임 1.1의 6장을 따른 과거 목업이다. 현재 화면은 이 파일을 가져오지
 * 않고 API-023에서 확정 목록 10편을 받는다.
 *
 * `flavorCode`는 계약 필드다. 8개 팝콘 맛이 고루 포함되도록 카드를 구성한다는 처리
 * 규칙(API-023)에 맞춰 서로 다른 A~H 맛 코드를 붙였다. 표시명과 색상은 API-044의
 * 확정 기준정보를 사용하며 화면에서는 이름을 하드코딩하지 않는다.
 */
import { findMockMovie } from './movies';
import type { OnboardingCard } from '../types/onboarding';

const cardFlavors: Record<number, string> = {
  301: 'FLAVOR_E',
  302: 'FLAVOR_B',
  303: 'FLAVOR_F',
  304: 'FLAVOR_D',
  305: 'FLAVOR_G',
  306: 'FLAVOR_H',
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
