/*
 * 5.8 파티원 팝콘
 *
 * 이동표
 * 5.1 → 5.8 참여 중인 파티방 선택
 * 5.8 → 5.9 함께 볼 영화 추천받기 (파티팝콘 흐름의 입구)
 * 5.8 → 5.10 지난 추천 보기 (업무 -826)
 * 5.8 ⇢ 7.8-D 사용자 차단 후 차단 사용자 관리
 *
 * **사용자 시안 2026-09-17(업무 -462)이 구성을 바꿨다.** 이전에는 파티원마다 통을 하나씩
 * 세로로 쌓아 한 화면에서 모두 보여주고, 아래에 `서로 겹치는 맛`과 `파티 관리`를 두었다.
 * 시안은 **친구를 고르면 그 사람의 통 하나를 크게** 보여준다 — 이 화면이 하는 일이
 * `친구의 영화 취향을 구경`하는 것이므로 한 번에 한 사람이다.
 *
 * 함께 달라진 것
 * - `서로 겹치는 맛`을 뺐다. 시안에 없고, 파티원 모두의 상위 3개 맛을 교집합한 값이라
 *   화면이 만들어 낸 기준이었다. 겹치는 취향은 5.9 · 5.5가 흐름으로 말한다
 * - `파티 관리`(나가기 · 종료 · 내보내기)를 헤더의 `···` 메뉴로 옮겼다. 시안이 헤더에
 *   그 자리를 두었고, 관리는 이 화면이 늘 펼쳐 둘 일이 아니다. 진입점이 5.8뿐이라는
 *   결정(2026-09-16)은 그대로다
 * - `친구 초대하기`가 생겼다. 계약에는 있는데(API-051) 파티 안에서 초대할 자리가
 *   없었다 — 5.3이 `파티 안에서 다시 초대할 수 있다`고 적어 둔 그 자리다
 *
 * **업무 -598에서 `취향 보기`를 걷어냈다**(2026-09-18 사용자 결정). 그때 7.2-B는 목업이라
 * 실제 파티원 번호로는 늘 404였다(2026-09-17 여정 QA). **지금은 7.2-B가 실제 API-062를
 * 부르지만(업무 -810) 여전히 감춘다** — 7.2-B 주소는 `publicId`(UUID)로 여는데 API-057은
 * 파티원의 `publicId`를 주지 않는다. 비활성 상태로 남기지 않는다 — 왜 못 누르는지 적을
 * 자리가 시안에 없고, `publicId`가 오면 되돌리는 것도 한 줄이다.
 *
 * 그 링크가 7.2-B로 가는 **유일한 진입점**이었고, 차단(API-020)을 거는 자리도 7.2-B의
 * ⋯ 메뉴뿐이었다(7.8-D에는 해제만 있다). 링크만 감추면 차단을 걸 방법이 사라지므로
 * **차단과 신고를 이 화면의 ⋯ 메뉴로 옮겼다**(사용자 결정 2026-09-18). 대상은 고른
 * 친구다 — 내보내기가 이미 그렇게 동작한다. 여기 `userId`는 API-057이 준 실제 번호라,
 * 목업 번호로 404가 나던 7.2-B와 달리 차단이 실제로 걸린다.
 *
 * 연동 API는 API-057 파티원 팝콘 조회다. 응답에 파티 이름이 없어 헤더의 이름은
 * API-050(`getMyParties`)에서 가져온다.
 *
 * `대표 맛`은 알이 가장 많은 맛이다. API-057에 대표 맛 필드가 없어 화면에서 고른다.
 *
 * 펼친 상태의 8종 맛 개수는 남긴다. **알 수는 그림이 아니라 범례가 말한다**
 * (`design-concept.md` 2.10) — 통은 30알로 요약해 그리므로 실제 개수는 이 목록에 있다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Avatar from '../../../components/Avatar';
import Button from '../../../components/Button';
import ConfirmDialog from '../../../components/ConfirmDialog';
import IconButton from '../../../components/IconButton';
import Menu from '../../../components/Menu';
import PartyInviteSearch from '../../../components/PartyInviteSearch';
import PopcornBucket from '../../../components/PopcornBucket';
import { kernelCounts } from '../../../components/PopcornBucket/kernels';
import ScreenHeader from '../../../components/ScreenHeader';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import { ChevronDownIcon, ChevronUpIcon, MoreVerticalIcon } from '../../../components/Icon';
import { ApiError } from '../../../api/apiError';
import {
  closeParty,
  getParty,
  getPartyPopcorn,
  inviteToParty,
  kickPartyMember,
  leaveParty,
  PARTY_MAX_MEMBERS,
  PARTY_MIN_MEMBERS,
} from '../../../api/parties';
import { blockUser } from '../../../api/settings';
import { getMyProfile } from '../../../api/users';
import { getPopcornFlavors } from '../../../api/taste';
import { path } from '../../../routes/paths';
import styles from './PartyPopcornPage.module.css';
import type { Party, PartyMemberPopcorn, PartyPopcorn } from '../../../types/party';
import type { PopcornFlavor } from '../../../types/taste';
import type { SearchedUser } from '../../../types/user';

/** 고른 친구의 통. 한 사람만 보여주므로 3.2와 같은 폭이다 */
const BUCKET_WIDTH = 196;

/** 친구 칩의 아바타 크기 */
const AVATAR_SIZE = 30;

/** 헤더 메뉴가 붙는 세로 위치(px). 7.2-B와 같이 헤더 아래에 붙는다 */
const MENU_TOP = 76;

/**
 * 되돌릴 수 없는 동작. 누르면 곧바로 실행하지 않고 한 번 더 묻는다.
 *
 * API-069 처리 규칙이 `클라이언트는 실행 전에 대상 닉네임과 강퇴 사실을 확인시킨다`고
 * 적고 있고, 나가기 · 종료도 되돌리는 계약이 없어 같은 규칙을 쓴다. 차단은 API-020이
 * `클라이언트는 차단 전에 공동 파티 탈퇴와 파티장 변경·종료 가능성을 안내한다`고 적었다.
 */
type PendingAction =
  | { type: 'leave' }
  | { type: 'close' }
  | { type: 'kick'; userId: number; nickname: string }
  | { type: 'block'; userId: number; nickname: string };

/** 확인 문구. 무엇이 일어나는지와 돌이킬 수 없다는 것을 함께 적는다 */
function confirmCopy(action: PendingAction): {
  title: string;
  description: string;
  confirmLabel: string;
} {
  if (action.type === 'leave') {
    return {
      title: '파티에서 나갈까요?',
      description: '파티원 팝콘을 더 볼 수 없어요.',
      confirmLabel: '나가기',
    };
  }

  if (action.type === 'close') {
    return {
      title: '파티를 종료할까요?',
      description: '파티원 모두가 나가게 되고 되돌릴 수 없어요.',
      confirmLabel: '종료하기',
    };
  }

  if (action.type === 'kick') {
    return {
      title: `${action.nickname} 님을 내보낼까요?`,
      description: '다시 초대해야 들어올 수 있어요.',
      confirmLabel: '내보내기',
    };
  }

  // 7.2-B가 쓰던 문구 그대로다. 같은 동작을 두 화면이 다르게 설명할 이유가 없다
  return {
    title: `${action.nickname} 님을 차단할까요?`,
    description:
      '서로의 프로필·취향 필름·팝콘·한줄평이 보이지 않아요. 함께 있는 파티에서는 나가게 돼요.',
    confirmLabel: '차단하기',
  };
}

/** 알이 가장 많은 맛의 코드 */
function topFlavorCode(member: PartyMemberPopcorn): string | undefined {
  return [...member.flavors].sort((a, b) => b.popcornCount - a.popcornCount)[0]?.flavorCode;
}

export default function PartyPopcornPage() {
  const { partyId } = useParams();
  const navigate = useNavigate();

  const id = Number(partyId);

  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [party, setParty] = useState<Party | null>(null);
  const [myUserId, setMyUserId] = useState<number | null>(null);
  const [flavors, setFlavors] = useState<PopcornFlavor[]>([]);
  const [pickedUserId, setPickedUserId] = useState<number | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviting, setInviting] = useState(false);
  const [notice, setNotice] = useState('');
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [managing, setManaging] = useState(false);

  useEffect(() => {
    let active = true;

    getPartyPopcorn(id)
      .then((value) => {
        if (!active) return;

        setPopcorn(value);
        setFailed(false);
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    /*
     * 아래 세 가지는 실패해도 본문을 막지 않는다. 파티 이름 · 파티 관리 · `나` 표시 ·
     * 맛 이름만 비워진다. 파티 관리는 파티장이 누구인지 알아야 그릴 수 있어 API-050이
     * 실패하면 나오지 않는다.
     */
    getParty(id)
      .then((value) => {
        if (active) {
          setParty(value);
        }
      })
      .catch(() => undefined);

    getMyProfile()
      .then((profile) => {
        if (active) {
          setMyUserId(profile.userId);
        }
      })
      .catch(() => undefined);

    /*
     * 집계(API-057)와 기준정보(API-044)가 모두 실제 응답이므로 맛 코드가 같은 곳에서
     * 온다. 기준정보가 비어 오면 맛 이름 자리에 코드가 남는다(`api/taste.ts`).
     */
    getPopcornFlavors()
      .then((value) => {
        if (active) {
          setFlavors(value);
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  /** 확인받은 동작을 계약에 맞는 호출로 보낸다 */
  function call(action: PendingAction): Promise<unknown> {
    if (action.type === 'leave') {
      return leaveParty(id);
    }

    if (action.type === 'close') {
      return closeParty(id);
    }

    if (action.type === 'kick') {
      return kickPartyMember(id, action.userId);
    }

    return blockUser(action.userId);
  }

  /** 확인을 받은 동작을 실제로 보낸다 */
  function run(action: PendingAction) {
    setManaging(true);

    call(action)
      .then(() => {
        setPending(null);

        // 강퇴만 이 화면에 남는다. 남은 파티원을 다시 읽는다
        if (action.type === 'kick') {
          setManaging(false);
          setPickedUserId(null);
          setReloadCount((count) => count + 1);

          return;
        }

        /*
         * 차단은 7.8-D로 보낸다(이동표 14번, 7.2-B와 같은 이동). 차단한 사람의 프로필은
         * 404이고 공동 파티에서도 빠지므로(API-020) 이 화면으로 돌아올 자리가 없다.
         * 나가기 · 종료는 이 파티를 더 볼 수 없으니 5.1로 돌아간다.
         */
        void navigate(action.type === 'block' ? path.blockedUsers() : path.party(), {
          replace: true,
        });
      })
      .catch((cause: unknown) => {
        /*
         * 이미 차단한 사람이면(409 `ALREADY_BLOCKED`) 바라던 상태이므로 성공과 같이
         * 7.8-D로 보낸다. 두 탭에서 같은 파티를 열어 둔 경우에 난다(7.2-B와 같은 판정).
         */
        if (
          action.type === 'block' &&
          cause instanceof ApiError &&
          cause.code === 'ALREADY_BLOCKED'
        ) {
          setPending(null);
          void navigate(path.blockedUsers(), { replace: true });

          return;
        }

        setManaging(false);
        setPending(null);
        setNotice(
          cause instanceof ApiError
            ? cause.message
            : '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.',
        );
      });
  }

  /**
   * 고른 친구에게 곧바로 초대를 보낸다.
   *
   * 5.3과 달리 파티가 이미 있어 한 사람씩 바로 보낼 수 있다. 정원이 차면 계약이
   * `PARTY_FULL`로 거절하므로(API-051) 화면에서 미리 막지 않고 그 문구를 보여준다.
   */
  function invite(user: SearchedUser) {
    setInviting(true);

    inviteToParty(id, user.userId)
      .then(() => {
        setInviting(false);
        setInviteOpen(false);
        setNotice(`${user.nickname} 님에게 초대를 보냈어요`);
      })
      .catch((cause: unknown) => {
        setInviting(false);
        setNotice(
          cause instanceof ApiError
            ? cause.message
            : '초대를 보내지 못했어요. 잠시 후 다시 시도해 주세요.',
        );
      });
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="파티원 팝콘" showBack fallbackTo={path.party()} />

        <StatusView
          title="파티 정보를 불러오지 못했어요"
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

  if (!popcorn) {
    return (
      <>
        <ScreenHeader title="파티원 팝콘" showBack fallbackTo={path.party()} />
      </>
    );
  }

  const flavorByCode = new Map(flavors.map((flavor) => [flavor.code, flavor]));
  // 파티장만 종료(API-056)와 내보내기(API-069)를 부를 수 있다
  const isOwner = party !== null && myUserId !== null && party.owner.userId === myUserId;

  /*
   * 고른 친구가 없으면 첫 파티원을 본다. 와이어프레임도 첫 카드를 펼쳐 두었고 시안도
   * 첫 칩이 선택된 상태다. 강퇴 뒤에 없는 사람이 남지 않도록 목록에서 다시 찾는다.
   */
  const picked =
    popcorn.members.find((member) => member.user.userId === pickedUserId) ?? popcorn.members[0];
  const isMine = picked !== undefined && picked.user.userId === myUserId;
  const leadCode = picked ? topFlavorCode(picked) : undefined;
  const leadName = leadCode ? (flavorByCode.get(leadCode)?.displayName ?? leadCode) : '';

  return (
    <>
      <ScreenHeader
        title={party?.name ?? '파티원 팝콘'}
        showBack
        fallbackTo={path.party()}
        action={
          <IconButton
            label="파티 관리"
            icon={<MoreVerticalIcon />}
            onClick={() => setMenuOpen(true)}
          />
        }
      />

      <div className={styles.page}>
        <div className={styles.friendsHead}>
          <h2 className={styles.label}>함께하는 친구들</h2>
          <p className={styles.count}>{popcorn.members.length}명</p>
        </div>

        {/*
         * 친구 칩. 시안대로 사진 아바타다 — API-057 `user.avatarUrl`(업무 -811 · -762). 사진이
         * 없는 사람은 Avatar의 무채색 자리표시자다. 닉네임에서 밝기를 뽑으므로 서로 구분된다.
         */}
        <ul className={styles.friends}>
          {popcorn.members.map((member) => {
            const isPicked = picked?.user.userId === member.user.userId;

            return (
              <li key={member.user.userId}>
                <button
                  type="button"
                  className={styles.friend}
                  data-picked={isPicked}
                  aria-pressed={isPicked}
                  onClick={() => {
                    setPickedUserId(member.user.userId);
                    setExpanded(false);
                  }}
                >
                  <Avatar
                    size={AVATAR_SIZE}
                    src={member.user.avatarUrl}
                    nickname={member.user.nickname}
                  />
                  <span className={styles.friendName}>{member.user.nickname}</span>
                </button>
              </li>
            );
          })}
        </ul>

        {picked ? (
          <section className={styles.showcase}>
            <h1 className={styles.title}>{picked.user.nickname}의 팝콘</h1>
            <p className={styles.subtitle}>
              {isMine ? '내 영화 취향이 담긴 통이에요' : '친구의 영화 취향을 구경해요'}
            </p>

            <PopcornBucket
              flavors={kernelCounts(picked.flavors)}
              width={BUCKET_WIDTH}
              label={`${picked.user.nickname}의 팝콘 ${picked.totalCount}알`}
            />

            <p className={styles.meta}>
              대표 맛 {leadName} · 팝콘 {picked.totalCount}알
            </p>

            <button
              type="button"
              className={styles.expand}
              aria-expanded={expanded}
              onClick={() => setExpanded((value) => !value)}
            >
              {expanded ? '접기' : '펼쳐서 8가지 맛 개수 보기'}
              <span className={styles.chevron} aria-hidden="true">
                {expanded ? <ChevronUpIcon /> : <ChevronDownIcon />}
              </span>
            </button>

            {expanded ? (
              <ul className={styles.flavorList}>
                {flavors.map((flavor) => (
                  <li key={flavor.code} className={styles.flavorItem}>
                    {/*
                     * 밝은 면에서 8색은 1.01~2.55:1이라 면만으로는 안 보인다.
                     * 3.1 범례와 같이 테두리를 둔다(`design-concept.md` 2.5)
                     */}
                    <span
                      className={styles.swatch}
                      style={{ background: flavor.color }}
                      aria-hidden="true"
                    />
                    <span className={styles.flavorText}>
                      {flavor.displayName}{' '}
                      {picked.flavors.find((item) => item.flavorCode === flavor.code)
                        ?.popcornCount ?? 0}
                      알
                    </span>
                  </li>
                ))}
              </ul>
            ) : null}
          </section>
        ) : null}

        {inviteOpen ? (
          <section className={styles.invite}>
            <h2 className={styles.label}>친구 초대</h2>

            <PartyInviteSearch
              disabled={inviting}
              pickLabel={() => '초대'}
              onPick={invite}
              note={`파티는 파티장 포함 최대 ${PARTY_MAX_MEMBERS}명까지예요`}
            />
          </section>
        ) : null}

        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => setInviteOpen((value) => !value)}>
            {inviteOpen ? '초대 접기' : '친구 초대하기'}
          </Button>

          {/*
           * 초대를 거절당하거나 파티원이 나가 나 혼자 남으면 추천을 받을 수 없다
           * (`PARTY_MIN_MEMBERS`, 업무 -610). 5.9 이후는 여러 사람의 팝콘을 섞는 흐름이다.
           */}
          {popcorn.members.length < PARTY_MIN_MEMBERS ? (
            <p className={styles.meta} role="status">
              파티원이 {PARTY_MIN_MEMBERS}명 이상이어야 함께 볼 영화를 추천받을 수 있어요
            </p>
          ) : null}

          <Button
            variant="brand"
            disabled={popcorn.members.length < PARTY_MIN_MEMBERS}
            onClick={() => void navigate(path.partyPopcornMix(id))}
          >
            함께 볼 영화 추천받기
          </Button>

          {/*
           * 5.10 파티 추천 이력(업무 -826). 5.5는 지금 묶음 하나만 보여 주므로 이 파티가
           * 지금까지 본 영화 전부를 볼 자리가 여기 말고는 없다. 끝난 파티는 5.1의
           * `지난 파티`로 들어가고 이 화면을 지나지 않는다.
           */}
          <Button variant="text" onClick={() => void navigate(path.partyRecommendationHistory(id))}>
            지난 추천 보기
          </Button>
        </div>
      </div>

      {menuOpen ? (
        <Menu label="파티 관리" top={MENU_TOP} onClose={() => setMenuOpen(false)}>
          <button
            type="button"
            className={styles.menuItem}
            disabled={managing}
            onClick={() => {
              setMenuOpen(false);
              setPending({ type: 'leave' });
            }}
          >
            파티 나가기
          </button>

          {isOwner ? (
            <button
              type="button"
              className={styles.menuItem}
              disabled={managing}
              onClick={() => {
                setMenuOpen(false);
                setPending({ type: 'close' });
              }}
            >
              파티 종료하기
            </button>
          ) : null}

          {isOwner && picked && !isMine ? (
            <button
              type="button"
              className={styles.menuItem}
              disabled={managing}
              onClick={() => {
                setMenuOpen(false);
                setPending({
                  type: 'kick',
                  userId: picked.user.userId,
                  nickname: picked.user.nickname,
                });
              }}
            >
              {picked.user.nickname} 님 내보내기
            </button>
          ) : null}

          {/*
           * 차단(API-020)과 신고. 7.2-B의 ⋯ 메뉴에 있던 둘을 업무 -598에서 옮겨 왔다.
           * 파티장이 아니어도 보인다 — 차단은 파티 권한과 무관하다.
           *
           * 내 팝콘을 고른 동안에는 두지 않는다. 계약도 자기 차단을 `400
           * CANNOT_BLOCK_SELF`로 막는다.
           */}
          {picked && !isMine ? (
            <>
              <button
                type="button"
                className={styles.menuItem}
                disabled={managing}
                onClick={() => {
                  setMenuOpen(false);
                  setPending({
                    type: 'block',
                    userId: picked.user.userId,
                    nickname: picked.user.nickname,
                  });
                }}
              >
                {picked.user.nickname} 님 차단하기
              </button>

              {/* 신고는 계약이 없다. 7.2-B와 같이 안내만 하고 실제 처리는 하지 않는다 */}
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
            </>
          ) : null}
        </Menu>
      ) : null}

      {pending ? (
        <ConfirmDialog
          {...confirmCopy(pending)}
          busy={managing}
          onConfirm={() => run(pending)}
          onCancel={() => setPending(null)}
        />
      ) : null}

      {notice ? (
        <Snackbar actionLabel="확인" onAction={() => setNotice('')}>
          {notice}
        </Snackbar>
      ) : null}
    </>
  );
}
