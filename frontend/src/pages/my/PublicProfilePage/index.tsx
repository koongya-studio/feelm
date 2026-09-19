/*
 * 7.2-B 다른 사용자 공개 프로필 · 7.2-A 내 공개 프로필 미리보기 (업무 S15P21E106-616)
 *
 * **두 화면은 이 하나다.** 사용자 시안 2026-09-18 — 미리보기는 따로 디자인하지 않고, 다른
 * 사람이 내 프로필을 볼 때의 화면을 그대로 연다.
 *
 * - 7.2-B `mode="public"` — 주소의 `userId`로 API-062를 부른다
 * - 7.2-A `mode="preview"` — 내 프로필을 **7.2-B와 같은 공개 모양으로** 받는다(`api/users.ts`
 *   `getMyPublicProfile` · `toPublicView`). 응답 모양 · 구역 · 정렬 · 빈 상태가 모두 같다
 *
 * 다른 것은 권한에서 나오는 둘뿐이다.
 * - 미리보기에는 위에 안내 바(`다른 사용자에게 보이는 내 프로필입니다.` · `편집으로 돌아가기`)가
 *   붙는다. 그 아래 본문은 남의 프로필과 똑같다
 * - 남의 프로필에만 ⋯ 메뉴(차단 · 신고)가 있다. 자기 자신에게는 차단 · 신고를 두지 않는다
 *
 * 비공개 키워드 · 필름은 API가 이미 빼고 준다. 이 화면은 가려 내지 않는다.
 *
 * 진입 경로: 7.2-B는 5.8 파티원 카드(screen-spec-my.md 2장 8번), 7.2-A는 7.2의
 * `내 공개 프로필 미리보기`다.
 *
 * **프로필은 목업이다**(API-062는 2차 MVP, 백엔드에 없다). 그래서 ⋯ 메뉴의 `사용자 차단`은
 * **잠겨 있다**(업무 -610). 이 화면에 들어오는 `userId`가 목업 번호라 서버에 없는 사람이어서
 * 실제 차단(API-020)을 부르면 늘 `404`였다. API-062가 열려 실제 사람이 흐르면 확인 창 → 7.8-D
 * 흐름을 되살린다. `[?]` 신고는 계약이 없다. 안내만 하고 실제 처리는 하지 않는다.
 */
import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import Button from '../../../components/Button';
import IconButton from '../../../components/IconButton';
import Menu from '../../../components/Menu';
import ProfileBackdrop from '../../../components/ProfileBackdrop';
import ProfileLayout, { ProfileSkeleton } from '../../../components/ProfileLayout';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { InfoIcon, MoreVerticalIcon } from '../../../components/Icon';
import { failureDescription } from '../../../components/StatusView/failure';
import { ApiError } from '../../../api/apiError';
import { getMyPublicProfile, getPublicProfile } from '../../../api/users';
import { useBack } from '../../../hooks/useBack';
import { path } from '../../../routes/paths';
import styles from './PublicProfilePage.module.css';
import type { ProfileView } from '../../../types/unspecified';

type Props = {
  /** `public` 다른 사용자(7.2-B) · `preview` 내 공개 프로필 미리보기(7.2-A) */
  mode?: 'public' | 'preview';
};

/** ⋯ 메뉴가 열리는 세로 위치(px). 헤더 오른쪽 ⋯ 바로 아래다 */
const MENU_TOP = 96;

/** 불러오지 못한 이유별 안내. 사용자가 할 일이 갈리는 데까지만 나눈다 */
function failureView(error: unknown): { title: string; description: string; retry: boolean } {
  if (error instanceof ApiError && error.status === 404) {
    return {
      title: '프로필을 볼 수 없어요',
      description: '탈퇴했거나 프로필을 공개하지 않은 사용자예요',
      retry: false,
    };
  }

  if (error instanceof ApiError && error.status === 403) {
    return {
      title: '이 프로필을 볼 권한이 없어요',
      description: '프로필 주인이 공개 범위를 바꿨을 수 있어요',
      retry: false,
    };
  }

  return {
    title: '프로필을 불러오지 못했어요',
    description: failureDescription(error),
    retry: true,
  };
}

export default function PublicProfilePage({ mode = 'public' }: Props) {
  const { userId } = useParams();
  const isPreview = mode === 'preview';

  const [profile, setProfile] = useState<ProfileView | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [reloadCount, setReloadCount] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  /** 신고를 고른 뒤의 안내. 계약이 없어 실제 처리는 하지 않는다 */
  const [notice, setNotice] = useState('');

  const backToEdit = useBack(path.profileEdit());

  useEffect(() => {
    let active = true;

    // 미리보기는 내 프로필을 공개 모양으로 한 번에 받는다. 남의 프로필과 모양이 같다
    const load = isPreview ? getMyPublicProfile() : getPublicProfile(Number(userId));

    load
      .then((value) => {
        if (active) {
          setProfile(value);
          setFailure(null);
        }
      })
      .catch((error: unknown) => {
        if (active) setFailure(error);
      });

    return () => {
      active = false;
    };
  }, [isPreview, userId, reloadCount]);

  /*
   * 헤더. 사진 배경 위라 흰 글자다(`tone`). 실패 화면만 밝은 벽이다.
   */
  const header = (tone: 'light' | 'dark') => (
    <ScreenHeader
      tone={tone}
      title="공개 프로필"
      align="center"
      showBack
      fallbackTo={isPreview ? path.profileEdit() : path.party()}
      action={
        isPreview ? (
          // 가운데 제목이 뒤로 가기 폭만큼 치우치지 않도록 오른쪽도 같은 폭을 비운다
          <span className={styles.headerSpacer} aria-hidden="true" />
        ) : (
          <IconButton
            label="더 보기"
            icon={<MoreVerticalIcon />}
            size={36}
            variant="translucent"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen(true)}
          />
        )
      }
    />
  );

  if (failure !== null) {
    const view = failureView(failure);

    return (
      <>
        {header('light')}

        <StatusView title={view.title} description={view.description}>
          {view.retry ? (
            <Button
              onClick={() => {
                setFailure(null);
                setProfile(null);
                setReloadCount((count) => count + 1);
              }}
            >
              다시 시도
            </Button>
          ) : null}
        </StatusView>
      </>
    );
  }

  /*
   * **프로필 사진이 화면 전체 배경이다**(사용자 지시 2026-09-19, 업무 -697). 2.4 영화 상세와 같은
   * 층 구성(`ProfileBackdrop`)이다.
   *
   * **받기 전에도 같은 틀을 쓴다.** 사진을 모르므로 사진 없는 어두운 배경에 자리만 비워 두고,
   * 받으면 사진이 깔린다. 받는 순간 밝은 벽을 어두운 틀로 바꿔 끼우면 화면이 번쩍이고, 안내 바와
   * ⋯ 버튼이 새로 만들어져 그 사이 누른 것이 사라진다.
   */
  return (
    <>
      <ProfileBackdrop photoUrl={profile?.avatarUrl ?? null} top={header('dark')} stickyTop>
        <div className={styles.page}>
          {isPreview ? (
            <div className={styles.previewBar} role="note">
              <span className={styles.previewIcon}>
                <InfoIcon />
              </span>
              <p className={styles.previewText}>다른 사용자에게 보이는 내 프로필입니다.</p>
              <button type="button" className={styles.previewBack} onClick={backToEdit}>
                편집으로 돌아가기
              </button>
            </div>
          ) : null}

          {profile ? (
            <ProfileLayout mode={mode} profile={profile} tone="dark" />
          ) : (
            <ProfileSkeleton tone="dark" />
          )}

          {notice ? (
            <p className={styles.notice} role="status">
              {notice}
            </p>
          ) : null}
        </div>
      </ProfileBackdrop>

      {menuOpen ? (
        <Menu label="프로필 메뉴" top={MENU_TOP} onClose={() => setMenuOpen(false)}>
          {/* 프로필이 목업인 동안 잠긴다. 목업 번호로 실제 차단을 부르지 않는다(업무 -610) */}
          <button type="button" className={styles.menuItem} disabled>
            사용자 차단
            <span className={styles.menuNote}>· 준비 중</span>
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
    </>
  );
}
