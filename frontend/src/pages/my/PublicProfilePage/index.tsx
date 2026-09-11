/*
 * 7.2-B 다른 사용자 공개 프로필
 *
 * 와이어프레임에서 가져온 것 — 이름 · 필름/팝콘 수, ⋯ 메뉴(사용자 차단 · 신고하기),
 * 공개된 취향 키워드 · 필름 · 팝콘 버킷, 공개 한줄평, `나와 취향 비교하기`.
 *
 * 배치는 `Social Profile UI Design (1).jpg`를 따른다. 상단 비주얼을 화면 전체 폭으로
 * 크게 두고, 그 위에 이름과 소개를 얹고, 수치를 3열로 두고, 주요 액션을 넓은 pill로
 * 둔다. 레퍼런스의 인물 사진 자리는 무채색 면이다(`ProfileHero` 주석).
 *
 * **팔로워 · 팔로잉 · 창작물 수는 쓰지 않는다.** Feelm에 없는 개념이다. 레퍼런스가
 * 그 자리에 둔 3열을 **프레임 · 팝콘 · 나와의 일치도**로 바꿨다. 일치도는 7.2-C가
 * 보여주는 값과 같으므로, 3열 수치가 바로 아래 `나와 취향 비교하기` 버튼의 예고가 된다.
 *
 * 진입 경로: **5.8 파티원 카드**에서 들어온다. 와이어프레임 이동표에 7.2-B로 들어오는
 * 화살표가 없었고 2026-09-11에 확정했다(screen-spec-my.md 2장 8번).
 *
 * `[?]` 차단 · 신고는 계약이 없다. 확인 창까지만 만들고 실제 처리는 하지 않는다.
 * 이동표는 `7.2-B ⇢ 7.8-D`지만 7.8-D 차단 사용자 관리가 아직 없어 안내로 끝낸다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Menu from '../../../components/Menu';
import ProfileHero from '../../../components/ProfileHero';
import RatingStars from '../../../components/RatingStars';
import Section from '../../../components/Section';
import StatRow from '../../../components/StatRow';
import StatusView from '../../../components/StatusView';
import TasteShowcase from '../../../components/TasteShowcase';
import { getPublicProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './PublicProfilePage.module.css';
import type { PublicProfileView } from '../../../types/unspecified';

/** ⋯ 메뉴가 열리는 세로 위치(px). 이미지 위에 뜬 ⋯ 버튼 바로 아래다 */
const MENU_TOP = 76;

export default function PublicProfilePage() {
  const { userId } = useParams();
  const navigate = useNavigate();

  const id = Number(userId);

  const [profile, setProfile] = useState<PublicProfileView | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  /** 차단 · 신고를 고른 뒤의 안내. 계약이 없어 실제 처리는 하지 않는다 */
  const [notice, setNotice] = useState('');
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getPublicProfile(id)
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
  }, [id, reloadCount]);

  if (failed) {
    return (
      <>
        <StatusView
          title="프로필을 불러오지 못했어요"
          description="주소가 잘못되었거나 잠시 문제가 생겼어요"
        >
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
    return null;
  }

  return (
    <>
      {menuOpen ? (
        <Menu label="프로필 메뉴" top={MENU_TOP} onClose={() => setMenuOpen(false)}>
          <button
            type="button"
            className={styles.menuItem}
            onClick={() => {
              setMenuOpen(false);
              setNotice(`${profile.nickname} 님을 차단하면 서로의 콘텐츠가 보이지 않아요`);
            }}
          >
            사용자 차단
          </button>

          <button
            type="button"
            className={styles.menuItem}
            data-tone="quiet"
            onClick={() => {
              setMenuOpen(false);
              setNotice('신고 내용은 운영팀이 확인해요');
            }}
          >
            신고하기
          </button>
        </Menu>
      ) : null}

      {/*
       * 헤더가 없다. 히어로가 화면 맨 위를 차지하고 뒤로 가기와 ⋯를 이미지 위에
       * 띄운다(ProfileHero 주석). 제목은 히어로 안의 이름이 h1으로 맡는다.
       */}
      <div className={styles.page}>
        <ProfileHero
          nickname={profile.nickname}
          avatarUrl={profile.avatarUrl}
          introduction={profile.introduction}
          onMore={() => setMenuOpen(true)}
        >
          <StatRow
            onDark
            items={[
              { label: '프레임', value: String(profile.stats.frameCount) },
              { label: '팝콘', value: String(profile.stats.popcornCount) },
              {
                label: '나와 일치',
                value: profile.matchRate === null ? '—' : `${profile.matchRate}%`,
              },
            ]}
          />
        </ProfileHero>

        <div className={styles.body}>
          {/* 화면의 주 동작. 검정 알약은 이것 하나다 */}
          <Button onClick={() => void navigate(path.tasteCompare(id))}>나와 취향 비교하기</Button>

          <TasteShowcase
            keywords={profile.keywords}
            frames={profile.frames}
            popcorn={profile.popcorn}
            popcornCount={profile.stats.popcornCount}
          />

          {profile.reviews.length > 0 ? (
            <Section label="공개 한줄평">
              <ul className={styles.reviews}>
                {profile.reviews.map((review) => (
                  <li key={review.ratingId} className={styles.review}>
                    <p className={styles.reviewComment}>{review.comment}</p>
                    <p className={styles.reviewMeta}>
                      <RatingStars score={review.score} size={12} />
                      <span>{review.movieTitle}</span>
                    </p>
                  </li>
                ))}
              </ul>
            </Section>
          ) : null}

          {notice ? (
            <p className={styles.notice} role="status">
              {notice}
            </p>
          ) : null}
        </div>
      </div>
    </>
  );
}
