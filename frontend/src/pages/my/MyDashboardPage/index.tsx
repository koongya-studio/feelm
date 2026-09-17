/*
 * 7.1 MY 대시보드
 *
 * 와이어프레임에서 가져온 것은 **기능과 정보**다 — 프로필, 취향 리포트 · 추천 기록 ·
 * 평가 · 설정. 배치는 가져오지 않았다(screen-spec-my.md 1장). `OTT 관리`는 7.8 설정
 * 안으로 옮겼다(사용자 지시 2026-09-16).
 *
 * 배치는 **사용자 시안**(인스타그램형 프로필 · 피드, 2026-09-16 업무 -459)을 따른다.
 *
 * | 자리 | 무엇 |
 * | --- | --- |
 * | 헤더 오른쪽 | `설정` 아이콘 |
 * | 상단 | 3:4 프로필 사진 + 닉네임 |
 * | 버튼 둘 | `프로필 편집`(→ 7.2) · `프로필 공유` |
 * | 바로가기 | `4컷 리포트`(→ 7.3) · `취향 팝콘`(이동 없음) |
 * | 탭 | `평가한 영화` · `안 남긴 영화` · `추천 기록`. **화면 안에서 아래 피드만 바꾼다** |
 * | 본문 | 고른 탭의 포스터 3열 피드 |
 *
 * **피드는 각 탭의 실제 기록이다.** 평가는 API-039(`getMyRatings`), 안 남긴 영화는
 * API-034(`getWatchRecords`, `WATCHED_UNRATED`), 추천은 API-048(`getRecommendationHistory`)이다.
 * 취향 필름(API-042)은 **부르지 않는다** — 프레임을 평가 · 추천 기록인 것처럼 쓸 수 없다.
 * 필름은 하단 내비 `필름` 탭(3.1)이 보여준다.
 *
 * **탭은 이동이 아니다.** 이 화면 안에서 목록만 바뀐다. 세 목록을 처음에 함께 읽어 두므로
 * 탭을 눌렀을 때 다시 기다리지 않는다.
 *
 * 고른 탭은 주소의 `?tab=`에 남는다. 화면 밖에서 특정 탭으로 보내야 하는 자리가 있기
 * 때문이다 — EXT-05 OTT 분석 데이터 부족의 `영화 평가하러 가기`가 `안 남긴 영화`로 온다.
 *
 * **추천 기록은 실제 호출이다**(업무 S15P21E106-478, API-048). 안 남긴 영화도 실제
 * 기록이고(API-034, 업무 -477), 평가만 API-039가 백엔드에 없어 목업으로 남는다.
 * `[!]` 추천 세트를 만드는 API-045가 아직 없어 추천 기록 탭은 당분간 비어 있다.
 *
 * **추천 기록 탭에만 `더 보기`가 있다**(업무 -478, 사용자 결정 2026-09-16). 한 장은
 * 20세트이고 그보다 오래된 이력은 `nextCursor`로 온다 — 버튼이 없으면 첫 장 뒤의 이력에
 * 닿을 길이 없다. 나머지 두 탭은 계약에 커서가 있어도 `api/watch`의 함수가 아직 목업이라
 * 첫 장만 그린다. 세 탭을 같은 모양으로 맞추는 것은 평가 목록 연동 때 한다(업무 -503).
 *
 * **통계 수치는 없다**(사용자 결정 2026-09-16). 프레임 · 평가 · 팝콘 수는 API-014가
 * 주지만 이 화면에 두지 않는다. 다른 자리에도 다시 넣지 않는다.
 *
 * **어두운 블록은 없다.** 4장 규칙은 `화면마다 어두운 블록은 하나다`이고 표는 7.2에
 * `없음`을 적는다 — 최대 하나이지 반드시 하나가 아니다.
 *
 * **벽은 앱 공통 벽 그대로다.** 7.1만 다른 배경을 쓰지 않는다. 한때 이 화면만 흰 벽으로
 * 만들었다가 되돌렸고, 공통 벽 자체를 다른 색으로 바꿨다가도 되돌렸다(2026-09-16).
 * 화면은 배경을 칠하지 않는다.
 *
 * **`프로필 공유`는 계약이 아니라 브라우저가 한다**(사용자 지시 2026-09-16). 공유 API가
 * 없으므로 서버를 부르지 않고, 7.2-B 공개 프로필 주소(`/users/{userId}`)를 기기의 공유
 * 메뉴(Web Share API)에 넘긴다. 없는 브라우저에서는 주소를 복사한다. 3.5 리포트 공유가
 * 쓰는 방법과 같다.
 * `[!]` 계약의 공개 프로필 키는 `publicId`인데 라우트는 `userId`다(9.4). 주소 규칙이
 * 정해지면 공유하는 주소도 같이 바꾼다.
 *
 * **그 밖에 계약에 없는 것은 넣지 않았다** — 한 줄 소개, 해시태그, 포스터 위에
 * 응답이 주지 않는 값. 프로필 이미지는 **자리만 둔다**. API-014 · API-015에 `avatarUrl`이
 * 없고 업로드 API도 없어 늘 기본 아이콘이다. 누르면 무언가 되는 것처럼 만들지 않았다.
 *
 * **7.5 추천 기록 · 7.6 평가한 영화 · 7.7 안 남긴 영화 화면은 지웠다**(업무 -495).
 * 세 화면이 하던 일을 이 피드가 그대로 한다. 7.6-A 한줄평 관리 시트는 3.1-A 프레임
 * 상세에서 열리므로 남는다(screen-spec-my.md 11장).
 */
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import Avatar from '../../../components/Avatar';
import Button from '../../../components/Button';
import PopcornBucket from '../../../components/PopcornBucket';
import { kernelCounts } from '../../../components/PopcornBucket/kernels';
import Poster from '../../../components/Poster';
import ReportFourCut from '../../../components/ReportFourCut';
import ReportTape from '../../../components/ReportTape';
import ScreenHeader from '../../../components/ScreenHeader';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { SettingsIcon } from '../../../components/Icon';
import { getOtts } from '../../../api/otts';
import { getRecommendationHistory } from '../../../api/recommendations';
import { getMyReports, getReportDetail } from '../../../api/reports';
import { getMyFrames, getPopcornBucket } from '../../../api/taste';
import { getMyProfile } from '../../../api/users';
import { getWatchRecords } from '../../../api/watch';
import { path } from '../../../routes/paths';
import { formatShortDate } from '../../../utils/date';
import styles from './MyDashboardPage.module.css';
import type { FlavorCount } from '../../../components/PopcornBucket/kernels';
import type { RecommendationType } from '../../../types/enums';
import type { RecommendationHistoryItem } from '../../../types/recommendation';
import type { ReportDetailView } from '../../../types/unspecified';
import type { MyProfile } from '../../../types/user';
import type { FrameSummary } from '../../../types/taste';
import type { WatchRecordListItem } from '../../../types/watch';

/** 포스터 비율 2:3. 격자는 칸 폭이 화면에 따라 달라지므로 비율만 고정한다 */
const POSTER_RATIO = { width: 2, height: 3 };

const TABS = [
  { key: 'ratings', label: '평가한 영화' },
  { key: 'unrated', label: '안 남긴 영화' },
  { key: 'recommendations', label: '추천 기록' },
] as const;

type TabKey = (typeof TABS)[number]['key'];

/** 탭마다 빈 목록일 때의 문구. 7.6 · 7.7이 쓰던 말을 줄여서 옮겼다 */
const EMPTY_TEXT: Record<TabKey, string> = {
  ratings: '아직 평가한 영화가 없어요',
  unrated: '평가를 기다리는 영화가 없어요',
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

/** 추천 세트를 한 줄씩 편다. 세트의 `generatedAt`이 그 줄의 날짜다 */
type HistoryRow = { item: RecommendationHistoryItem; generatedAt: string };

/**
 * 안 남긴 영화 한 칸. 감상 기록(API-034)에 OTT 이름과 날짜를 얹은 것이다.
 *
 * 기록은 `ottId`만 주고 이름은 기준정보(API-025)에 있다. 7.7이 쓰던 방식 그대로다.
 *
 * **둘 다 없을 수 있다.** 클릭 없이 평가로 만들어진 기록은 `ott`도 시각도 `null`이다
 * (업무 -477). 그런 칸은 OTT 이름과 날짜 없이 그린다.
 */
type UnratedRow = { record: WatchRecordListItem; ottName?: string; date?: string };

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
function useLatestReport(): ReportDetailView | null {
  const [detail, setDetail] = useState<ReportDetailView | null>(null);

  useEffect(() => {
    let active = true;

    getMyReports()
      .then(({ items }) => {
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

  return detail;
}

/*
 * 바로가기의 팝콘 통.
 *
 * **실제 맛 분포대로 알을 담는다**(API-041, 3.2 취향 팝콘과 같은 함수). 못 불러오거나
 * 기준정보가 아직 없으면 **빈 통**이다 — 아무 맛이나 쌓으면 없는 취향을 지어내는 것이 된다.
 */
function useMyPopcorn(): FlavorCount[] {
  const [flavors, setFlavors] = useState<FlavorCount[]>([]);

  useEffect(() => {
    let active = true;

    getPopcornBucket()
      .then((bucket) => {
        if (active) setFlavors(kernelCounts(bucket.flavors));
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  return flavors;
}

/** 세트 목록을 최신순 그대로 한 줄씩 편다 */
function flatten(
  sets: { generatedAt: string; items: RecommendationHistoryItem[] }[],
): HistoryRow[] {
  return sets.flatMap((set) => set.items.map((item) => ({ item, generatedAt: set.generatedAt })));
}

export default function MyDashboardPage() {
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  /*
   * 고른 탭은 주소에 남긴다(`?tab=`). 화면 밖에서 특정 탭으로 보내는 자리가 있어서다
   * (EXT-05 → `안 남긴 영화`). 모르는 값이 오면 기본 탭이다.
   */
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab: TabKey = TABS.some((item) => item.key === requested)
    ? (requested as TabKey)
    : 'ratings';

  const [ratings, setRatings] = useState<Feed<FrameSummary>>({ status: 'loading' });
  const [unrated, setUnrated] = useState<Feed<UnratedRow>>({ status: 'loading' });
  const [history, setHistory] = useState<Feed<HistoryRow>>({ status: 'loading' });
  /** 추천 기록의 다음 장 커서. 더 받을 것이 없으면 `null`이다(API-048 `hasNext`) */
  const [historyCursor, setHistoryCursor] = useState<string | null>(null);
  const [moreState, setMoreState] = useState<'idle' | 'loading' | 'failed'>('idle');
  const [feedReload, setFeedReload] = useState(0);
  const [notice, setNotice] = useState('');

  const report = useLatestReport();
  const popcorn = useMyPopcorn();

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((value) => {
        if (active) {
          setProfile(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    return () => {
      active = false;
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
     * 평가와 1:1인 프레임(API-042)이 `평가한 영화`의 원천이다(업무 -610). 내 평가 목록
     * API-039는 백엔드에 아직 없고, 그전에 쓰던 고정 목업은 방금 저장한 평가를 보여 주지
     * 못했다. API-039가 열리면 `api/watch`의 함수로 바꾼다.
     */
    getMyFrames()
      .then((value) => {
        if (active) setRatings({ status: 'ready', items: value.items });
      })
      .catch(() => {
        if (active) setRatings({ status: 'failed' });
      });

    /*
     * 안 남긴 영화는 감상 기록과 OTT 기준정보를 함께 읽는다. 기록은 `ottId`만 주고
     * 이름은 기준정보에 있다(7.7이 쓰던 방식).
     */
    Promise.all([getWatchRecords({ status: ['WATCHED_UNRATED'] }), getOtts()])
      .then(([records, otts]) => {
        if (!active) return;

        setUnrated({
          status: 'ready',
          items: records.items.map((record) => {
            const watchedOn = record.watchedAt ?? record.clickedAt;

            return {
              record,
              ottName: otts.find((ott) => ott.ottId === record.ott?.ottId)?.name,
              date: watchedOn ? formatShortDate(watchedOn) : undefined,
            };
          }),
        });
      })
      .catch(() => {
        if (active) setUnrated({ status: 'failed' });
      });

    getRecommendationHistory()
      .then((value) => {
        if (!active) return;

        setHistory({ status: 'ready', items: flatten(value.items) });
        setHistoryCursor(value.hasNext ? value.nextCursor : null);
      })
      .catch(() => {
        if (active) setHistory({ status: 'failed' });
      });

    return () => {
      active = false;
    };
  }, [feedReload]);

  if (failed) {
    return (
      <>
        <ScreenHeader title={<VisuallyHidden>MY</VisuallyHidden>} />

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

  if (!profile) {
    return <ScreenHeader title={<VisuallyHidden>MY</VisuallyHidden>} />;
  }

  const feed = tab === 'ratings' ? ratings : tab === 'unrated' ? unrated : history;

  /*
   * 7.2-B 공개 프로필 주소를 기기의 공유 메뉴에 넘긴다. 공유 API가 계약에 없으므로
   * 서버를 부르지 않는다. 공유 메뉴가 없는 브라우저에서는 주소를 복사하고, 그것도
   * 막혀 있으면 알린다 — 되는 것처럼 보이고 아무 일도 없는 상태를 만들지 않는다.
   */
  const shareProfile = async () => {
    const url = `${window.location.origin}${path.publicProfile(profile.userId)}`;
    const title = `${profile.nickname}님의 Feelm 프로필`;

    if (typeof navigator.share === 'function') {
      try {
        await navigator.share({ title, url });
      } catch {
        // 공유 메뉴를 닫은 경우도 여기로 온다. 알리지 않는다
      }

      return;
    }

    try {
      await navigator.clipboard.writeText(url);
      setNotice('프로필 주소를 복사했어요');
    } catch {
      setNotice('이 브라우저에서는 공유할 수 없어요');
    }
  };

  /*
   * 추천 기록의 다음 장을 이어 받는다(업무 -478). 실패해도 이미 그린 격자는 두고 버튼만
   * `다시 시도`로 바꾼다 — 여기서 목록을 비우면 보고 있던 기록이 통째로 사라진다
   * (4.2 검색이 쓰는 처리와 같다).
   */
  function loadMoreHistory() {
    if (!historyCursor || moreState === 'loading') {
      return;
    }

    setMoreState('loading');

    getRecommendationHistory(historyCursor)
      .then((page) => {
        setMoreState('idle');
        setHistoryCursor(page.hasNext ? page.nextCursor : null);
        setHistory((prev) =>
          prev.status === 'ready'
            ? { status: 'ready', items: [...prev.items, ...flatten(page.items)] }
            : prev,
        );
      })
      .catch(() => setMoreState('failed'));
  }

  function retryFeeds() {
    setHistoryCursor(null);
    setMoreState('idle');
    setRatings({ status: 'loading' });
    setUnrated({ status: 'loading' });
    setHistory({ status: 'loading' });
    setFeedReload((count) => count + 1);
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
       * **화면 제목 `MY`는 보이지 않는다**(사용자 지시 2026-09-16). 바로 아래 프로필이
       * 여기가 어디인지 말하고 하단 내비의 `MY`에도 표시가 있다. 화면마다 h1이 하나는
       * 있어야 하므로 글자는 남기고 보이지만 않게 한다.
       */}
      <ScreenHeader
        title={<VisuallyHidden>MY</VisuallyHidden>}
        action={
          <Link to={path.settings()} className={styles.headerLink}>
            <SettingsIcon />
            <VisuallyHidden>설정</VisuallyHidden>
          </Link>
        }
      />

      <div className={styles.page}>
        {/*
         * 프로필 머리.
         *
         * 프로필 사진(3:4)은 **자리만 있다.** API-014 · API-015에 `avatarUrl`이 없고 올리는
         * API도 없어 늘 기본 아이콘이다(`Avatar`의 자리표시자). 누를 수 없게 두어
         * 되는 기능처럼 보이지 않게 했다.
         */}
        <header className={styles.identity}>
          <Avatar size={84} shape="portrait" nickname={profile.nickname} />

          <p className={styles.name}>{profile.nickname}</p>
        </header>

        {/*
         * 프로필 버튼 둘. `프로필 편집`은 7.2로 가는 기존 입구 그대로이고(이동 경로 2 · 3번
         * 사슬의 출발점), `프로필 공유`는 기기의 공유 메뉴를 연다.
         */}
        <div className={styles.profileActions}>
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
        </div>

        {/*
         * 바로가기 그림 둘. 상자 · 테두리 없이 그림과 이름만 둔다. 왼쪽은 그림과 글자가
         * 하나의 링크로 7.3 취향 리포트 목록에 간다. 오른쪽은 이동이 없다 — 7.1의
         * 팝콘 → 3.2 이동은 9.2에서 뺐다(3.1 안에 같은 입구가 있다).
         */}
        <div className={styles.shortcuts}>
          <Link to={path.tasteReports()} className={styles.shortcut}>
            <span className={styles.shortcutArt}>
              <span className={styles.reportStrip} aria-hidden="true">
                <ReportTape />

                {report ? (
                  <ReportFourCut cuts={report.fourCut} createdAt={report.createdAt} head={false} />
                ) : (
                  <span className={styles.reportBlank} />
                )}
              </span>
            </span>

            <span className={styles.shortcutLabel}>4컷 리포트</span>
          </Link>

          <div className={styles.shortcut}>
            <span className={styles.shortcutArt}>
              <PopcornBucket width={64} flavors={popcorn} label="팝콘 통" />
            </span>

            <span className={styles.shortcutLabel}>취향 팝콘</span>
          </div>
        </div>

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
           * 평가 탭. 누르면 7.6과 같은 곳(2.5 평가 수정)으로 간다. 별점은 API-039가
           * 주는 값이라 포스터 위에 표식으로 얹는다.
           */}
          {tab === 'ratings' && ratings.status === 'ready' && ratings.items.length > 0 && (
            <ul className={styles.grid}>
              {ratings.items.map((rating) => (
                <li key={rating.frameId}>
                  <Link to={path.movieRating(rating.movie.movieId)} className={styles.cell}>
                    <Poster
                      fluid
                      width={POSTER_RATIO.width}
                      height={POSTER_RATIO.height}
                      radius={0}
                      src={rating.movie.posterUrl ?? undefined}
                      title={rating.movie.title}
                      seed={rating.movie.movieId}
                    />

                    <VisuallyHidden>{rating.movie.title}</VisuallyHidden>

                    <span className={styles.score}>
                      <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
                        <path d={STAR_PATH} />
                      </svg>
                      {rating.score.toFixed(1)}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}

          {/*
           * 안 남긴 영화 탭. 본 기록은 있는데 평가가 없는 영화다(API-034
           * `WATCHED_UNRATED`). 누르면 2.5 평가하기로 간다 — 7.7이 하던 이동 그대로다.
           * 날짜와 OTT는 기록이 주는 값이고, 클릭 이력이 없으면 둘 다 없다(업무 -477).
           */}
          {tab === 'unrated' && unrated.status === 'ready' && unrated.items.length > 0 && (
            <ul className={styles.grid}>
              {unrated.items.map(({ record, ottName, date }) => (
                <li key={record.watchRecordId}>
                  <Link to={path.movieRating(record.movie.movieId)} className={styles.cell}>
                    <Poster
                      fluid
                      width={POSTER_RATIO.width}
                      height={POSTER_RATIO.height}
                      radius={0}
                      src={record.movie.posterUrl ?? undefined}
                      title={record.movie.title}
                      seed={record.movie.movieId}
                    />

                    <VisuallyHidden>{record.movie.title} 평가하기</VisuallyHidden>

                    <span className={styles.caption}>
                      <span className={styles.captionSource}>{ottName ?? '감상 기록'}</span>
                      {date ? <span className={styles.captionDate}>{date}</span> : null}
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
                    <Poster
                      fluid
                      width={POSTER_RATIO.width}
                      height={POSTER_RATIO.height}
                      radius={0}
                      src={item.movie.posterUrl ?? undefined}
                      title={item.movie.title}
                      seed={item.movie.movieId}
                    />

                    <VisuallyHidden>{item.movie.title}</VisuallyHidden>

                    <span className={styles.caption}>
                      <span className={styles.captionSource}>{SOURCE_LABEL[item.type]}</span>
                      <span className={styles.captionDate}>{formatShortDate(generatedAt)}</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}

          {/*
           * 추천 기록만 다음 장을 받는다. 나머지 두 탭은 `api/watch`의 목록 함수가 아직
           * 커서를 쓰지 않아 첫 장뿐이다(업무 -503에서 셋을 같은 모양으로 맞춘다).
           */}
          {tab === 'recommendations' && history.status === 'ready' && historyCursor ? (
            <div className={styles.more}>
              <Button
                variant="secondary"
                onClick={loadMoreHistory}
                disabled={moreState === 'loading'}
              >
                {moreState === 'failed' ? '다시 시도' : '더 보기'}
              </Button>

              {moreState === 'failed' ? (
                <p className={styles.moreError}>지난 추천을 불러오지 못했어요.</p>
              ) : null}
            </div>
          ) : null}
        </div>
      </div>

      {notice ? (
        <div className={styles.notice}>
          <Snackbar actionLabel="확인" onAction={() => setNotice('')}>
            {notice}
          </Snackbar>
        </div>
      ) : null}
    </>
  );
}
