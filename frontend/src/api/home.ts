/*
 * 2.1 `내 OTT에서 지금 볼 수 있어요` 데이터 접근 함수.
 *
 * 대응하는 API가 없다(CR-14). 계약이 정해지면 이 파일을 지우거나 확정된 도메인
 * 파일로 옮긴다. 화면이 목업을 직접 import하지 않도록 함수 계층은 유지한다.
 */
import { mockAvailableNowCandidates } from '../mocks/home';
import { getMyOtts } from './otts';
import { respond } from './mockTransport';
import type { AvailableNowMovie } from '../types/unspecified';

/**
 * 계약에 없음 (CR-14)
 *
 * 섹션 이름대로 **구독 중인 OTT에서 볼 수 있는 영화만** 내려준다(API-026). 1.2에서
 * 고른 목록이 이 섹션에 그대로 반영되며, 배지도 그 OTT를 가리킨다(업무 -263).
 * 구독 중인 OTT가 없거나 겹치는 영화가 없으면 빈 목록이다.
 */
export async function getAvailableNowMovies(): Promise<AvailableNowMovie[]> {
  const subscribed = await getMyOtts();
  const codes = subscribed.map((ott) => ott.code);

  const items = mockAvailableNowCandidates.flatMap((candidate) => {
    const code = candidate.ottProviders.find((provider) => codes.includes(provider));

    if (!code) {
      return [];
    }

    return [
      {
        movieId: candidate.movieId,
        title: candidate.title,
        posterUrl: candidate.posterUrl,
        ottProvider: code,
      },
    ];
  });

  return respond(items);
}
