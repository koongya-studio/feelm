/*
 * 2.1 로그인 후 홈
 *
 * 요소 순서와 문구는 와이어프레임 2.1 그대로다(screen-spec.md 5장 2.1).
 * 헤더 · 인사 · 오늘의 추천 · 추천 더 보기 · 내 OTT에서 지금 볼 수 있어요 ·
 * 내 취향 요약 · 나에게 맞는 OTT 찾기 · 참여 중인 파티
 *
 * 계약에 대응이 없는 요소도 와이어프레임대로 만든다(팀 결정 2026-09-07).
 * 어떤 값이 계약에 없는지는 types/unspecified.ts에 모아 두었다.
 * - 취향 일치 % (CR-13) · 내 OTT에서 지금 볼 수 있어요 (CR-14)
 * - 나에게 맞는 OTT 찾기 배너 (CR-18) · 새 팝콘 수 (CR 미등록)
 *
 * 범위 밖 화면으로 가는 이동(알림 8.1 · 취향 리포트 3.4 · 파티 5.1 · OTT 추천 EXT-01)은
 * 라우트가 없으므로 링크를 걸지 않는다. 자리와 문구만 와이어프레임대로 둔다.
 *
 * 섹션마다 따로 불러온다. 한 번에 `Promise.all`로 받으면 한 섹션이 실패할 때 홈 전체가
 * 사라진다. **실패한 섹션만 안내로 바뀌고 나머지는 그대로 보인다**(업무 -264).
 * 데이터가 비어 있을 때의 문구도 여기서 정한다(`screen-spec.md` 2.1 미확정 항목).
 *
 * 로딩 표시는 만들지 않는다. 데이터를 받기 전에는 아무것도 그리지 않는다
 * (팀 결정 2026-09-08). `?mockDelay=800`으로 그 동작을 확인할 수 있다.
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import IconButton from '../../../components/IconButton';
import StatusView from '../../../components/StatusView';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import Poster from '../../../components/Poster';
import OttBadge from '../../../components/OttBadge';
import Wordmark from '../../../components/Wordmark';
import { BellIcon, ChevronRightIcon, PartyGroupIcon, SearchIcon } from '../../../components/Icon';
import TodayRecommendationCard from './TodayRecommendationCard';
import { getMyProfile } from '../../../api/users';
import { getRecommendations } from '../../../api/recommendations';
import { getAvailableNowMovies } from '../../../api/home';
import { getMyTaste } from '../../../api/taste';
import { getMyParties } from '../../../api/parties';
import { getOtts } from '../../../api/otts';
import { path } from '../../../routes/paths';
import { ottBadgeLetter } from '../../../utils/ott';
import styles from './HomePage.module.css';
import type { MyProfile } from '../../../types/user';
import type { Ott } from '../../../types/ott';
import type { TasteAnalysis } from '../../../types/taste';
import type {
  AvailableNowMovie,
  PartyView,
  RecommendationItemView,
} from '../../../types/unspecified';

/**
 * 와이어프레임은 `액션 · 스릴러`와 하나의 비율 `49%`를 함께 보여준다. 상위 두 장르를
 * 묶는 산출 규칙은 계약에 없으므로 화면에서 이름을 잇고 비율을 더한다.
 */
function summarizeGenres(taste: TasteAnalysis) {
  const top = taste.analysis.genres.slice(0, 2);

  return {
    label: top.map((genre) => genre.name).join(' · '),
    percent: Math.round(top.reduce((sum, genre) => sum + genre.share, 0) * 100),
  };
}

/** 실패했을 때 안내를 보여줄 섹션 */
type FailedSection = 'recommendation' | 'availableNow' | 'taste' | 'parties';

const NOTHING_FAILED: Record<FailedSection, boolean> = {
  recommendation: false,
  availableNow: false,
  taste: false,
  parties: false,
};

export default function HomePage() {
  const navigate = useNavigate();

  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [otts, setOtts] = useState<Ott[]>([]);
  /** `null`은 아직 받기 전이다. 빈 배열은 받았지만 보여줄 것이 없는 상태다 */
  const [recommendations, setRecommendations] = useState<RecommendationItemView[] | null>(null);
  const [availableNow, setAvailableNow] = useState<AvailableNowMovie[] | null>(null);
  const [taste, setTaste] = useState<TasteAnalysis | null>(null);
  const [tasteLoaded, setTasteLoaded] = useState(false);
  const [parties, setParties] = useState<PartyView[] | null>(null);

  const [failed, setFailed] = useState(NOTHING_FAILED);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    /** 섹션 하나를 불러온다. 실패하면 그 섹션만 안내로 바뀐다 */
    function load<T>(request: Promise<T>, apply: (value: T) => void, section?: FailedSection) {
      request
        .then((value) => {
          if (active) {
            apply(value);
          }
        })
        .catch(() => {
          if (active && section) {
            setFailed((previous) => ({ ...previous, [section]: true }));
          }
        });
    }

    // 인사와 OTT 기준정보는 실패해도 보여줄 안내가 없다. 인사는 사라지고 배지는 코드를 쓴다
    load(getMyProfile(), setProfile);
    load(getOtts(), setOtts);

    load(getRecommendations(), (set) => setRecommendations(set.items), 'recommendation');
    load(getAvailableNowMovies(), setAvailableNow, 'availableNow');
    load(
      getMyTaste(),
      (value) => {
        setTaste(value);
        setTasteLoaded(true);
      },
      'taste',
    );
    load(getMyParties(), setParties, 'parties');

    return () => {
      active = false;
    };
  }, [reloadCount]);

  /** 실패한 섹션의 `다시 시도`. 이미 받은 섹션은 그대로 두고 다시 불러온다 */
  function handleRetry() {
    setFailed(NOTHING_FAILED);
    setReloadCount((count) => count + 1);
  }

  /*
   * 홈의 대표 1편은 추천 세트의 첫 항목으로 둔다.
   * `[?]` 별도 API(확장 단계 API-066)인지 여부는 screen-spec.md 2.1 미확정 항목이다.
   */
  const today = recommendations?.[0] ?? null;
  const genreSummary = taste ? summarizeGenres(taste) : null;
  const party = parties?.[0] ?? null;

  return (
    <>
      <ScreenHeader
        title={<Wordmark />}
        action={
          <div className={styles.headerActions}>
            {/* 검색은 하단 탭에서 헤더로 옮겼다 (S15P21E106-329) */}
            <Link to={path.search()} className={styles.headerAction} aria-label="검색">
              <SearchIcon />
            </Link>
            {/* 알림 목록(8.1)은 범위 밖이라 이동하지 않는다 */}
            <IconButton label="알림" icon={<BellIcon />} size={36} disabled />
          </div>
        }
      />

      <div className={styles.page}>
        {profile ? (
          <p className={styles.greeting}>{profile.nickname} 님, 오늘은 어떤 영화를 볼까요?</p>
        ) : null}

        {failed.recommendation ? (
          <StatusView size="inline" title="추천을 잠시 불러오지 못했어요">
            <Button variant="text" onClick={handleRetry}>
              다시 시도
            </Button>
          </StatusView>
        ) : null}

        {recommendations && !today ? (
          <StatusView
            size="inline"
            variant="empty"
            title="추천할 영화를 아직 찾지 못했어요"
            description="영화를 평가하면 취향에 맞는 추천이 쌓여요"
          />
        ) : null}

        {today ? (
          <section className={styles.hero}>
            <p className={styles.eyebrow}>오늘의 추천</p>

            <TodayRecommendationCard item={today} otts={otts} />

            {/*
             * 동작이 2.2로의 화면 이동이므로 버튼이 아니라 링크로 둔다
             * (common-components.md 4.2 미확정 항목). 화면의 주 동작이라 검정 알약
             * 모양을 따른다.
             */}
            <Link to={path.recommendations()} className={styles.moreLink}>
              추천 더 보기
            </Link>
          </section>
        ) : null}

        {failed.availableNow ? (
          <StatusView size="inline" title="지금 볼 수 있는 영화를 불러오지 못했어요">
            <Button variant="text" onClick={handleRetry}>
              다시 시도
            </Button>
          </StatusView>
        ) : null}

        {availableNow?.length === 0 ? (
          <StatusView
            size="inline"
            variant="empty"
            title="구독 중인 OTT에서 볼 수 있는 영화가 없어요"
            description="구독 OTT를 설정하면 그 OTT의 영화를 모아 보여드려요"
          />
        ) : null}

        {availableNow && availableNow.length > 0 ? (
          <section className={styles.plainSection}>
            <h2 className={styles.sectionTitle}>내 OTT에서 지금 볼 수 있어요</h2>

            <ul className={styles.availableList}>
              {availableNow.map((movie) => {
                const ott = otts.find((candidate) => candidate.code === movie.ottProvider);

                return (
                  <li key={movie.movieId} className={styles.availableItem}>
                    <Link
                      to={path.movieDetail(movie.movieId)}
                      className={styles.availablePoster}
                      aria-label={`${movie.title} 상세 보기`}
                    >
                      <Poster
                        width={104}
                        height={146}
                        radius={8}
                        src={movie.posterUrl ?? undefined}
                        title=""
                        seed={movie.movieId}
                      />
                      <span className={styles.availableOtt}>
                        <OttBadge
                          letter={ottBadgeLetter(movie.ottProvider)}
                          name={ott?.name ?? movie.ottProvider}
                          size={20}
                        />
                      </span>
                    </Link>
                    <p className={styles.availableTitle}>{movie.title}</p>
                  </li>
                );
              })}
            </ul>
          </section>
        ) : null}

        {failed.taste ? (
          <StatusView size="inline" title="취향 요약을 불러오지 못했어요">
            <Button variant="text" onClick={handleRetry}>
              다시 시도
            </Button>
          </StatusView>
        ) : null}

        {tasteLoaded && !genreSummary?.label ? (
          <StatusView
            size="inline"
            variant="empty"
            title="아직 취향을 요약할 만큼 평가가 쌓이지 않았어요"
            description="영화를 평가하면 장르 비중과 키워드를 보여드려요"
          />
        ) : null}

        {taste && genreSummary?.label ? (
          <section className={styles.plainSection}>
            <h2 className={styles.sectionTitle}>내 취향 요약</h2>

            <div className={styles.tasteRow}>
              <span className={styles.tasteGenre}>{genreSummary.label}</span>
              <span
                className={styles.tasteTrack}
                role="img"
                aria-label={`${genreSummary.label} 비중 ${genreSummary.percent}퍼센트`}
              >
                <span
                  className={styles.tasteBar}
                  style={{ width: `${genreSummary.percent}%` }}
                  aria-hidden="true"
                />
              </span>
              <span className={styles.tastePercent}>{genreSummary.percent}%</span>
            </div>

            {taste.keywords[0] ? (
              <p className={styles.tasteKeyword}>{taste.keywords[0].label}</p>
            ) : null}

            {/*
             * 취향 리포트(3.4)는 범위 밖이라 이동할 곳이 없다. 링크처럼 보이는 문단을
             * 두면 눌러도 아무 일이 없으므로, 문구와 모양은 유지하고 동작하지 않음이
             * 보조기술에도 전달되도록 비활성 버튼으로 둔다.
             */}
            <button type="button" className={styles.reportLink} disabled>
              취향 리포트 보기
            </button>
          </section>
        ) : null}

        {/*
         * 나에게 맞는 OTT 찾기 (CR-18)
         * API-068은 확장 단계이고 EXT 화면도 Phase 2다. 와이어프레임대로 표시하되
         * 진입 화면이 없으므로 버튼은 동작하지 않는다.
         */}
        <section className={styles.banner}>
          <h2 className={styles.bannerTitle}>나에게 맞는 OTT 찾기</h2>
          <p className={styles.bannerText}>감상 기록을 분석해 OTT별 취향 일치도를 알려드려요</p>
          <button type="button" className={styles.bannerButton} disabled>
            OTT 추천받기
          </button>
        </section>

        {failed.parties ? (
          <StatusView size="inline" title="파티 정보를 불러오지 못했어요">
            <Button variant="text" onClick={handleRetry}>
              다시 시도
            </Button>
          </StatusView>
        ) : null}

        {parties?.length === 0 ? (
          <StatusView
            size="inline"
            variant="empty"
            title="참여 중인 파티가 없어요"
            description="파티를 만들면 친구와 겹치는 취향을 볼 수 있어요"
          />
        ) : null}

        {party ? (
          /*
           * 파티 목록(5.1)이 범위 밖이라 5.8 파티원 팝콘으로 바로 보낸다.
           * 배치 · 문구 · 색은 그대로이고 누를 수 있게만 바꿨다(팀 결정 2026-09-07).
           */
          <button
            type="button"
            className={styles.partyCard}
            onClick={() => void navigate(path.partyPopcorn(party.partyId))}
          >
            <span className={styles.partyIcon}>
              <PartyGroupIcon />
            </span>

            <div className={styles.partyBody}>
              <p className={styles.partyName}>참여 중인 파티 · {party.name}</p>
              <p className={styles.partyMeta}>
                멤버 {party.members.length}명 · 새 팝콘 {party.newPopcornCount}개
              </p>
            </div>

            <span className={styles.partyChevron}>
              <ChevronRightIcon />
            </span>
          </button>
        ) : null}
      </div>
    </>
  );
}
