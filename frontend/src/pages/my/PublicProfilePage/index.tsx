/*
 * 7.2-B 다른 사용자 공개 프로필
 *
 * 와이어프레임에서 가져온 것 — 이름, ⋯ 메뉴(사용자 차단 · 신고하기), 공개된 취향
 * 키워드 · 필름 · 팝콘 버킷, 공개 한줄평.
 *
 * **2.4 영화 상세와 같은 화면이다**(업무 -605, 사용자 지시 2026-09-18 — "영화 상세페이지
 * 화면처럼"). 화면은 `ProfileBoard`가 그린다. 그전에는 `Social Profile UI Design (1).jpg`를
 * 따라 어두운 히어로에 수치 3열을 얹었다(업무 -305). ⋯는 2.4의 뒤로 가기와 같은 유리 버튼으로
 * 위 오른쪽에 뜬다.
 *
 * **팔로워 · 팔로잉 · 창작물 수는 쓰지 않는다.** Feelm에 없는 개념이다. 이름 아래 수치 한 줄은
 * **프레임 · 팝콘 · 한줄평**이고 셋 다 API-062가 주는 값이다.
 *
 * **뺀 것**(업무 -459) — 아바타 · 한 줄 소개는 계약에 자리가 없다. 3열의 `나와 일치`도
 * 뺐다. API-063이 주기로 한 값이지만 2차 MVP라 미구현이고, 지금 보이던 `76%`는 목업이
 * 지어낸 수였다.
 *
 * **주 동작이 없다**(업무 -506) — 검정 알약 하나가 `나와 취향 비교하기`로 7.2-C에 가던
 * 자리인데, 비교를 하지 않기로 하면서 그 화면을 지웠다. 읽는 화면으로 남긴다
 * (screen-spec-my.md 13장).
 *
 * 진입 경로: **5.8 파티원 카드**에서 들어온다. 와이어프레임 이동표에 7.2-B로 들어오는
 * 화살표가 없었고 2026-09-11에 확정했다(screen-spec-my.md 2장 8번).
 *
 * 차단은 확인 창을 거쳐 차단하고 7.8-D로 간다(이동표 `7.2-B ⇢ 7.8-D`, 업무 -307). 확인 창은
 * 공동 파티에서 나가게 된다는 것을 알린다 — API-020이 차단 전에 안내하라고 적었다. 이동은
 * `replace`다. 차단한 사람의 프로필은 404라 뒤로 가기가 여기로 돌아오면 안 된다.
 *
 * **차단만 실제 API다**(API-020, 업무 -394). 프로필 자체는 API-062가 2차 MVP라 목업이다.
 * `[!]` 그래서 이 화면에 들어오는 `userId`는 목업 번호이고, 실제 백엔드에는 없는 사람이라
 *       차단이 `404`로 끝난다. 파티(S15P21E106-393)와 API-062가 열려 실제 번호가 흘러야
 *       이어진다. 목업 프로필과 같은 번호의 사용자가 서버에 있으면 그대로 동작한다.
 *
 * `[?]` 신고는 계약이 없다. 안내만 하고 실제 처리는 하지 않는다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import ConfirmDialog from '../../../components/ConfirmDialog';
import Menu from '../../../components/Menu';
import IconButton from '../../../components/IconButton';
import ProfileBoard from '../../../components/ProfileBoard';
import StatusView from '../../../components/StatusView';
import { MoreVerticalIcon } from '../../../components/Icon';
import { isApiError } from '../../../api/apiError';
import { blockUser } from '../../../api/settings';
import { getPublicProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './PublicProfilePage.module.css';
import type { PublicProfileView } from '../../../types/unspecified';

/** ⋯ 메뉴가 열리는 세로 위치(px). 이미지 위에 뜬 ⋯ 버튼 바로 아래다 */
/* ⋯ 버튼 바로 아래. 위 여백(--space-3) + 버튼(39px)이 지난 자리다 */
const MENU_TOP = 60;

/* 2.4의 뒤로 가기와 같은 값 */
const TOP_BUTTON_SIZE = 39;

export default function PublicProfilePage() {
  const { userId } = useParams();
  const navigate = useNavigate();

  const id = Number(userId);

  const [profile, setProfile] = useState<PublicProfileView | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  /** 신고를 고른 뒤의 안내. 계약이 없어 실제 처리는 하지 않는다 */
  const [notice, setNotice] = useState('');
  const [confirmingBlock, setConfirmingBlock] = useState(false);
  const [blocking, setBlocking] = useState(false);
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

  async function handleBlock() {
    setBlocking(true);

    try {
      await blockUser(id);
      void navigate(path.blockedUsers(), { replace: true });
    } catch (error) {
      /*
       * 이미 차단한 사람이면(409 ALREADY_BLOCKED) 바라던 상태이므로 성공과 같이 7.8-D로
       * 보낸다. 두 탭에서 같은 프로필을 열어 둔 경우에 난다.
       */
      if (isApiError(error) && error.code === 'ALREADY_BLOCKED') {
        void navigate(path.blockedUsers(), { replace: true });

        return;
      }

      setBlocking(false);
      setConfirmingBlock(false);
      // 계약이 정한 문구가 상황을 더 정확히 말해 준다(없는 사용자 404 · 자기 자신 400)
      setNotice(
        isApiError(error) && error.status >= 400 && error.status < 500
          ? error.message
          : '차단하지 못했어요. 잠시 후 다시 시도해 주세요',
      );
    }
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
              setConfirmingBlock(true);
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

      {confirmingBlock ? (
        <ConfirmDialog
          title={`${profile.nickname} 님을 차단할까요?`}
          description="서로의 프로필·취향 필름·팝콘·한줄평이 보이지 않아요. 함께 있는 파티에서는 나가게 돼요"
          confirmLabel="차단"
          busy={blocking}
          onConfirm={() => void handleBlock()}
          onCancel={() => setConfirmingBlock(false)}
        />
      ) : null}

      {/*
       * **이 화면에는 주 동작이 없다.** 검정 알약 하나가 `나와 취향 비교하기`로
       * 7.2-C에 가던 자리인데, 비교를 하지 않기로 하면서 그 화면을 지웠다
       * (사용자 결정 2026-09-17, 업무 -506). 읽는 화면으로 남긴다.
       */}
      <ProfileBoard
        profile={profile}
        reviews
        topAction={
          <IconButton
            label="더 보기"
            icon={<MoreVerticalIcon />}
            size={TOP_BUTTON_SIZE}
            variant="translucent"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen(true)}
          />
        }
      >
        {notice ? (
          <p className={styles.notice} role="status">
            {notice}
          </p>
        ) : null}
      </ProfileBoard>
    </>
  );
}
