/*
 * 7.1 MY 대시보드
 *
 * 와이어프레임에서 가져온 것은 **기능과 정보**다 — 프로필, 취향 리포트 · 추천 기록 ·
 * 평가 · 설정. 배치는 가져오지 않았다(screen-spec-my.md 1장). `OTT 관리`는 7.8 설정
 * 안으로 옮겼다(사용자 지시 2026-09-16).
 *
 * 배치는 **사용자 시안**(인스타그램형 프로필 · 피드, 2026-09-16 업무 -459)을 따르고, 위쪽
 * 프로필은 2026-09-18 시안으로 바꿨다(업무 -616). **프로필 머리 · 수치는 7.2 · 7.2-A · 7.2-B와
 * 같은 `ProfileLayout`(`mode="view"`)이 그린다** — 네 화면의 프로필이 따로 놀지 않는다.
 *
 * | 자리 | 무엇 |
 * | --- | --- |
 * | 헤더 | 공통 헤더(워드마크 · 검색 · 알림, 업무 -676) · 그 아래 `프로필` · 오른쪽 `설정` 아이콘 |
 * | 상단 | 동그란 프로필 사진 + 닉네임 + 소개 |
 * | 버튼 둘 | `프로필 편집`(→ 7.2) · `프로필 공유` |
 * | 수치 | 프레임 · 팝콘 · 공개 한줄평 |
 * | 바로가기 | `4컷 리포트`(→ 7.3) · `취향 팝콘`(→ 3.2) |
 * | 탭 | `평가한 영화` · `최근 본 영화` · `추천 기록`. **화면 안에서 아래 피드만 바꾼다** |
 * | 본문 | 고른 탭의 포스터 3열 피드 |
 *
 * **피드는 각 탭의 실제 기록이다.** 평가는 API-039(`getMyRatings`), 최근 본 영화는 같은
 * API-039를 `filter=ALL`로 부른 것, 추천은 API-048(`getRecommendationHistory`)이다.
 *
 * **`평가한 영화`와 `최근 본 영화`는 겹친다**(업무 -919). 평가한 영화가 둘 다에 나온다. 보는 것이
 * 달라 그대로 둔다 — 평가한 영화는 별점과 한줄평이 있는 내 평가만이고, 최근 본 영화는 거기에
 * 아직 평가하지 않은 영화까지 합쳐 본 순서로 세운 것이다.
 * 취향 필름(API-042)은 **부르지 않는다** — 프레임을 평가 · 추천 기록인 것처럼 쓸 수 없다.
 * 필름은 하단 내비 `필름` 탭(3.1)이 보여준다.
 *
 * **탭은 이동이 아니다.** 이 화면 안에서 목록만 바뀐다. 세 목록을 처음에 함께 읽어 두므로
 * 탭을 눌렀을 때 다시 기다리지 않는다.
 *
 * 고른 탭은 주소의 `?tab=`에 남는다. 화면 밖에서 특정 탭으로 보내야 하는 자리가 있기
 * 때문이다 — EXT-05 OTT 분석 데이터 부족의 `영화 평가하러 가기`가 이 탭으로 온다.
 *
 * `?tab=`의 값은 `unrated` 그대로다(업무 -919). 이름과 내용이 바뀌었지만 값을 바꾸면 위 진입점과
 * 밖에 남은 주소가 끊긴다. 카드를 누르면 2.5 평가하기로 가는 것도 그대로라, 그 화면에서 평가한
 * 영화는 수정이 된다(API-037).
 *
 * **세 탭 모두 실제 호출이다.** 추천 기록은 API-048(업무 S15P21E106-478), 최근 본 영화는
 * API-034(업무 -477), 평가한 영화는 API-039(업무 -611)다. 평가한 영화는 그전까지 평가와
 * 1:1인 내 필름(API-042)으로 대신 그렸다(업무 -610). API-039는 **프레임 없는 과거 평가도**
 * 돌려주므로 필름의 프레임 수보다 많을 수 있다.
 *
 * **세 탭 모두 `더 보기`로 다음 장을 이어 받는다**(업무 -478 · -611 · -503). 한 장은 18편이고
 * (업무 -657 — 3열 격자 여섯 줄. API 기본값 20이면 줄마다 두 칸이 남아 끊겨 보였다)
 * 그보다 오래된 기록은 `nextCursor`로 온다 — 버튼이 없으면 첫 장 뒤의 기록에 닿을 길이 없다.
 * 무한 스크롤로 하지 않은 것은 피드가 끝나는 자리가 보여야 해서다. 탭이 셋이고 탭마다 끝이
 * 다르므로, 스크롤만으로 이어 받으면 지금 탭이 끝났는지 알 길이 없다. 셋 다 같은 규칙이다 —
 * 실패하면 보던 격자는 두고 버튼만 `다시 시도`로 바꾼다.
 *
 * **수치 카드가 다시 있다**(사용자 시안 2026-09-18, 업무 -616). 2026-09-16에는 뺐지만 새
 * 시안이 프레임 · 팝콘 · 공개 한줄평 카드를 그렸다. `ProfileLayout`이 그린다.
 *
 * **어두운 블록은 없다.** 4장 규칙은 `화면마다 어두운 블록은 하나다`이고 표는 7.2에
 * `없음`을 적는다 — 최대 하나이지 반드시 하나가 아니다.
 *
 * **벽은 앱 공통 벽 그대로다.** 7.1만 다른 배경을 쓰지 않는다. 한때 이 화면만 흰 벽으로
 * 만들었다가 되돌렸고, 공통 벽 자체를 다른 색으로 바꿨다가도 되돌렸다(2026-09-16).
 * 화면은 배경을 칠하지 않는다.
 *
 * **`프로필 공유`는 계약이 아니라 브라우저가 한다**(사용자 지시 2026-09-16). 공유 API가
 * 없으므로 서버를 부르지 않고, 7.2-B 공개 프로필 주소를 기기의 공유
 * 메뉴(Web Share API)에 넘긴다. 없는 브라우저에서는 주소를 복사한다. 3.5 리포트 공유가
 * 쓰는 방법과 같다.
 * `[!]` 공개 프로필 주소는 `publicId`(UUID)로 연다(업무 -810). 그런데 내 `publicId`를 알려
 * 주는 API가 아직 없어, 지금 공유하는 주소는 숫자 ID라 받는 사람에게 `프로필을 볼 수 없어요`로
 * 열린다. API-014가 `publicId`를 주면 그 값으로 바꾼다.
 *
 * 프로필 사진은 API-014의 `avatarUrl`이다(업무 -762). **소개는 계약에 없다**(업무 -616) — 7.2에서
 * 바꿀 수 있지만 값은 목업 저장소에만 남는다(`api/users.ts` `getMyProfileView`). 해시태그처럼
 * 시안에도 계약에도 없는 값은 넣지 않았다.
 *
 * **7.5 추천 기록 · 7.6 평가한 영화 · 7.7 평가하지 않은 영화 화면은 지웠다**(업무 -495).
 * 세 화면이 하던 일을 이 피드가 그대로 한다. 7.6-A 한줄평 관리 시트는 3.1-A 프레임
 * 상세에서 열리므로 남는다(screen-spec-my.md 11장).
 */
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import PopcornBucket from '../../../components/PopcornBucket';
import AppHeader from '../../../components/AppHeader';
import DesktopAside from '../../../components/DesktopAside';
import ProfileBackdrop from '../../../components/ProfileBackdrop';
import ProfileLayout, { ProfileSkeleton } from '../../../components/ProfileLayout';
import { kernelCounts } from '../../../components/PopcornBucket/kernels';
import Poster from '../../../components/Poster';
import PosterCue from '../../../components/PosterCue';
import ReportFourCut from '../../../components/ReportFourCut';
import ReportTape from '../../../components/ReportTape';
import ScreenHeader from '../../../components/ScreenHeader';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { ChevronRightIcon, SettingsIcon } from '../../../components/Icon';
import MyAside from './MyAside';
import ServiceFeedbackPrompt from './ServiceFeedbackPrompt';
import { useShellSize } from '../../../hooks/useShellSize';
import { getMyTaste } from '../../../api/taste';
import { getRecommendationHistory } from '../../../api/recommendations';
import { getMyReports, getReportDetail } from '../../../api/reports';
import { getPopcornBucket } from '../../../api/taste';
import { getMyProfileView } from '../../../api/users';
import { retryTransient } from '../../../api/retry';
import { getMyRatings } from '../../../api/watch';
import { path } from '../../../routes/paths';
import { appUrl } from '../../../utils/appUrl';
import { shareLink } from '../../../utils/share';
import { formatDate, formatShortDate } from '../../../utils/date';
import styles from './MyDashboardPage.module.css';
import type { FlavorCount } from '../../../components/PopcornBucket/kernels';
import type { RecommendationType } from '../../../types/enums';
import type { RecommendationHistoryItem } from '../../../types/recommendation';
// 같은 이름의 통 그림 컴포넌트가 이미 있어 타입은 이름을 바꿔 들여온다
import type { PopcornBucket as PopcornBucketData } from '../../../types/taste';
import type { ReportDetail, ReportSummary } from '../../../types/report';
import type { TasteAnalysis } from '../../../types/taste';
import type { ProfileView } from '../../../types/unspecified';
import type { MyRatedItem, MyRatingList, MyRatingListItem } from '../../../types/watch';

/** 포스터 비율 2:3. 격자는 칸 폭이 화면에 따라 달라지므로 비율만 고정한다 */
const POSTER_RATIO = { width: 2, height: 3 };

/** API-040이 키워드를 만드는 최소 평가 편수. 계약 처리 규칙과 같은 값이다 */
const MINIMUM_RATINGS_FOR_KEYWORDS = 15;

/**
 * 키워드가 없을 때 무엇을 말할지.
 *
 * **두 경우가 다르다.** 계약은 `insufficientData`를 평가 수가 아니라 키워드 공백으로 정하므로
 * (API-040), 평가가 넉넉한데 규칙을 못 넘겨 비는 사용자가 있다. 그 사람에게 `평가가 부족해요`라고
 * 하면 화면에 보이는 평가 수와 어긋난다.
 */
function emptyTasteText(ratingCount: number): string {
  const left = MINIMUM_RATINGS_FOR_KEYWORDS - ratingCount;

  return left > 0
    ? `${left}개만 더 평가하면 취향 키워드가 생겨요`
    : '아직 뚜렷한 취향이 잡히지 않았어요. 더 평가하면 키워드가 생겨요';
}

const TABS = [
  { key: 'ratings', label: '평가한 영화' },
  { key: 'unrated', label: '최근 본 영화' },
  { key: 'recommendations', label: '추천 기록' },
] as const;

type TabKey = (typeof TABS)[number]['key'];

/** 탭마다 빈 목록일 때의 문구. 7.6 · 7.7이 쓰던 말을 줄여서 옮겼다 */
const EMPTY_TEXT: Record<TabKey, string> = {
  ratings: '아직 평가한 영화가 없어요',
  unrated: '아직 본 영화가 없어요',
  recommendations: '아직 추천받은 기록이 없어요',
};

/** 7.5의 출처 문구를 그대로 쓴다. 계약의 `type` 세 값과 하나씩 맞는다 */
const SOURCE_LABEL: Record<RecommendationType, string> = {
  TASTE: '취향 맞춤',
  DISCOVERY: '새로운 맛 발견',
  PARTY: '파티 추천',
};

/**
 * 피드 하나의 상태. **셋을 구분한다** — 읽는 중 · 실패 · 빈 목록.
 *
 * 빈 목록을 가짜 포스터로 채우지 않는다. 실패도 빈 목록으로 뭉개지 않는다 — 다시
 * 시도할 수 있어야 하기 때문이다.
 */
type Feed<T> = { status: 'loading' } | { status: 'failed' } | { status: 'ready'; items: T[] };

/** `더 보기` 한 번의 상태. 실패해도 이미 받은 목록은 남는다 */
type MoreState = 'idle' | 'loading' | 'failed';

/**
 * 평가만 남긴다. 필터를 생략하면 서버가 평가만 주지만(API-039), 응답 모양은 미평가 항목도
 * 담을 수 있어 타입을 좁혀 둔다 — 격자가 `score`를 믿고 그린다.
 */
function ratedItems(list: MyRatingList): MyRatedItem[] {
  return list.items.filter((item): item is MyRatedItem => item.itemType === 'RATED');
}

/** 추천 세트를 한 줄씩 편다. 세트의 `generatedAt`이 그 줄의 날짜다 */
type HistoryRow = { item: RecommendationHistoryItem; generatedAt: string };

/**
 * 최근 본 영화 한 칸. 감상 기록(API-034)에 OTT 이름과 날짜를 얹은 것이다.
 *
 * 기록은 `ottId`만 주고 이름은 기준정보(API-025)에 있다. 7.7이 쓰던 방식 그대로다.
 *
 * **둘 다 없을 수 있다.** 클릭 없이 평가로 만들어진 기록은 `ott`도 시각도 `null`이다
 * (업무 -477). 그런 칸은 OTT 이름과 날짜 없이 그린다.
 */
type WatchedRow = {
  item: MyRatingListItem;
  /** 평가한 때 또는 봤다고 확인한 때. 적는 형식은 폭에 따라 다르므로 그릴 때 정한다(업무 -786) */
  watchedOn: string | null;
};

/*
 * 별 하나. `RatingStars`와 같은 모양이지만 그 컴포넌트는 별 다섯 개와 숫자를 함께
 * 그려 포스터 위 작은 표식으로 쓸 수 없다. 경로만 같이 쓴다.
 */
const STAR_PATH =
  'M10 1.5l2.6 5.3 5.9.9-4.2 4.1 1 5.8L10 14.9l-5.3 2.7 1-5.8L1.5 7.7l5.9-.9L10 1.5z';

/*
 * 바로가기의 4컷 리포트 그림.
 *
 * **사용자가 만든 리포트 디자인 그대로다**(`ReportFourCut`, 업무 -321 · -446). 3.4 · 7.4
 * 상세와 7.3 목록이 쓰는 같은 컴포넌트를 7.3 목록처럼 머리 없이 작게 쓴다. 손으로 그린
 * 필름 조각을 여기 두었다가 바꿨다(사용자 지시 2026-09-16) — 리포트 모양은 사용자가
 * 만드는 것이라 흉내 낸 그림을 둘 자리가 아니다.
 *
 * **가장 최근에 인화가 끝난 리포트 한 장을 그린다.** 없거나 못 불러오면 7.3과 같은
 * 빈 인화지다. 칸을 지어내지 않는다.
 *
 * 위에 **빨간 테이프**를 붙인다. 7.3이 인화지를 벽에 붙여 둔 모양 그대로다
 * (`components/ReportTape`, 사용자 지시 2026-09-16).
 */
function useLatestReport(): { detail: ReportDetail | null; summaries: ReportSummary[] } {
  const [detail, setDetail] = useState<ReportDetail | null>(null);
  /* 넓은 화면의 `최근 활동`이 같은 응답을 쓴다. 리포트 목록을 두 번 부르지 않는다(업무 -786) */
  const [summaries, setSummaries] = useState<ReportSummary[]>([]);

  useEffect(() => {
    let active = true;

    getMyReports()
      .then(({ items }) => {
        if (active) setSummaries(items);

        // 최신순으로 오므로 먼저 만나는 READY가 가장 최근 리포트다
        const latest = items.find((item) => item.status === 'READY');

        return latest ? getReportDetail(latest.reportId) : null;
      })
      .then((value) => {
        if (active) setDetail(value);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  return { detail, summaries };
}

/*
 * 바로가기의 팝콘 통.
 *
 * **실제 맛 분포대로 알을 담는다**(API-041, 3.2 취향 팝콘과 같은 함수). 못 불러오거나
 * 기준정보가 아직 없으면 **빈 통**이다 — 아무 맛이나 쌓으면 없는 취향을 지어내는 것이 된다.
 */
function useMyPopcorn(): { flavors: FlavorCount[]; bucket: PopcornBucketData | null } {
  /* 넓은 화면의 `팝콘 분포도`가 맛별 수치를 그대로 쓰므로 응답을 통째로 둔다(업무 -786) */
  const [bucket, setBucket] = useState<PopcornBucketData | null>(null);

  useEffect(() => {
    let active = true;

    getPopcornBucket()
      .then((value) => {
        if (active) setBucket(value);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  return { flavors: bucket ? kernelCounts(bucket.flavors) : [], bucket };
}

/** 세트 목록을 최신순 그대로 한 줄씩 편다 */
function flatten(
  sets: { generatedAt: string; items: RecommendationHistoryItem[] }[],
): HistoryRow[] {
  return sets.flatMap((set) => set.items.map((item) => ({ item, generatedAt: set.generatedAt })));
}

/**
 * 한 장의 편 수. 격자가 3열이라 3의 배수여야 첫 장과 `더 보기`마다 줄이 꽉 찬다(업무 -657).
 * API 기본값 20이면 매번 마지막 줄에 두 칸만 남았다. API-039 · API-034 모두 1~50을 받는다
 */
const FEED_PAGE_SIZE = 18;

/**
 * 추천 기록 한 장의 세트 수. API-048의 `size`는 편이 아니라 **세트 단위**다. 세트 하나가
 * 대개 3편(취향 맞춤 2 + 취향 발견 1)이라 여섯 세트로 다른 탭과 같은 18편을 맞춘다.
 * 세트에 3편이 다 차지 않으면 줄이 딱 맞지 않을 수 있다
 */
const HISTORY_SETS_PER_PAGE = FEED_PAGE_SIZE / 3;

/**
 * 최근 본 영화의 조건. 다음 장도 **같은 조건으로** 받아야 커서가 맞는다(API-039).
 *
 * 업무 -919에서 **감상 기록(API-034)에서 내 평가 목록(API-039)의 `filter=ALL`로 바꿨다.**
 * 탭이 `안 남긴 영화`였을 때는 평가 안 한 것만 보이면 됐지만, `최근 본 영화`가 되면서 평가한
 * 영화도 함께 보여야 한다. API-034로 둘을 함께 받을 수는 있어도 **순서가 맞지 않는다** —
 * 그쪽은 `clickedAt` 내림차순이라, OTT 링크를 누르지 않고 바로 평가한 기록은 그 시각이 없어
 * 맨 뒤로 밀린다. 방금 평가한 영화가 목록 끝에 서게 된다.
 *
 * `filter=ALL`은 서버가 평가와 미평가를 한 페이지에서 `activityAt` 내림차순으로 정렬해 준다.
 * 그 시각은 평가한 항목이면 평가한 때, 미평가면 봤다고 확인한 때다. 같은 영화가 두 번 나오지도
 * 않는다. 화면에서 두 목록을 합치는 방법도 있지만 한 장 안에서만 맞고 `더 보기`에서 어긋난다.
 */
function watchedQuery(unratedOnly: boolean) {
  return { filter: unratedOnly ? ('UNRATED' as const) : ('ALL' as const), size: FEED_PAGE_SIZE };
}

/** `평가 안 한 것만` 을 켠 상태를 주소에 남기는 이름 */
const UNRATED_ONLY_PARAM = 'unratedOnly';

/**
 * 목록 한 칸. 서버가 정렬에 쓴 시각(`activityAt`)을 그대로 적는다 — 보이는 날짜와 줄 세운
 * 순서가 같아야 한다.
 *
 * **날짜에 형식을 입히지 않고 그대로 둔다.** 폭에 따라 적는 방식이 달라지는데(업무 -786)
 * 받아 둘 때 정해 버리면 폭이 바뀔 때마다 목록을 다시 받아야 한다.
 */
function toWatchedRow(item: MyRatingListItem): WatchedRow {
  return { item, watchedOn: item.activityAt };
}

/** 받은 장을 이미 그린 격자 뒤에 붙인다. 격자가 없으면(실패 · 읽는 중) 그대로 둔다 */
function append<T>(items: T[]) {
  return (prev: Feed<T>): Feed<T> =>
    prev.status === 'ready' ? { status: 'ready', items: [...prev.items, ...items] } : prev;
}

/** 다음 장 커서. `hasNext`가 거짓이면 커서가 와도 버린다 */
function nextCursor(page: { nextCursor: string | null; hasNext: boolean }): string | null {
  return page.hasNext ? page.nextCursor : null;
}

/** 탭마다 다음 장 커서. 처음에는 셋 다 없다 */
const NO_CURSORS: Record<TabKey, string | null> = {
  ratings: null,
  unrated: null,
  recommendations: null,
};

const IDLE_MORE: Record<TabKey, MoreState> = {
  ratings: 'idle',
  unrated: 'idle',
  recommendations: 'idle',
};

/** 다음 장을 못 받았을 때 버튼 아래 한 줄 */
const MORE_ERROR: Record<TabKey, string> = {
  ratings: '지난 평가를 불러오지 못했어요.',
  unrated: '지난 기록을 불러오지 못했어요.',
  recommendations: '지난 추천을 불러오지 못했어요.',
};

export default function MyDashboardPage() {
  /*
   * ─────────────────────────── 데스크톱 시안 (업무 -786)
   *
   * 1024px 이상에서는 2026-09-21 PC 시안의 배치를 쓴다. **412px 이하 화면은 그대로다** —
   * 폭에 따라 그리는 것 자체가 갈린다(`hooks/useShellSize`, 업무 -782).
   */
  const desktop = useShellSize() !== 'mobile';
  const [profile, setProfile] = useState<ProfileView | null>(null);

  /**
   * 취향 키워드(API-040). 서버가 못 주면 `null`로 두고 구역을 그리지 않는다 — 키워드를 못
   * 받았다고 프로필과 피드까지 오류로 만들 이유가 없다.
   *
   * 프로필(`getMyProfileView`)도 같은 키워드를 싣지만 그쪽은 공개 여부를 걸러 쓰는 쪽(7.2 ·
   * 7.2-A · 7.2-B)을 위한 것이다. **내 대시보드는 비공개로 둔 것까지 내 키워드를 다 보여준다.**
   * 남은 개수를 말하려면 `ratingCount`도 필요해 여기서 따로 부른다. 요청은 프로필과 함께
   * 한 번만 보낸다(아래 프로필 효과).
   */
  const [taste, setTaste] = useState<TasteAnalysis | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  /*
   * 고른 탭은 주소에 남긴다(`?tab=`). 화면 밖에서 특정 탭으로 보내는 자리가 있어서다
   * (EXT-05 → `최근 본 영화`). 모르는 값이 오면 기본 탭이다.
   */
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab: TabKey = TABS.some((item) => item.key === requested)
    ? (requested as TabKey)
    : 'ratings';

  /*
   * `최근 본 영화` 안에서 아직 평가하지 않은 것만 보는 스위치(업무 -919).
   *
   * **처음 값만 주소에서 읽고 그 뒤로는 화면이 들고 있는다.** 밖에서 켠 채로 보낼 수 있어야
   * 해서 주소를 읽지만(EXT-05의 `영화 평가하러 가기`는 평가할 영화를 찾아가라는 뜻이다),
   * 누를 때마다 주소를 고치면 **스크롤이 맨 위로 튄다.** `useScrollRestoration`이 주소가
   * 바뀌는 것을 화면을 새로 연 것으로 보고 본문을 되감기 때문이다. 탭을 누를 때는 그것이 맞지만
   * 같은 탭 안에서 목록만 거르는 것은 다르다. 그 훅은 모든 화면이 쓰므로 건드리지 않는다.
   */
  const [unratedOnly, setUnratedOnly] = useState(() => params.get(UNRATED_ONLY_PARAM) === 'true');

  const [ratings, setRatings] = useState<Feed<MyRatedItem>>({ status: 'loading' });
  const [unrated, setUnrated] = useState<Feed<WatchedRow>>({ status: 'loading' });
  const [history, setHistory] = useState<Feed<HistoryRow>>({ status: 'loading' });
  /** 탭마다 다음 장 커서. 더 받을 것이 없으면 `null`이다(각 API의 `hasNext`) */
  const [cursors, setCursors] = useState(NO_CURSORS);
  const [more, setMore] = useState(IDLE_MORE);
  /** 최근 본 영화의 OTT 이름표. 다음 장에도 같은 기준정보를 쓴다 */
  const [feedReload, setFeedReload] = useState(0);
  const [notice, setNotice] = useState('');

  const { detail: report, summaries: reportSummaries } = useLatestReport();
  const { flavors: popcorn, bucket } = useMyPopcorn();

  /*
   * 프로필은 일시 오류면 짧게 기다렸다 다시 부른다(업무 S15P21E106-893). 모바일에서 처음
   * 들어올 때 재발급 · 네트워크가 잠깐 실패해 오류 화면이 떴다가, 탭을 다시 열면 멀쩡했다.
   * 다시 부르는 동안은 뼈대를 그대로 두고, 횟수를 다 써도 실패하면 그때 오류 화면이다.
   * 어떤 실패를 다시 부르는지는 `api/retry.ts`가 정한다 — 영구적인 4xx는 곧바로 오류다.
   *
   * 취향 분석(API-040)은 한 번만 부르고 프로필이 그 응답을 같이 쓴다. 프로필을 다시 불러도
   * 취향 분석은 다시 보내지 않는다. 화면을 떠나면 재시도를 멈추고 늦게 온 응답은 버린다.
   */
  useEffect(() => {
    const controller = new AbortController();
    const active = () => !controller.signal.aborted;
    const tasteRequest = getMyTaste().catch(() => null);

    void tasteRequest.then((value) => {
      if (active() && value) setTaste(value);
    });

    retryTransient(() => getMyProfileView(tasteRequest), { signal: controller.signal })
      .then((value) => {
        if (active()) {
          setProfile(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active()) {
          setFailed(true);
        }
      });

    return () => {
      controller.abort();
    };
  }, [reloadCount]);

  /*
   * 두 피드를 함께 읽는다. 탭은 이동이 아니라 화면 안의 전환이라, 눌렀을 때 다시
   * 기다리지 않아야 한다. 하나가 실패해도 다른 하나는 그려진다.
   *
   * 다시 읽기 전에 `loading`으로 되돌리는 것은 `retryFeeds`가 한다. 여기서 하면 효과
   * 안에서 상태를 바꾸게 된다.
   */
  useEffect(() => {
    let active = true;

    /*
     * 평가한 영화는 내 평가 목록(API-039)이다(업무 -611). 필터를 생략하면 서버가 평가만
     * 최신순으로 준다. 2.5에서 방금 저장 · 수정한 평가도 다시 읽을 때 그대로 보인다.
     */
    getMyRatings({ size: FEED_PAGE_SIZE })
      .then((value) => {
        if (!active) return;

        setRatings({ status: 'ready', items: ratedItems(value) });
        setCursors((prev) => ({ ...prev, ratings: nextCursor(value) }));
      })
      .catch(() => {
        if (active) setRatings({ status: 'failed' });
      });

    getRecommendationHistory(undefined, HISTORY_SETS_PER_PAGE)
      .then((value) => {
        if (!active) return;

        setHistory({ status: 'ready', items: flatten(value.items) });
        setCursors((prev) => ({ ...prev, recommendations: nextCursor(value) }));
      })
      .catch(() => {
        if (active) setHistory({ status: 'failed' });
      });

    return () => {
      active = false;
    };
  }, [feedReload]);

  /*
   * 최근 본 영화만 따로 읽는다. 거르개를 누르면 이 목록만 다시 받고 **다른 두 탭은 건드리지
   * 않는다** — 한 효과에 묶어 두면 거르개 한 번에 세 API를 다시 부른다.
   *
   * 평가와 미평가를 서버가 합쳐 정렬해 준다(API-039 `filter=ALL`). 거르개를 켜면 `UNRATED`다.
   * 커서는 조회 조건에 묶이므로 조건이 바뀌면 첫 장부터 받는다.
   */
  useEffect(() => {
    let active = true;

    getMyRatings(watchedQuery(unratedOnly))
      .then((records) => {
        if (!active) return;

        setUnrated({ status: 'ready', items: records.items.map(toWatchedRow) });
        setCursors((prev) => ({ ...prev, unrated: nextCursor(records) }));
      })
      .catch(() => {
        if (active) setUnrated({ status: 'failed' });
      });

    return () => {
      active = false;
    };
  }, [feedReload, unratedOnly]);

  if (failed) {
    return (
      <>
        <AppHeader />
        <ScreenHeader title="프로필" flush />

        <StatusView title="프로필을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  // 받는 동안도 사진 배경 틀이다. 사진을 모르므로 사진 없는 어두운 배경에 자리만 비워 둔다 —
  // 받는 순간 밝은 벽에서 어두운 화면으로 바뀌면 번쩍인다
  if (!profile) {
    return (
      <ProfileBackdrop
        photoUrl={null}
        top={
          <>
            <AppHeader />
            <ScreenHeader title="프로필" flush tone="dark" />
          </>
        }
      >
        <div className={styles.page}>
          <ProfileSkeleton tone="dark" />
        </div>
      </ProfileBackdrop>
    );
  }

  const feed = tab === 'ratings' ? ratings : tab === 'unrated' ? unrated : history;

  /*
   * 7.2-B 공개 프로필 주소를 공유한다. 공유 API가 계약에 없으므로 서버를 부르지 않는다.
   * 휴대폰은 기기의 공유 메뉴, 데스크톱은 주소 복사다(`utils/share.ts`, 업무 -767 — 한때
   * 데스크톱 Chrome의 `navigator.share`를 믿어 아무 반응이 없었다).
   *
   * **주소는 `appUrl`로 만든다**(업무 -716 다음의 -717). `path.*`는 라우터 안에서 쓰는
   * 경로라 앱이 놓인 자리(`/app`)가 빠져 있다. `location.origin`에 그대로 붙이면 받는
   * 사람이 404를 본다.
   */
  const shareProfile = async () => {
    const result = await shareLink({
      title: `${profile.nickname}님의 Feelm 프로필`,
      // [!] 머리말 참고. publicId가 생기면 바꾼다
      url: appUrl(path.publicProfile(String(profile.userId))),
    });

    if (result === 'copied') setNotice('프로필 주소를 복사했어요');
    if (result === 'failed') setNotice('이 브라우저에서는 공유할 수 없어요');
  };

  /*
   * 지금 탭의 다음 장을 이어 받는다(업무 -478 · -611 · -503). 셋 다 같은 규칙이다 — 첫 장과
   * **같은 조건**에 커서만 실어 보내고(조건이 바뀐 커서는 `400`), 실패해도 이미 그린 격자는
   * 두고 버튼만 `다시 시도`로 바꾼다. 여기서 목록을 비우면 보고 있던 기록이 통째로 사라진다
   * (4.2 검색이 쓰는 처리와 같다).
   */
  function loadMore() {
    const current = tab;
    const cursor = cursors[current];

    if (!cursor || more[current] === 'loading') {
      return;
    }

    setMore((prev) => ({ ...prev, [current]: 'loading' }));

    const done = (next: string | null) => {
      setMore((prev) => ({ ...prev, [current]: 'idle' }));
      setCursors((prev) => ({ ...prev, [current]: next }));
    };
    const fail = () => setMore((prev) => ({ ...prev, [current]: 'failed' }));

    if (current === 'ratings') {
      getMyRatings({ cursor, size: FEED_PAGE_SIZE })
        .then((page) => {
          done(nextCursor(page));
          setRatings(append(ratedItems(page)));
        })
        .catch(fail);

      return;
    }

    if (current === 'unrated') {
      getMyRatings({ ...watchedQuery(unratedOnly), cursor })
        .then((page) => {
          done(nextCursor(page));
          setUnrated(append(page.items.map(toWatchedRow)));
        })
        .catch(fail);

      return;
    }

    getRecommendationHistory(cursor, HISTORY_SETS_PER_PAGE)
      .then((page) => {
        done(nextCursor(page));
        setHistory(append(flatten(page.items)));
      })
      .catch(fail);
  }

  function retryFeeds() {
    setCursors(NO_CURSORS);
    setMore(IDLE_MORE);
    setRatings({ status: 'loading' });
    setUnrated({ status: 'loading' });
    setHistory({ status: 'loading' });
    setFeedReload((count) => count + 1);
  }

  /*
   * 프로필 · 바로가기 · 탭 · 피드. **두 껍데기가 같은 것을 담는다** — 좁은 화면은 사진 배경
   * 틀 안에, 넓은 화면은 데스크톱 셸의 중앙 단에 그대로 놓인다. 갈리는 것은 껍데기와 면의
   * 밝기뿐이라 본문을 두 벌로 두지 않는다(업무 -786).
   */
  const body = (
    <div className={desktop ? styles.desktopPage : styles.page}>
      {/*
       * 넓은 화면의 제목 줄. 좁은 화면은 사진 배경 틀 위의 `ScreenHeader`가 같은 일을 한다.
       * 데스크톱 설정은 좌측 내비에 있으므로 본문에는 중복 진입점을 두지 않는다.
       */}
      {desktop ? (
        <header className={styles.desktopHead}>
          <h1 className={styles.desktopTitle}>프로필</h1>
        </header>
      ) : null}

      {/*
       * 프로필 머리 · 버튼 둘 · 수치. `프로필 편집`은 7.2로 가는 입구이고(이동 경로 2 · 3번
       * 사슬의 출발점), `프로필 공유`는 기기의 공유 메뉴를 연다.
       */}
      <ProfileLayout
        mode="view"
        tone={desktop ? 'light' : 'dark'}
        profile={profile}
        actions={
          <>
            <Link to={path.profileEdit()} className={styles.profileAction}>
              프로필 편집
            </Link>

            <button
              type="button"
              className={styles.profileAction}
              onClick={() => void shareProfile()}
            >
              프로필 공유
            </button>
          </>
        }
      />

      {/*
       * 바로가기 카드 둘. 그림 · 이름 · 화살표가 한 줄이고 카드 전체가 링크다(시안 7.1).
       * 왼쪽은 7.3 취향 리포트 목록, 오른쪽은 3.2 취향 팝콘으로 간다 — 시안이 두 카드 모두에
       * 화살표를 그렸다. 화살표가 있는데 안 눌리면 고장처럼 보인다.
       */}
      <div className={styles.shortcuts}>
        <Link to={path.tasteReports()} className={styles.shortcut}>
          <span className={styles.shortcutArt}>
            <span className={styles.reportStrip} aria-hidden="true">
              <ReportTape />

              {report ? (
                <ReportFourCut
                  cuts={report.fourCut}
                  createdAt={report.createdAt}
                  head={false}
                  characterCode={report.characterCard?.code}
                />
              ) : (
                <span className={styles.reportBlank} />
              )}
            </span>
          </span>

          <span className={styles.shortcutText}>
            <span className={styles.shortcutLabel}>4컷 리포트</span>
            {desktop ? <span className={styles.shortcutNote}>나의 영화 취향을 한눈에</span> : null}
          </span>
          <span className={styles.shortcutChevron}>
            <ChevronRightIcon />
          </span>
        </Link>

        <Link to={path.popcornBucket()} className={styles.shortcut}>
          {/* 그림은 장식이다. 링크 이름은 옆의 `취향 팝콘` 글자다 */}
          <span className={styles.shortcutArt} aria-hidden="true">
            <PopcornBucket width={52} flavors={popcorn} label="팝콘 통" />
          </span>

          <span className={styles.shortcutText}>
            <span className={styles.shortcutLabel}>취향 팝콘</span>
            {desktop ? (
              <span className={styles.shortcutNote}>내 취향이 담긴 팝콘을 확인해보세요</span>
            ) : null}
          </span>
          <span className={styles.shortcutChevron}>
            <ChevronRightIcon />
          </span>
        </Link>
      </div>

      {taste ? (
        <section className={styles.taste} aria-labelledby="my-taste-heading">
          <h2 id="my-taste-heading" className={styles.tasteTitle}>
            내 취향
          </h2>
          {taste.keywords.length > 0 ? (
            <ul className={styles.tasteKeywords}>
              {taste.keywords.map((keyword) => (
                <li key={keyword.code}>
                  <Chip tone={desktop ? 'light' : 'dark'} hashtag>
                    {keyword.label}
                  </Chip>
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.tasteNote}>{emptyTasteText(taste.ratingCount)}</p>
          )}
        </section>
      ) : null}

      {/* 시안이 탭 위에 둔 구역 이름. 좁은 화면은 탭 자체가 그 말을 해 두지 않는다 */}
      {desktop ? <h2 className={styles.feedTitle}>내 기록</h2> : null}

      {/* 화면 안의 전환이다. 다른 화면으로 가지 않으므로 링크가 아니라 탭이다 */}
      <div className={styles.tabs} role="tablist" aria-label="내 기록">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            id={`my-feed-tab-${item.key}`}
            aria-selected={tab === item.key}
            aria-controls="my-feed"
            className={styles.tab}
            onClick={() => setParams({ tab: item.key }, { replace: true })}
          >
            {item.label}
          </button>
        ))}
      </div>

      <div
        id="my-feed"
        role="tabpanel"
        aria-labelledby={`my-feed-tab-${tab}`}
        className={styles.feed}
      >
        {/*
         * 아직 평가하지 않은 것만 보는 스위치. **목록 상태와 상관없이 늘 같은 자리에 둔다** —
         * 읽는 중 · 실패 · 빈 목록에도 그린다. 목록이 비었을 때 사라지면 왜 비었는지(거르개를
         * 켜 둔 탓인지) 알 길이 없고, 끌 수도 없어 갇힌다.
         *
         * **켜고 끄면 이 목록만 첫 장부터 다시 받는다** — 커서가 조회 조건에 묶여 있어(API-039)
         * 조건이 바뀐 커서는 `400`이다. 다른 두 탭은 건드리지 않고, 주소도 바꾸지 않아 보던
         * 자리에 그대로 있는다.
         */}
        {tab === 'unrated' && (
          <div className={styles.feedFilter}>
            <button
              type="button"
              className={styles.feedFilterButton}
              data-on={unratedOnly}
              aria-pressed={unratedOnly}
              onClick={() => setUnratedOnly((on) => !on)}
            >
              평가 안 한 영화
            </button>
          </div>
        )}

        {feed.status === 'loading' && <p className={styles.feedNote}>불러오는 중이에요</p>}

        {feed.status === 'failed' && (
          <div className={styles.feedNote}>
            <p>목록을 불러오지 못했어요</p>

            <Button variant="secondary" onClick={retryFeeds}>
              다시 시도
            </Button>
          </div>
        )}

        {feed.status === 'ready' && feed.items.length === 0 && (
          <p className={styles.feedNote}>{EMPTY_TEXT[tab]}</p>
        )}

        {/*
         * 평가 탭. 누르면 2.4 영화 상세로 간다(업무 -638). 목록에서 영화를 고르면 상세가
         * 열리는 것이 자연스럽고, 평가 수정은 2.4의 `내 별점 수정하기`로 이어진다 — 한때
         * 2.5 평가 수정으로 바로 갔다(QA 2026-09-18). 별점은 API-039가 주는 값이라 포스터
         * 위에 표식으로 얹는다. 키는 평가 번호다 — 프레임 번호는 과거 평가에서 `null`일 수 있다.
         */}
        {tab === 'ratings' && ratings.status === 'ready' && ratings.items.length > 0 && (
          <ul className={styles.grid}>
            {ratings.items.map((rating) => (
              <li key={rating.ratingId}>
                <Link to={path.movieDetail(rating.movie.movieId)} className={styles.cell}>
                  {/*
                   * 넓은 화면은 포스터 아래에 평가한 날과 제목을 적는다(시안). 좁은 화면은
                   * 3열이라 자리가 없어 제목을 읽기 도구에만 남긴다.
                   *
                   * **별점보다 앞에 둔다** — 링크에 읽히는 이름이 `제목 별점` 순서여야 한다
                   */}
                  {desktop ? null : <VisuallyHidden>{rating.movie.title}</VisuallyHidden>}

                  <PosterCue>
                    <Poster
                      fluid
                      width={POSTER_RATIO.width}
                      height={POSTER_RATIO.height}
                      radius={0}
                      src={rating.movie.posterUrl ?? undefined}
                      title={rating.movie.title}
                      seed={rating.movie.movieId}
                    />

                    {/*
                     * 별점은 **포스터 칸 안에** 얹는다. 칸 전체를 기준으로 두면 넓은 화면에서
                     * 포스터 아래에 붙는 날짜 · 제목 위로 내려앉는다(업무 -786 브라우저 확인).
                     * `PosterCue`의 칸이 곧 포스터라 좁은 화면의 자리는 그대로다.
                     */}
                    <span className={styles.score}>
                      <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
                        <path d={STAR_PATH} />
                      </svg>
                      {rating.score.toFixed(1)}
                    </span>
                  </PosterCue>

                  {desktop ? (
                    <span className={styles.caption}>
                      <span className={styles.captionDate}>{formatDate(rating.createdAt)}</span>
                      <span className={styles.captionTitle}>{rating.movie.title}</span>
                    </span>
                  ) : null}
                </Link>
              </li>
            ))}
          </ul>
        )}

        {/*
         * 최근 본 영화 탭. 본 기록이 있는 영화다 — 평가한 것과 아직 안 한 것을 함께 담는다
         * (API-034 `RATED` + `WATCHED_UNRATED`, 업무 -919). 누르면 2.5 평가하기로 간다.
         * 이미 평가한 영화는 그 화면이 수정으로 받는다(API-037).
         * 날짜와 OTT는 기록이 주는 값이고, 클릭 이력이 없으면 둘 다 없다(업무 -477).
         */}
        {tab === 'unrated' && unrated.status === 'ready' && unrated.items.length > 0 && (
          <ul className={styles.grid}>
            {unrated.items.map(({ item, watchedOn }) => (
              <li key={`${item.itemType}-${item.movie.movieId}`}>
                <Link to={path.movieRating(item.movie.movieId)} className={styles.cell}>
                  <PosterCue>
                    <Poster
                      fluid
                      width={POSTER_RATIO.width}
                      height={POSTER_RATIO.height}
                      radius={0}
                      src={item.movie.posterUrl ?? undefined}
                      title={item.movie.title}
                      seed={item.movie.movieId}
                    />

                    {/* 평가한 영화는 `평가한 영화` 탭과 같은 자리에 같은 배지를 얹는다 */}
                    {item.itemType === 'RATED' ? (
                      <span className={styles.score}>
                        <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
                          <path d={STAR_PATH} />
                        </svg>
                        {item.score.toFixed(1)}
                      </span>
                    ) : null}
                  </PosterCue>

                  <VisuallyHidden>
                    {item.movie.title} {item.itemType === 'RATED' ? '평가 수정하기' : '평가하기'}
                  </VisuallyHidden>

                  <span className={styles.caption}>
                    {/* 아직 평가하지 않은 영화만 그 사실을 적는다. 평가한 것은 포스터 위 별점이 말한다 */}
                    {item.itemType === 'RATED' ? null : (
                      <span className={styles.captionSource}>평가 전</span>
                    )}
                    {watchedOn ? (
                      <span className={styles.captionDate}>
                        {desktop ? formatDate(watchedOn) : formatShortDate(watchedOn)}
                      </span>
                    ) : null}
                    {desktop ? (
                      <span className={styles.captionTitle}>{item.movie.title}</span>
                    ) : null}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}

        {/*
         * 추천 탭. 누르면 7.5와 같은 곳(2.4 영화 상세)으로 간다. 출처와 날짜는
         * API-048이 주는 값이다(`type` · 세트의 `generatedAt`). 그 밖의 값은 쓰지 않는다.
         */}
        {tab === 'recommendations' && history.status === 'ready' && history.items.length > 0 && (
          <ul className={styles.grid}>
            {history.items.map(({ item, generatedAt }) => (
              <li key={item.recommendationItemId}>
                <Link to={path.movieDetail(item.movie.movieId)} className={styles.cell}>
                  <PosterCue>
                    <Poster
                      fluid
                      width={POSTER_RATIO.width}
                      height={POSTER_RATIO.height}
                      radius={0}
                      src={item.movie.posterUrl ?? undefined}
                      title={item.movie.title}
                      seed={item.movie.movieId}
                    />
                  </PosterCue>

                  <VisuallyHidden>{item.movie.title}</VisuallyHidden>

                  <span className={styles.caption}>
                    <span className={styles.captionSource}>{SOURCE_LABEL[item.type]}</span>
                    <span className={styles.captionDate}>
                      {desktop ? formatDate(generatedAt) : formatShortDate(generatedAt)}
                    </span>
                    {desktop ? (
                      <span className={styles.captionTitle}>{item.movie.title}</span>
                    ) : null}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}

        {/* 세 탭이 같은 자리 · 같은 모양으로 다음 장을 받는다(업무 -503) */}
        {feed.status === 'ready' && cursors[tab] ? (
          <div className={styles.more}>
            <Button variant="secondary" onClick={loadMore} disabled={more[tab] === 'loading'}>
              {more[tab] === 'failed' ? '다시 시도' : '더 보기'}
            </Button>

            {more[tab] === 'failed' ? <p className={styles.moreError}>{MORE_ERROR[tab]}</p> : null}
          </div>
        ) : null}
      </div>
      {((ratings.status === 'ready' && ratings.items.length > 0) ||
        (history.status === 'ready' && history.items.length > 0)) && (
        <ServiceFeedbackPrompt key={profile.userId} userId={profile.userId} desktop={desktop} />
      )}
    </div>
  );

  const noticeBar = notice ? (
    <div className={styles.notice}>
      <Snackbar actionLabel="확인" onAction={() => setNotice('')}>
        {notice}
      </Snackbar>
    </div>
  ) : null;

  /*
   * 넓은 화면에는 사진 배경 틀이 없다. 시안의 프로필은 밝은 벽 위의 흰 카드이고, 상단의
   * 검색 · 알림 · 프로필은 데스크톱 셸의 상단 바가 이미 맡는다(업무 -782).
   */
  if (desktop) {
    return (
      <>
        {body}

        {/* 우측 보조 패널. 1280px 이상에서만 자리가 있다(업무 -782) */}
        <DesktopAside>
          <MyAside
            profile={profile}
            bucket={bucket}
            ratings={ratings.status === 'ready' ? ratings.items : []}
            reports={reportSummaries}
          />
        </DesktopAside>

        {noticeBar}
      </>
    );
  }

  return (
    <>
      {/*
       * `설정`은 헤더 오른쪽 **아이콘**이다(사용자 지시 2026-09-16). 글자는 남기고
       * 보이지만 않게 해 스크린 리더가 읽을 이름을 지킨다.
       *
       * **`OTT 관리`는 7.8 설정 안으로 옮겼다**(사용자 지시 2026-09-16). 이 화면에서
       * 6.1로 가는 길은 없어졌고 설정 > 계정 > OTT 관리가 유일한 입구다.
       *
       * 화면 제목은 `프로필`이다(사용자 시안 2026-09-18, 업무 -616). 그 위에 필름 · 파티와 같은
       * 공통 헤더(워드마크 · 검색 · 알림)가 선다(업무 -676) — 제목 줄은 헤더 아래로 내려와
       * 위 여백 없이 붙는다(`flush`).
       *
       * **프로필 사진이 화면 전체 배경이다**(사용자 지시 2026-09-19, 업무 -697). 2.4 영화 상세와
       * 같은 층 구성(`ProfileBackdrop`)이라 사진이 위에 선명하게 보이고, 내리면 흐려지며 어두운
       * 본문이 그 위를 덮는다. 헤더는 위에 붙어 따라온다.
       */}
      <ProfileBackdrop
        photoUrl={profile.avatarUrl}
        top={
          <>
            <AppHeader />
            <ScreenHeader
              flush
              tone="dark"
              title="프로필"
              action={
                <Link to={path.settings()} className={styles.headerLink}>
                  <SettingsIcon />
                  <VisuallyHidden>설정</VisuallyHidden>
                </Link>
              }
            />
          </>
        }
      >
        {body}
      </ProfileBackdrop>

      {noticeBar}
    </>
  );
}
