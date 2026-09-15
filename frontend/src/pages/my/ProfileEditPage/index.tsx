/*
 * 7.2 프로필 편집
 *
 * 와이어프레임에서 가져온 것 — 프로필 이미지 변경, 닉네임(2~10자 · 카운터 · 헬퍼),
 * 읽기 전용 3행(이메일 · 연령대 · 성별), 저장, 미리보기로 나가는 경로.
 *
 * 배치는 레퍼런스(`10 UI_UX Design Trends`)를 따른다. 와이어프레임은 입력을 세로로
 * 길게 나열하지만, 레퍼런스는 **상단에 큰 프로필 비주얼**을 두고 그 아래를 정보 영역으로
 * 나눈다. 편집 화면에서 가장 먼저 보여야 할 것은 지금 내 프로필이 어떻게 생겼는가다.
 *
 * 와이어프레임에 없고 레퍼런스 지시로 추가한 것 두 가지 (screen-spec-my.md 3.2)
 * - **한 줄 소개.** 7.2-A · 7.2-B가 소개 문구를 보여줘야 하는데 편집할 곳이 없었다
 * - **선호 장르 칩.** 값은 API-040 취향 분석에서 읽어 보여준다. **저장하지 않는다** —
 *   취향은 평가로 쌓이는 것이지 직접 고르는 것이 아니다(FR-07). 고를 수 있게 만들면
 *   서비스의 전제와 어긋나므로 조작할 수 없는 표시로 두고, 어디서 오는 값인지 적었다
 *
 * 저장은 세션 저장소를 거친다. **닉네임을 읽는 화면이 여럿이다** — 2.1 홈 인사말 ·
 * 5.8 파티원 · 7.1 · 7.2-A가 모두 `getMyProfile`을 거치므로 여기서 저장하면 전부
 * 따라온다(mocks/sessionStore.ts의 `profileEdit` 주석).
 *
 * 저장 후 7.1로 돌아간다(와이어프레임 이동표 `7.2 ⇢ 7.1`).
 *
 * **닉네임은 입력을 멈추면 서버에 사용 가능한지 물어본다**(API-016, 업무
 * S15P21E106-391). 0.4는 `중복 확인` 버튼으로 묻지만 와이어프레임 7.2에는 그 버튼이
 * 없고 글자수 카운터와 헬퍼만 있다. 버튼을 새로 그리는 대신 입력이 멎은 뒤 한 번
 * 물어 헬퍼 자리에 결과를 적는다 — 보이는 요소는 그대로 두고 값만 채우는 쪽이다.
 * 확인을 통과해도 저장(API-015)이 다시 검사하므로 `409 NICKNAME_DUPLICATED`는 여전히
 * 올 수 있다(API-016 처리 규칙).
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Avatar from '../../../components/Avatar';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import ScreenHeader from '../../../components/ScreenHeader';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import TextField from '../../../components/TextField';
import { CameraIcon } from '../../../components/Icon';
import { getMyTaste } from '../../../api/taste';
import { ApiError } from '../../../api/apiError';
import {
  checkNicknameAvailability,
  getMyIntroduction,
  getMyProfile,
  isValidNickname,
  saveMyIntroduction,
  updateMyProfile,
} from '../../../api/users';
import { path } from '../../../routes/paths';
import { formatAgeGroup } from '../../../utils/date';
import styles from './ProfileEditPage.module.css';
import type { TasteGenre } from '../../../types/taste';
import type { MyProfile } from '../../../types/user';

/** 와이어프레임 7.2의 헬퍼 문구가 적은 상한 */
const NICKNAME_MAX = 10;
/** 저장 막대를 보여주고 7.1로 돌아가기까지의 시간 */
const SAVED_NOTICE_MS = 700;
/** 한 줄 소개 상한. 와이어프레임에 없어 히어로에서 두 줄로 끊기는 길이로 잡았다 */
const INTRODUCTION_MAX = 40;

/** 와이어프레임 7.2의 헬퍼 문구 그대로다 */
const NICKNAME_HINT = '한글, 영문, 숫자, _ 2~10자';

/**
 * 입력이 멎었다고 보는 시간(ms).
 *
 * 글자마다 부르면 닉네임 하나에 요청이 열 번 나간다. 0.4처럼 버튼으로 부르는 자리가
 * 없어 이 화면만 시간으로 가른다.
 */
const NICKNAME_CHECK_DELAY_MS = 400;

type NicknameState = 'checking' | 'available' | 'duplicated' | 'invalid' | 'failed';

const NICKNAME_MESSAGE: Record<NicknameState, string> = {
  checking: '확인하는 중이에요',
  available: '사용할 수 있는 닉네임이에요',
  duplicated: '이미 사용 중인 닉네임이에요',
  invalid: `닉네임은 ${NICKNAME_HINT}예요`,
  failed: '사용 가능한지 확인하지 못했어요',
};

/** 결과로 저장을 막는 것은 중복뿐이다. 나머지는 알리기만 하고 서버 판정에 맡긴다 */
function blocksSave(state: NicknameState | null): boolean {
  return state === 'duplicated';
}

/** 성별 표시명. 와이어프레임 7.2가 `여성`으로 적는다 */
const GENDER_LABEL: Record<MyProfile['gender'], string> = {
  FEMALE: '여성',
  MALE: '남성',
  OTHER: '기타',
};

export default function ProfileEditPage() {
  const navigate = useNavigate();

  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [nickname, setNickname] = useState('');
  const [introduction, setIntroduction] = useState('');
  const [genres, setGenres] = useState<TasteGenre[]>([]);
  const [error, setError] = useState('');
  /** 어느 닉네임에 대한 결과인지 함께 들고 있어야 입력이 바뀐 뒤의 결과를 쓰지 않는다 */
  const [availability, setAvailability] = useState<{
    nickname: string;
    state: NicknameState;
  } | null>(null);
  const [saved, setSaved] = useState(false);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    Promise.all([getMyProfile(), getMyIntroduction()])
      .then(([value, intro]) => {
        if (active) {
          setProfile(value);
          setNickname(value.nickname);
          setIntroduction(intro);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    // 장르는 실패해도 본문을 막지 않는다. 칩만 비워진다
    getMyTaste()
      .then((taste) => {
        if (active) {
          setGenres(taste.analysis.genres);
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [reloadCount]);

  /*
   * 닉네임 사용 가능 확인(API-016).
   *
   * 지금 쓰는 닉네임 그대로면 물을 것이 없다 — 자기 것이라 중복으로 나오지 않지만
   * 화면을 열자마자 요청이 나가는 것도 이상하다.
   */
  useEffect(() => {
    if (!profile || nickname === profile.nickname) {
      return;
    }

    let active = true;
    const timer = setTimeout(() => {
      /*
       * 형식이 틀린 값은 부르지 않는다. 서버가 `INVALID_FORMAT`으로 같은 답을 주므로
       * 왕복할 이유가 없다(0.4와 같다). 판정을 기다린 뒤에 하는 이유는 두 글자를
       * 치는 도중에 `2~10자`라고 나무라지 않기 위해서다.
       */
      if (!isValidNickname(nickname)) {
        setAvailability({ nickname, state: 'invalid' });

        return;
      }

      setAvailability({ nickname, state: 'checking' });

      checkNicknameAvailability(nickname)
        .then(({ available, reason }) => {
          if (!active) return;

          setAvailability({
            nickname,
            state: available ? 'available' : reason === 'INVALID_FORMAT' ? 'invalid' : 'duplicated',
          });
        })
        .catch(() => {
          if (active) setAvailability({ nickname, state: 'failed' });
        });
    }, NICKNAME_CHECK_DELAY_MS);

    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [nickname, profile]);

  /*
   * 저장 막대를 잠깐 보여주고 7.1로 돌아간다(이동표 `7.2 ⇢ 7.1`). 타이머를 effect에
   * 두는 이유는 사용자가 그 사이에 뒤로 가기를 눌러도 이동이 따라오지 않게 하기
   * 위해서다. `handleSave` 안에서 `setTimeout`을 걸면 화면을 떠난 뒤에 이동이 실행된다.
   */
  useEffect(() => {
    if (!saved) return;

    const timer = setTimeout(() => void navigate(path.my()), SAVED_NOTICE_MS);

    return () => clearTimeout(timer);
  }, [saved, navigate]);

  /*
   * 확인한 뒤 닉네임을 고쳤으면 그 결과는 지금 값의 것이 아니다(0.4와 같다). 지금 쓰는
   * 닉네임으로 되돌린 경우도 물어본 적이 없으니 결과가 없다.
   */
  const nicknameState =
    nickname !== profile?.nickname && availability?.nickname === nickname
      ? availability.state
      : null;

  function handleSave() {
    if (!profile) return;

    if (!isValidNickname(nickname)) {
      setError(NICKNAME_MESSAGE.invalid);

      return;
    }

    // 이미 서버가 안 된다고 답한 값이다. 보내 봐야 `409`로 돌아온다
    if (blocksSave(nicknameState)) {
      setError(NICKNAME_MESSAGE.duplicated);

      return;
    }

    setError('');

    /*
     * 닉네임은 API-015로 서버에 저장하고, 한 줄 소개는 계약에 자리가 없어 따로 둔다
     * (`api/users.ts`). 소개만 바꾸고 저장해도 닉네임 요청은 그대로 나가는데,
     * API-015는 값이 같으면 중복으로 보지 않는다(처리 규칙).
     */
    Promise.all([updateMyProfile({ nickname }), saveMyIntroduction(introduction)])
      .then(() => {
        // 저장했음을 알리고, 아래 effect가 잠시 뒤 7.1로 돌려보낸다
        setSaved(true);
      })
      .catch((reason: unknown) =>
        setError(
          reason instanceof ApiError
            ? reason.message
            : '저장하지 못했어요. 잠시 후 다시 시도해 주세요',
        ),
      );
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="프로필 편집" showBack />

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
    return <ScreenHeader title="프로필 편집" showBack />;
  }

  return (
    <>
      <ScreenHeader title="프로필 편집" showBack />

      <div className={styles.page}>
        {/*
         * 상단 비주얼. 7.2-A · 7.2-B의 히어로와 같은 무채색 면을 쓰되 낮다.
         *
         * 처음에는 밝은 벽에 아바타만 크게 두었는데, 그러면 **와이어프레임 배치 그대로**이고
         * 같은 묶음인 7.2-A · 7.2-B와 혼자 달라 보였다(2026-09-11 되돌림). 레퍼런스
         * (`10 UI_UX Design Trends`)도 편집 화면 상단에 큰 비주얼을 둔다.
         *
         * 미리보기(7.2-A)보다 낮다. 여기는 보는 화면이 아니라 고치는 화면이라 아래
         * 입력이 접힌 자리 밖으로 밀리면 안 된다.
         *
         * 입력한 닉네임이 바로 이름에 반영돼 결과를 보면서 고칠 수 있다(레퍼런스 지시 7).
         */}
        <div className={styles.visual}>
          <div className={styles.avatarSlot}>
            <div className={styles.avatarWrap}>
              <Avatar size={104} nickname={nickname || profile.nickname} />

              {/*
               * 이미지 변경은 파일 선택이 필요하고 업로드 API도 계약에 없다. 자리와 문구만
               * 두고 동작하지 않음을 분명히 한다.
               */}
              <button
                type="button"
                className={styles.avatarBadge}
                disabled
                aria-label="프로필 이미지 변경"
              >
                <CameraIcon />
              </button>
            </div>

            <p className={styles.visualName}>{nickname || profile.nickname}</p>
            <p className={styles.visualHint}>프로필 이미지 변경은 곧 열려요</p>
          </div>
        </div>

        <section className={styles.group}>
          <h2 className={styles.groupLabel}>기본 정보</h2>

          <TextField
            label="닉네임"
            value={nickname}
            maxLength={NICKNAME_MAX}
            onChange={(event) => {
              setNickname(event.target.value);
              setError('');
            }}
            trailing={`${[...nickname].length}/${NICKNAME_MAX}`}
            hint={
              nicknameState && nicknameState !== 'duplicated' && nicknameState !== 'invalid'
                ? NICKNAME_MESSAGE[nicknameState]
                : NICKNAME_HINT
            }
            error={
              error ||
              (nicknameState === 'duplicated' || nicknameState === 'invalid'
                ? NICKNAME_MESSAGE[nicknameState]
                : undefined)
            }
          />

          <TextField
            label="한 줄 소개"
            value={introduction}
            maxLength={INTRODUCTION_MAX}
            placeholder="어떤 영화를 좋아하는지 한 줄로 적어 주세요"
            onChange={(event) => setIntroduction(event.target.value)}
            trailing={`${[...introduction].length}/${INTRODUCTION_MAX}`}
            hint="공개 프로필에 보여요"
          />
        </section>

        <section className={styles.group}>
          <h2 className={styles.groupLabel}>내 취향</h2>

          {genres.length > 0 ? (
            <div className={styles.chips}>
              {genres.map((genre, index) => (
                <Chip key={genre.code} variant={index === 0 ? 'solid' : 'outline'}>
                  {genre.name}
                </Chip>
              ))}
            </div>
          ) : null}

          {/* 취향은 고르는 것이 아니라 쌓이는 것이다(FR-07). 어디서 오는지 적는다 */}
          <p className={styles.note}>평가가 쌓이면 자동으로 바뀌어요</p>
        </section>

        <section className={styles.group}>
          <h2 className={styles.groupLabel}>계정</h2>

          <dl className={styles.readonly}>
            <div className={styles.readonlyRow}>
              <dt>이메일</dt>
              <dd>{profile.email}</dd>
            </div>
            <div className={styles.readonlyRow}>
              <dt>연령대</dt>
              <dd>{formatAgeGroup(profile.birthDate)}</dd>
            </div>
            <div className={styles.readonlyRow}>
              <dt>성별</dt>
              <dd>{GENDER_LABEL[profile.gender]}</dd>
            </div>
          </dl>

          <p className={styles.note}>이메일과 기본 정보는 설정 &gt; 계정에서 바꿀 수 있어요</p>
        </section>
      </div>

      <div className={styles.footer}>
        {/*
         * 미리보기는 저장된 값을 보여준다. 주 동작(저장)과 구분되도록 보조 버튼이다.
         * 화면의 검정 알약은 저장 하나다.
         */}
        <Button variant="secondary" onClick={() => void navigate(path.profilePreview())}>
          내 공개 프로필 미리보기
        </Button>

        <Button onClick={handleSave}>저장</Button>
      </div>

      {saved ? (
        <div className={styles.snackbarSlot}>
          <Snackbar>프로필을 저장했어요</Snackbar>
        </div>
      ) : null}
    </>
  );
}
