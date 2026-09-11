/*
 * 7.2-A 내 공개 프로필 미리보기
 *
 * 와이어프레임에서 가져온 것 — 이름 · 필름/팝콘 수, 공개된 취향 키워드 · 필름 ·
 * 팝콘 버킷, 그리고 `비공개로 설정한 항목은 표시되지 않아요` 안내.
 *
 * 배치는 레퍼런스(`10 UI_UX Design Trends`)를 따라 **상단 비주얼과 하단 정보가
 * 이어지는** 구성이다. 히어로 배경은 무채색 어두운 면이다(`ProfileHero` 주석).
 *
 * 본인 화면이므로 팔로우 자리에 **프로필 편집**을 둔다(레퍼런스 지시 3.3).
 * 7.2-B와 같은 골격을 쓰되 다른 점은 세 가지다.
 * - ⋯ 메뉴(차단 · 신고)가 없다
 * - 한줄평 카드가 없다. 와이어프레임이 한줄평을 비공개로 둔 상태를 그렸다
 * - 필름을 눌러 프레임 상세(3.1-A)로 갈 수 있다. 내 기록이기 때문이다
 *
 * 이동표: `7.2 ⇢ 7.2-A`로 들어오고 편집으로 돌아간다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ProfileHero from '../../../components/ProfileHero';
import StatRow from '../../../components/StatRow';
import StatusView from '../../../components/StatusView';
import TasteShowcase from '../../../components/TasteShowcase';
import { getMyPublicProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './ProfilePreviewPage.module.css';
import type { PublicProfileView } from '../../../types/unspecified';

export default function ProfilePreviewPage() {
  const navigate = useNavigate();

  const [profile, setProfile] = useState<PublicProfileView | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMyPublicProfile()
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

  if (failed) {
    return (
      <>
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
    return null;
  }

  return (
    <>
      {/*
       * 헤더가 없다. 히어로가 화면 맨 위를 차지하고 뒤로 가기를 이미지 위에 띄운다
       * (ProfileHero 주석). 이 화면이 무엇인지는 이미지 위 `미리보기` 라벨이 알린다.
       */}
      <div className={styles.page}>
        <ProfileHero
          eyebrow="미리보기"
          nickname={profile.nickname}
          avatarUrl={profile.avatarUrl}
          introduction={profile.introduction}
        >
          <StatRow
            onDark
            items={[
              { label: '프레임', value: String(profile.stats.frameCount) },
              { label: '팝콘', value: String(profile.stats.popcornCount) },
              { label: '공개 한줄평', value: String(profile.stats.publicReviewCount) },
            ]}
          />
        </ProfileHero>

        <div className={styles.body}>
          <p className={styles.purpose}>다른 사용자에게 이렇게 보여요</p>

          <Button variant="secondary" onClick={() => void navigate(path.profileEdit())}>
            프로필 편집
          </Button>

          <TasteShowcase
            frameLinks
            keywords={profile.keywords}
            frames={profile.frames}
            popcorn={profile.popcorn}
            popcornCount={profile.stats.popcornCount}
          />

          {/*
           * 무엇이 빠졌는지 알려 주는 안내. 공개 범위는 7.8-B가 정하는데 그 화면이
           * 아직 없어 이동하지 않는다(screen-spec-my.md 2장 13번).
           */}
          <p className={styles.notice}>
            비공개로 설정한 항목은 여기에 보이지 않아요. 공개 범위는 설정에서 바꿀 수 있어요
          </p>
        </div>
      </div>
    </>
  );
}
