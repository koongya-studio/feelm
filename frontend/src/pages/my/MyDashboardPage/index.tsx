/*
 * 7.1 MY 대시보드
 *
 * 와이어프레임에서 가져온 것은 **기능과 정보**다 — 프로필 요약, 활동 수치, 내 취향 필름,
 * 메뉴 5개(취향 리포트 · 추천 기록 · 평가 · OTT 관리 · 설정). 배치는 가져오지 않았다
 * (screen-spec-my.md 1장).
 *
 * 와이어프레임은 같은 폭의 카드를 세로로 쌓는다. 레퍼런스(`Dormy App.jpg`)는 크기가
 * 다른 카드를 조합해 **정보 중요도를 카드 크기로** 나른다. 그쪽을 따랐다.
 *
 * | 자리 | 무엇 | 왜 |
 * | --- | --- | --- |
 * | 상단 | 인사말 + 아바타 + 편집 | 레퍼런스대로 간결하게. 프로필 카드를 크게 두지 않는다 |
 * | 큰 카드 | 내 취향 필름 | 이 화면에서 가장 중요한 정보다. 화면에서 유일한 어두운 블록 |
 * | 작은 카드 2열 | 본 영화 · 팝콘 | 수치는 짧아서 반 폭이면 충분하다 |
 * | 타일 목록 | 메뉴 5개 | 이동만 하는 항목이라 가장 가볍게 |
 *
 * **어두운 블록은 화면에 하나다**(screen-spec-my.md 4장). 취향 필름 카드가 그 자리를
 * 쓰므로 나머지는 전부 밝은 면이다.
 *
 * 수치는 100 기준이다. 와이어프레임 7.1의 `본 영화 13 · 평가 10`은 같은 사용자의
 * 7.2-A · 3.1 · 3.2와 어긋나 쓰지 않았다(mocks/user.ts).
 *
 * 메뉴 5개 중 **OTT 관리만 갈 곳이 있다.** 취향 리포트(7.3) · 추천 기록(7.5) ·
 * 평가(7.6) · 설정(7.8)은 306~308 몫이라 지금은 `NotInScopePage`로 간다. 링크를 막지
 * 않고 그대로 두는 이유는 이동 경로 자체가 검증 대상이기 때문이다
 * (screen-spec-my.md 2장 13번).
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import Avatar from '../../../components/Avatar';
import Button from '../../../components/Button';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ChevronRightIcon } from '../../../components/Icon';
import { getMyFrames } from '../../../api/taste';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import { formatJoinedMonth } from '../../../utils/date';
import styles from './MyDashboardPage.module.css';
import type { FrameSummary } from '../../../types/taste';
import type { MyProfile } from '../../../types/user';

/** 대시보드 카드에 보여주는 프레임 수. 가로 스크롤이라 넘치면 밀어서 본다 */
const FRAME_PREVIEW_COUNT = 8;

/** 메뉴 5개. 와이어프레임 7.1의 순서와 문구 그대로다 */
const MENU = [
  { label: '취향 리포트', to: path.tasteReports() },
  { label: '추천 기록', to: path.recommendationHistory() },
  { label: '평가', to: path.myRatings() },
  { label: 'OTT 관리', to: path.ottManage() },
  { label: '설정', to: path.settings() },
] as const;

export default function MyDashboardPage() {
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [frames, setFrames] = useState<FrameSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

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

    // 필름은 실패해도 본문을 막지 않는다. 카드만 비워진다
    getMyFrames()
      .then((value) => {
        if (active) {
          setFrames(value.items);
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [reloadCount]);

  if (failed) {
    return (
      <>
        <ScreenHeader title="MY" />

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
    return <ScreenHeader title="MY" />;
  }

  const preview = frames?.slice(0, FRAME_PREVIEW_COUNT) ?? [];

  return (
    <>
      <ScreenHeader title="MY" />

      <div className={styles.page}>
        {/*
         * 인사말 줄. 레퍼런스대로 아바타 · 이름 · 편집만 둔다. 와이어프레임은 여기에
         * 테두리 카드를 둘렀지만 그러면 아래 카드들과 위계가 같아진다.
         */}
        <div className={styles.identity}>
          <Avatar size={52} nickname={profile.nickname} />

          <div className={styles.identityText}>
            <p className={styles.name}>{profile.nickname}</p>
            <p className={styles.meta}>
              {profile.email} · {formatJoinedMonth(profile.createdAt)} 가입
            </p>
          </div>

          <Link to={path.profileEdit()} className={styles.editLink}>
            편집
          </Link>
        </div>

        {/*
         * 취향 필름 — 이 화면에서 가장 큰 카드이자 유일한 어두운 블록.
         * 전체 보기는 3.1로 간다(팀 결정 2026-09-11).
         */}
        <section className={styles.filmCard}>
          <div className={styles.filmHead}>
            <div>
              <p className={styles.filmLabel}>내 취향 필름</p>
              <p className={styles.filmCount}>{profile.stats.frameCount}프레임</p>
            </div>

            <Link to={path.film()} className={styles.filmMore}>
              전체 보기
              <ChevronRightIcon />
            </Link>
          </div>

          {preview.length > 0 ? (
            <ul className={styles.filmTrack}>
              {preview.map((frame) => (
                <li key={frame.frameId}>
                  <Link to={path.frameDetail(frame.frameId)} className={styles.filmItem}>
                    <Poster
                      width={72}
                      height={102}
                      radius={6}
                      src={frame.movie.posterUrl ?? undefined}
                      title={frame.movie.title}
                      seed={frame.movie.movieId}
                    />
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.filmEmpty}>영화를 평가하면 프레임이 쌓여요</p>
          )}
        </section>

        {/* 수치 두 개. 짧은 정보라 반 폭 카드에 나눠 담는다 */}
        <div className={styles.statGrid}>
          <div className={styles.statCard}>
            <p className={styles.statValue}>{profile.stats.ratingCount}</p>
            <p className={styles.statLabel}>평가한 영화</p>
          </div>

          <Link to={path.popcornBucket()} className={styles.statCard} data-link="true">
            <p className={styles.statValue}>{profile.stats.popcornCount}</p>
            <p className={styles.statLabel}>팝콘</p>
            <span className={styles.statChevron} aria-hidden="true">
              <ChevronRightIcon />
            </span>
          </Link>
        </div>

        <nav className={styles.menu} aria-label="MY 메뉴">
          {MENU.map((item) => (
            <Link key={item.label} to={item.to} className={styles.menuItem}>
              <span>{item.label}</span>
              <ChevronRightIcon />
            </Link>
          ))}
        </nav>
      </div>
    </>
  );
}
