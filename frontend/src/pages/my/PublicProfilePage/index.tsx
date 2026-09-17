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
 * **프로필은 목업이다**(API-062는 2차 MVP, 백엔드에 없다). 그래서 ⋯ 메뉴의 `사용자 차단`은
 * **잠겨 있다**(업무 -610). 전에는 여기서 실제 차단(API-020)을 불렀는데, 이 화면에 들어오는
 * `userId`가 목업 번호라 서버에 없는 사람이어서 늘 `404`였다 — 목업 화면이 실제 변경 API를
 * 부르면 안 된다. API-062가 열려 실제 사람이 흐르면 확인 창 → 7.8-D 흐름을 되살린다
 * (이동표 `7.2-B ⇢ 7.8-D`). 차단 해제와 목록은 7.8-D가 실제 API로 한다.
 *
 * `[?]` 신고는 계약이 없다. 안내만 하고 실제 처리는 하지 않는다.
 */
import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import Button from '../../../components/Button';
import Menu from '../../../components/Menu';
import IconButton from '../../../components/IconButton';
import ProfileBoard from '../../../components/ProfileBoard';
import StatusView from '../../../components/StatusView';
import { MoreVerticalIcon } from '../../../components/Icon';
import { failureDescription } from '../../../components/StatusView/failure';
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

  const id = Number(userId);

  const [profile, setProfile] = useState<PublicProfileView | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  /** 신고를 고른 뒤의 안내. 계약이 없어 실제 처리는 하지 않는다 */
  const [notice, setNotice] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getPublicProfile(id)
      .then((value) => {
        if (active) {
          setProfile(value);
          setFailure(null);
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setFailure(error);
        }
      });

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  if (failure !== null) {
    return (
      <>
        <StatusView title="프로필을 불러오지 못했어요" description={failureDescription(failure)}>
          <Button
            onClick={() => {
              setFailure(null);
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

      {/*
       * **이 화면에는 주 동작이 없다.** 검정 알약 하나가 `나와 취향 비교하기`로
       * 7.2-C에 가던 자리인데, 비교를 하지 않기로 하면서 그 화면을 지웠다
       * (사용자 결정 2026-09-17, 업무 -506). 읽는 화면으로 남긴다.
       */}
      <ProfileBoard
        profile={profile}
        reviews
        backTo={path.party()}
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
