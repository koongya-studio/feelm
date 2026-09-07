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
 * `[?]` 각 섹션의 로딩 · 빈 데이터 상태는 상태 UI 업무(S15P21E106-264)에서 정의한다.
 * 지금은 데이터를 받기 전에 아무것도 그리지 않고, 실패한 섹션은 표시하지 않는다.
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import IconButton from '../../../components/IconButton';
import Section from '../../../components/Section';
import ScreenHeader from '../../../components/ScreenHeader';
import Poster from '../../../components/Poster';
import OttBadge from '../../../components/OttBadge';
import { BellIcon, ChevronRightIcon, PartyGroupIcon } from '../../../components/Icon';
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

export default function HomePage() {
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [otts, setOtts] = useState<Ott[]>([]);
  const [today, setToday] = useState<RecommendationItemView | null>(null);
  const [availableNow, setAvailableNow] = useState<AvailableNowMovie[]>([]);
  const [taste, setTaste] = useState<TasteAnalysis | null>(null);
  const [parties, setParties] = useState<PartyView[]>([]);

  useEffect(() => {
    let active = true;

    async function load() {
      const [profileResult, ottResult, availableResult, tasteResult, partyResult] =
        await Promise.all([
          getMyProfile(),
          getOtts(),
          getAvailableNowMovies(),
          getMyTaste(),
          getMyParties(),
        ]);

      if (!active) {
        return;
      }

      setProfile(profileResult);
      setOtts(ottResult);
      setAvailableNow(availableResult);
      setTaste(tasteResult);
      setParties(partyResult);
    }

    async function loadRecommendation() {
      try {
        const set = await getRecommendations();

        if (active) {
          // 홈의 대표 1편은 추천 세트의 첫 항목으로 둔다.
          // `[?]` 별도 API(확장 단계 API-066)인지 여부는 screen-spec.md 2.1 미확정 항목이다.
          setToday(set.items[0] ?? null);
        }
      } catch {
        // 홈에는 추천 실패 화면이 없다. 실패하면 섹션을 표시하지 않는다(업무 16에서 정의).
      }
    }

    void load();
    void loadRecommendation();

    return () => {
      active = false;
    };
  }, []);

  const genreSummary = taste ? summarizeGenres(taste) : null;
  const party = parties[0] ?? null;

  return (
    <>
      <ScreenHeader
        title={<span className={styles.wordmark}>feelm</span>}
        action={
          // 알림 목록(8.1)은 범위 밖이라 이동하지 않는다
          <IconButton label="알림" icon={<BellIcon />} size={36} disabled />
        }
      />

      <div className={styles.page}>
        {profile ? (
          <p className={styles.greeting}>{profile.nickname} 님, 오늘은 어떤 영화를 볼까요?</p>
        ) : null}

        {today ? (
          <Section label="오늘의 추천">
            <TodayRecommendationCard item={today} otts={otts} />

            {/*
             * 와이어프레임에서는 테두리만 있는 컨트롤이다. 동작이 2.2로의 화면 이동이므로
             * 버튼이 아니라 링크로 둔다(common-components.md 4.2 미확정 항목).
             * 높이 42px · radius 12px는 Button의 52px 사양과 달라 화면에서 스타일을 둔다.
             */}
            <Link to={path.recommendations()} className={styles.moreLink}>
              추천 더 보기
            </Link>
          </Section>
        ) : null}

        {availableNow.length > 0 ? (
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

        {taste && genreSummary ? (
          <Section label="내 취향 요약">
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
          </Section>
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

        {party ? (
          // 파티 목록(5.1)은 범위 밖이라 이동하지 않는다
          <section className={styles.partyCard}>
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
          </section>
        ) : null}
      </div>
    </>
  );
}
