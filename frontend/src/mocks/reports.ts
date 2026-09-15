/*
 * 취향 리포트 목업. API-059 · API-060 · API-061 (2차 MVP)
 *
 * 값은 와이어프레임 3.3 · 3.4 · 7.3 · 7.4에서 옮겼다. 사람 이름(크리스토퍼 놀란 · 마고 로비 등)은
 * 와이어프레임이 적은 그대로다. 영화 제목은 자리표시자 기준(mocks/movieDetails.ts)을 따르지만
 * 리포트는 영화가 아니라 **사람과 취향**을 말하므로 이름을 바꾸지 않았다.
 *
 * - 7.3은 네 칸을 그린다. 2026 상반기 · 2025 하반기 · 2025 상반기와 흐린 `2026 하반기`다.
 *   마지막은 아직 끝나지 않은 반기라 `GENERATING`으로 둔다(API-059 처리 규칙)
 * - 상세 값은 2026 상반기(301)만 와이어프레임에 있다. 지난 두 리포트는 흐름이 이어지도록
 *   만든 값이다
 * - 키워드 넷은 3.1 `요즘 취향 흐름`과 같다(mocks/taste.ts). 같은 사용자라 두 번 적지 않는다
 *
 * 시각은 정오 기준이다(mocks/frames.ts와 같은 이유).
 *
 * **4컷(`fourCut`)은 사용자 시안(2026-09-15, 업무 -321)의 샘플 데이터다.**
 * - 2026 상반기(301)의 1위 장르 · 감독 · 배우 · 캐릭터를 시안대로 로맨스 · 웨스 앤더슨 · 엠마 스톤 ·
 *   아멜리에로 바꿨다. 4컷과 상세의 목록이 같은 사람을 말해야 해서 목록 1위도 함께 바꿨다
 * - 사진(`images/reports/`)은 시안 이미지에서 잘라 낸 자리표시자다. 사진 권리가 확정되지 않았고
 *   (`components/Poster`) 계약에도 사진이 없어, API 연동 때 지운다
 * - 지난 두 리포트(202 · 201)는 사진이 없다. 사진 없는 4컷 모양을 보여 준다
 */
import actorPortrait from './images/reports/actor-portrait.jpg';
import characterAmelie from './images/reports/character-amelie.jpg';
import directorPortrait from './images/reports/director-portrait.jpg';
import directorWork from './images/reports/director-work.jpg';
import genreRomance from './images/reports/genre-romance.jpg';
import { mockTasteFlow } from './taste';
import type { ReportSummary } from '../types/report';
import type { ReportDetailView } from '../types/unspecified';

export const mockReportList: ReportSummary[] = [
  {
    reportId: 302,
    period: { year: 2026, half: 'H2' },
    status: 'GENERATING',
    title: '2026 하반기 취향 리포트',
    createdAt: '2026-09-01T12:00:00Z',
  },
  {
    reportId: 301,
    period: { year: 2026, half: 'H1' },
    status: 'READY',
    title: '2026 상반기 취향 리포트',
    createdAt: '2026-07-01T12:00:00Z',
  },
  {
    reportId: 202,
    period: { year: 2025, half: 'H2' },
    status: 'READY',
    title: '2025 하반기 취향 리포트',
    createdAt: '2026-01-01T12:00:00Z',
  },
  {
    reportId: 201,
    period: { year: 2025, half: 'H1' },
    status: 'READY',
    title: '2025 상반기 취향 리포트',
    createdAt: '2025-07-01T12:00:00Z',
  },
];

/** 와이어프레임 3.4의 캐릭터 문단 */
const CHARACTER_DESCRIPTION =
  '치밀하게 계획을 세우는 주인공에게 끌려요. 정면 돌파보다 한 수 앞을 내다보는 캐릭터가 나온 영화에 높은 별점을 줬어요.';

export const mockReportDetails: Record<number, ReportDetailView> = {
  // 와이어프레임 3.3 · 3.4 · 7.4
  301: {
    reportId: 301,
    period: { year: 2026, half: 'H1' },
    summary: { ratingCount: 38, newFlavorCount: 1, averageScore: 4.1 },
    topGenres: [
      { code: 'ROMANCE', name: '로맨스', count: 19 },
      { code: 'THRILLER', name: '스릴러', count: 10 },
      { code: 'DRAMA', name: '드라마', count: 9 },
    ],
    topActors: [
      { personId: 9104, name: '엠마 스톤', count: 3 },
      { personId: 9102, name: '라이언 고슬링', count: 2 },
      { personId: 9103, name: '틸다 스윈튼', count: 1 },
    ],
    topDirectors: [
      { personId: 9204, name: '웨스 앤더슨', count: 3 },
      { personId: 9202, name: '봉준호', count: 2 },
      { personId: 9203, name: '드니 빌뇌브', count: 2 },
    ],
    characterCard: {
      name: '아멜리에',
      movieTitle: '영화 제목 2',
      description: CHARACTER_DESCRIPTION,
      imageUrl: characterAmelie,
    },
    flavorChanges: [
      { flavorCode: 'FLAVOR_THRILL', before: 14, after: 26 },
      { flavorCode: 'FLAVOR_NOIR', before: 0, after: 4 },
      { flavorCode: 'FLAVOR_DRAMA', before: 16, after: 10 },
    ],
    createdAt: '2026-07-01T12:00:00Z',
    fourCut: {
      genre: {
        name: '로맨스',
        displayName: 'ROMANCE',
        image: { url: genreRomance, position: { x: 50, y: 40 } },
      },
      director: {
        name: '웨스 앤더슨',
        displayName: 'Wes Anderson',
        image: { url: directorPortrait, position: { x: 50, y: 20 } },
        work: { url: directorWork },
      },
      actor: {
        name: '엠마 스톤',
        displayName: 'Emma Stone',
        image: { url: actorPortrait, position: { x: 60, y: 30 } },
      },
      character: {
        name: '아멜리에',
        displayName: 'Amélie',
        image: { url: characterAmelie, position: { x: 50, y: 30 } },
      },
    },
    genreShares: [
      { label: '로맨스', share: 49 },
      { label: '스릴러', share: 27 },
      { label: '드라마', share: 24 },
    ],
    countryShares: [
      { label: '한국', share: 42 },
      { label: '미국', share: 35 },
      { label: '일본', share: 13 },
    ],
    eraShares: [
      { label: '2010년대', share: 38 },
      { label: '2020년대', share: 34 },
      { label: '그 이전', share: 28 },
    ],
    keywords: mockTasteFlow.keywords,
    comparisons: [
      { label: '범죄·스릴러', delta: 12, isNew: false },
      { label: '느와르 (새로운 맛)', delta: 4, isNew: true },
      { label: '드라마', delta: -6, isNew: false },
    ],
    highlights: [
      '지난 반기보다 스릴러 취향이 12% 증가했어요',
      '새롭게 발견한 감독 4명',
      '새로 넓어진 팝콘 맛: 느와르',
    ],
  },
  202: {
    reportId: 202,
    period: { year: 2025, half: 'H2' },
    summary: { ratingCount: 31, newFlavorCount: 0, averageScore: 4.0 },
    topGenres: [
      { code: 'DRAMA', name: '드라마', count: 11 },
      { code: 'ACTION', name: '액션', count: 10 },
      { code: 'THRILLER', name: '스릴러', count: 5 },
    ],
    topActors: [{ personId: 9102, name: '라이언 고슬링', count: 3 }],
    topDirectors: [{ personId: 9202, name: '봉준호', count: 3 }],
    characterCard: {
      name: '묵묵한 조력자',
      movieTitle: '영화 제목 1',
      description: '앞에 나서기보다 곁에서 판을 짜는 인물이 나온 영화에 오래 머물렀어요.',
      imageUrl: null,
    },
    flavorChanges: [{ flavorCode: 'FLAVOR_DRAMA', before: 10, after: 16 }],
    createdAt: '2026-01-01T12:00:00Z',
    fourCut: {
      genre: { name: '드라마', displayName: 'DRAMA', image: { url: null } },
      director: { name: '봉준호', image: { url: null }, work: null },
      actor: { name: '라이언 고슬링', displayName: 'Ryan Gosling', image: { url: null } },
      character: { name: '묵묵한 조력자', image: { url: null } },
    },
    genreShares: [
      { label: '드라마', share: 34 },
      { label: '액션', share: 24 },
      { label: '스릴러', share: 14 },
    ],
    countryShares: [
      { label: '한국', share: 48 },
      { label: '미국', share: 30 },
      { label: '영국', share: 10 },
    ],
    eraShares: [
      { label: '2010년대', share: 44 },
      { label: '2020년대', share: 30 },
      { label: '그 이전', share: 26 },
    ],
    keywords: mockTasteFlow.keywords.slice(0, 2),
    comparisons: [
      { label: '드라마', delta: 6, isNew: false },
      { label: '액션', delta: -3, isNew: false },
    ],
    highlights: ['지난 반기보다 드라마 취향이 6% 증가했어요', '새롭게 발견한 감독 2명'],
  },
  201: {
    reportId: 201,
    period: { year: 2025, half: 'H1' },
    summary: { ratingCount: 31, newFlavorCount: 2, averageScore: 3.9 },
    topGenres: [
      { code: 'ACTION', name: '액션', count: 12 },
      { code: 'COMEDY', name: '코미디', count: 8 },
      { code: 'DRAMA', name: '드라마', count: 6 },
    ],
    topActors: [{ personId: 9101, name: '마고 로비', count: 2 }],
    topDirectors: [{ personId: 9203, name: '드니 빌뇌브', count: 2 }],
    characterCard: {
      name: '유쾌한 해결사',
      movieTitle: '영화 제목 3',
      description: '위기를 농담으로 넘기는 인물이 나온 영화에 높은 별점을 줬어요.',
      imageUrl: null,
    },
    flavorChanges: [{ flavorCode: 'FLAVOR_COMEDY', before: 2, after: 8 }],
    createdAt: '2025-07-01T12:00:00Z',
    fourCut: {
      genre: { name: '액션', displayName: 'ACTION', image: { url: null } },
      director: {
        name: '드니 빌뇌브',
        displayName: 'Denis Villeneuve',
        image: { url: null },
        work: null,
      },
      actor: { name: '마고 로비', displayName: 'Margot Robbie', image: { url: null } },
      character: { name: '유쾌한 해결사', image: { url: null } },
    },
    genreShares: [
      { label: '액션', share: 32 },
      { label: '코미디', share: 20 },
      { label: '드라마', share: 16 },
    ],
    countryShares: [
      { label: '미국', share: 46 },
      { label: '한국', share: 38 },
      { label: '프랑스', share: 8 },
    ],
    eraShares: [
      { label: '2010년대', share: 40 },
      { label: '그 이전', share: 32 },
      { label: '2020년대', share: 28 },
    ],
    keywords: mockTasteFlow.keywords.slice(1, 3),
    // 첫 리포트라 비교할 지난 반기가 없다
    comparisons: [],
    highlights: ['첫 취향 리포트예요', '새로 넓어진 팝콘 맛 2가지'],
  },
};
