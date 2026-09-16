/*
 * 영화 표시 규칙.
 *
 * 2.4 영화 상세와 2.5 영화 평가가 제목 아래에 같은 한 줄(`2013 · 케이퍼 · 115분`)을
 * 적는다(와이어프레임 2.4 · 2.5). API-030은 개봉일 · 상영 시간 · 장르가 없을 수 있어
 * (원천 값이 없으면 `null`, 연결이 없으면 빈 배열) 있는 값만 이어 붙인다.
 */
import type { MovieCode } from '../types/movie';

type MetaSource = {
  releaseDate: string | null;
  runtimeMinutes: number | null;
  genres: MovieCode[];
};

/**
 * 제목 아래 메타 한 줄. 값이 하나도 없으면 빈 문자열이고 화면은 줄을 그리지 않는다.
 *
 * 개봉일은 연도만 쓴다. 계약이 `YYYY-MM-DD`를 주므로 앞 네 자리를 자른다.
 */
export function movieMetaLine({ releaseDate, runtimeMinutes, genres }: MetaSource): string {
  const parts = [
    releaseDate?.slice(0, 4),
    ...genres.map((genre) => genre.name),
    runtimeMinutes === null ? undefined : `${runtimeMinutes}분`,
  ];

  return parts.filter(Boolean).join(' · ');
}
