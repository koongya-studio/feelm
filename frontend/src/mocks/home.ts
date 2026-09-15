/*
 * 2.1 `내 OTT에서 지금 볼 수 있어요` 목업.
 *
 * 이 섹션은 대응하는 API가 없다(CR-14). 응답 형태 자체가 미정이므로 계약 도메인
 * 목업 파일에 두지 않고 이 파일에 분리했다. CR-14가 결정되면 이 파일을 지우거나
 * 확정된 API의 목업으로 옮긴다.
 *
 * 4편과 각 OTT 배지는 와이어프레임 2.1 그대로다(N · T · W · D).
 *
 * 섹션 이름이 `내 OTT에서`이므로 실제로 보여줄 목록은 구독 중인 OTT에 따라 달라진다.
 * 여기에는 후보 4편과 각 영화가 올라가 있는 OTT를 두고, 걸러내는 일은 api/home.ts가
 * 한다(업무 -263).
 */
import { findMockMovie } from './movies';

export const mockAvailableNowCandidates = [201, 202, 203, 204].map((movieId) => {
  const movie = findMockMovie(movieId);

  return {
    movieId: movie.movieId,
    title: movie.title,
    posterUrl: movie.posterUrl,
    /** 이 영화를 볼 수 있는 OTT 코드 */
    ottProviders: movie.ottProviders,
  };
});
