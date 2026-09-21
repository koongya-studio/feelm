/*
 * 리포트 화면 테스트가 함께 쓰는 응답 조각 (업무 S15P21E106-558).
 *
 * 목록 · 상세는 실제 API를 부르므로(API-059 · 060) 화면 테스트마다 응답을 손으로 만들어야
 * 한다. 값은 목업 시절 `mocks/reports.ts`에 있던 와이어프레임 수치를 옮긴 것이다.
 *
 * **필드는 계약 응답 그대로다.** 빠뜨린 필드가 있으면 화면이 아니라 여기가 틀린 것이다.
 * 사진(`image.url`)은 서버가 감독 · 배우 칸에만, 그것도 `people.profile_url`이 있을 때만
 * 주므로 여기서는 늘 null이다. 캐릭터(`characterCard` · `fourCut.character`)도 선정 기준이
 * 업무 -618에서 정해질 때까지 서버가 null로 준다(백엔드 답 2026-09-21).
 */
import type { ReportCurrent, ReportDetail, ReportList } from '../types/report';

/**
 * API-059 응답. 2026 하반기(302)는 아직 만드는 중이라 `items`가 아니라 `current`에만 있다.
 *
 * `current`의 `issuedAt`이 null인 `READY`가 3.1 `새 취향 리포트 받기` 대상이다.
 */
export const reportList: ReportList = {
  items: [
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
  ],
  current: {
    reportId: 302,
    period: { year: 2026, half: 'H2' },
    status: 'GENERATING',
    issuedAt: null,
  },
  preparing: true,
};

/** 2026 상반기가 공개된 최신 반기이고 아직 뽑지 않은 상태 */
const READY_TO_ISSUE: ReportCurrent = {
  reportId: 301,
  period: { year: 2026, half: 'H1' },
  status: 'READY',
  issuedAt: null,
};

/** 3.1이 `새 리포트 받기`를 보여주는 상태 */
export const reportListBeforeIssue: ReportList = {
  ...reportList,
  current: READY_TO_ISSUE,
  preparing: false,
};

/** 3.1이 `리포트 보기`를 보여주는 상태. 최신 반기를 이미 뽑았다 */
export const reportListAfterIssue: ReportList = {
  ...reportList,
  current: { ...READY_TO_ISSUE, issuedAt: '2026-07-01T00:00:14Z' },
  preparing: false,
};

/** API-060 응답. 와이어프레임 3.3 · 3.4 · 7.4는 2026 상반기(301)다 */
export const reportDetails: Record<number, ReportDetail> = {
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
    characterCard: null,
    flavorChanges: [
      { flavorCode: 'FLAVOR_B', before: 14, after: 26 },
      { flavorCode: 'FLAVOR_C', before: 0, after: 4 },
      { flavorCode: 'FLAVOR_F', before: 16, after: 10 },
    ],
    createdAt: '2026-07-01T12:00:00Z',
    fourCut: {
      genre: { name: '로맨스', displayName: null, image: { url: null } },
      director: { name: '웨스 앤더슨', displayName: null, image: { url: null }, work: null },
      actor: { name: '엠마 스톤', displayName: null, image: { url: null } },
      character: null,
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
    keywords: [
      { code: 'DIRECTOR_BINGE', label: '웨스 앤더슨 몰아보기', rationale: '웨스 앤더슨 영화 3편' },
      {
        code: 'FLAVOR_EXPLORER',
        label: '블루베리 탐험가',
        rationale: '이번 반기에 블루베리 프레임 4개 추가',
      },
      { code: 'GENRE_DEVOTEE', label: '로맨스 편식', rationale: '로맨스가 49%' },
      { code: 'GENEROUS_RATER', label: '후한 평가자', rationale: '이번 반기 평균 ★4.3' },
    ],
    // `(새로운 맛)` 접미사는 계약이 아니라 화면이 `isNew`로 붙인다
    comparisons: [
      { label: '다크초코', delta: 12, isNew: false },
      { label: '블루베리', delta: 4, isNew: true },
      { label: '스트로베리', delta: -6, isNew: false },
    ],
    highlights: [
      '지난 반기보다 다크초코 취향이 12% 증가했어요',
      '새롭게 발견한 감독 4명',
      '새로 넓어진 팝콘 맛: 블루베리',
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
    characterCard: null,
    flavorChanges: [{ flavorCode: 'FLAVOR_F', before: 10, after: 16 }],
    createdAt: '2026-01-01T12:00:00Z',
    fourCut: {
      genre: { name: '드라마', displayName: null, image: { url: null } },
      director: { name: '봉준호', displayName: null, image: { url: null }, work: null },
      actor: { name: '라이언 고슬링', displayName: null, image: { url: null } },
      character: null,
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
    // 임계값을 넘긴 규칙이 없으면 빈 배열로 온다. 화면은 이 칸을 숨긴다
    keywords: [],
    comparisons: [
      { label: '솔티카라멜', delta: 6, isNew: false },
      { label: '체다치즈', delta: -3, isNew: false },
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
    characterCard: null,
    flavorChanges: [{ flavorCode: 'FLAVOR_G', before: 2, after: 8 }],
    createdAt: '2025-07-01T12:00:00Z',
    fourCut: {
      genre: { name: '액션', displayName: null, image: { url: null } },
      director: { name: '드니 빌뇌브', displayName: null, image: { url: null }, work: null },
      actor: { name: '마고 로비', displayName: null, image: { url: null } },
      character: null,
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
    keywords: [],
    // 첫 리포트라 비교할 지난 반기가 없다
    comparisons: [],
    highlights: ['첫 취향 리포트예요', '새로 넓어진 팝콘 맛 2가지'],
  },
};
