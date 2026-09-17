/*
 * 5.8 파티원 팝콘 한눈에 보기
 *
 * 요소와 순서는 와이어프레임 5.8 그대로다(screen-spec.md 7장 5.8).
 * 제목·파티 정보 · 멤버 카드 · 서로 겹치는 맛 · 팝콘 섞기 보기
 *
 * 연동 API는 API-057 파티원 팝콘 조회다. 응답에 파티 이름이 없어 부제의 이름은
 * API-050(`getMyParties`)에서 가져온다.
 *
 * 계약에 산출 규칙이 없는 두 값은 화면에서 계산한다.
 * - `대표 맛` = 알이 가장 많은 맛. API-057에 대표 맛 필드가 없다
 * - `서로 겹치는 맛` = 파티원 모두의 상위 3개 맛에 들어간 맛. 기준 자체는 와이어프레임의
 *   설명 문구(`두 사람 모두 상위 3개 맛에 포함돼요`)가 적고 있어 그대로 따랐다
 *
 * 와이어프레임은 멤버 2명 기준이고 2.1은 같은 파티를 멤버 4명으로 적고 있다. 목업 파티가
 * 하나뿐이라 2.1 기준 4명을 쓰기로 했고(팀 결정 2026-09-07), 이 화면은 멤버 수에 무관하게
 * 렌더한다. 그래서 설명 문구도 `파티원 모두 상위 3개 맛에 포함돼요`로 일반화했다.
 *
 * 첫 멤버 카드만 펼친 상태로 시작한다. 와이어프레임이 첫 카드를 펼쳐 두었다.
 *
 * 팀 결정 2026-09-10 (업무 -320)
 * - **멤버마다 팝콘 통을 그린다.** 이전에는 맛 분포를 누적 색 막대와 8종 색 스와치로
 *   보여줬다. 색 라벨을 읽어야 분포를 알 수 있는 구조인데, 이 화면이 보여줄 것은
 *   "저 사람 통에 무엇이 담겼나"이지 색표가 아니다. -101에서 구운 3D 통과 알을
 *   `PopcornBucket`으로 쌓아 통에 팝콘이 얹힌 그림으로 바꿨다
 * - 그 결과 **어두운 판이 필요 없어졌다.** 색 막대·스와치를 쓸 때는 시안 2.5장에 따라
 *   어두운 블록이 필수였는데, 3D 렌더는 3.2·5.9와 같이 밝은 벽에서 읽힌다. 밝은 면에
 *   놀 수 없던 것이 사라지니 판도 사라진다
 * - 펼친 상태의 8종 맛 개수는 남긴다(와이어프레임 요소). 다만 색 점에는 테두리를 둔다.
 *   밝은 면에서 8색은 1.01~2.55:1이라 면만으로는 안 보인다(3.1 범례와 같은 처리)
 * - 카드 테두리를 걷어냈다. 홈·취향 필름·영화 상세와 같이 카드 없는 섹션으로 둔다
 * - `서로 겹치는 맛`은 맛 이름만 쓴다
 *
 * **파티원 수 배치 규칙** (팀 결정 2026-09-10): 멤버는 세로로 순서대로 쌓는다. 파티는
 * 최대 4명이므로(FR-20) 인원이 늘어도 규칙이 바뀌지 않고 목록이 길어질 뿐이다. 첫 카드만
 * 펼친 상태로 시작하는 것도 인원과 무관하다.
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import PopcornBucket from '../../../components/PopcornBucket';
import { kernelCounts } from '../../../components/PopcornBucket/kernels';
import { ApiError } from '../../../api/apiError';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ChevronDownIcon, ChevronRightIcon, ChevronUpIcon } from '../../../components/Icon';
import {
  closeParty,
  getParty,
  getPartyPopcorn,
  kickPartyMember,
  leaveParty,
} from '../../../api/parties';
import { getMyProfile } from '../../../api/users';
import { getPopcornFlavors } from '../../../api/taste';
import { path } from '../../../routes/paths';
import styles from './PartyPopcornPage.module.css';
import type { Party, PartyMemberPopcorn, PartyPopcorn } from '../../../types/party';
import type { PopcornFlavor } from '../../../types/taste';

/** 겹치는 맛을 고르는 기준. 와이어프레임 설명 문구가 `상위 3개 맛`으로 적고 있다 */
const TOP_FLAVOR_COUNT = 3;

/**
 * 멤버 통이 그려지는 폭(px). 네 명이 세로로 쌓이므로 3.2(196px)보다 작다.
 * 이름·대표 맛이 옆에 붙을 폭을 남기는 값이다.
 */
const BUCKET_WIDTH = 132;

/**
 * 되돌릴 수 없는 파티 동작. 누르면 곧바로 실행하지 않고 한 번 더 묻는다.
 *
 * API-069 처리 규칙이 `클라이언트는 실행 전에 대상 닉네임과 강퇴 사실을 확인시킨다`고
 * 적고 있고, 나가기 · 종료도 되돌리는 계약이 없어 같은 규칙을 쓴다.
 */
type PendingAction =
  { type: 'leave' } | { type: 'close' } | { type: 'kick'; userId: number; nickname: string };

/** 확인 문구. 무엇이 일어나는지와 돌이킬 수 없다는 것을 함께 적는다 */
function confirmMessage(action: PendingAction): string {
  if (action.type === 'leave') {
    return '파티에서 나갈까요? 파티원 팝콘을 더 볼 수 없어요.';
  }

  if (action.type === 'close') {
    return '파티를 종료할까요? 파티원 모두가 나가게 되고 되돌릴 수 없어요.';
  }

  return `${action.nickname} 님을 파티에서 내보낼까요?`;
}

/** 알이 가장 많은 맛의 코드 */
function topFlavorCodes(member: PartyMemberPopcorn, count: number): string[] {
  return [...member.flavors]
    .sort((a, b) => b.popcornCount - a.popcornCount)
    .slice(0, count)
    .map((flavor) => flavor.flavorCode);
}

export default function PartyPopcornPage() {
  const { partyId } = useParams();
  const navigate = useNavigate();

  const id = Number(partyId);

  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [party, setParty] = useState<Party | null>(null);
  const [myUserId, setMyUserId] = useState<number | null>(null);
  const [flavors, setFlavors] = useState<PopcornFlavor[]>([]);
  const [expanded, setExpanded] = useState<number[]>([]);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [managing, setManaging] = useState(false);
  const [manageError, setManageError] = useState<string | undefined>();

  useEffect(() => {
    let active = true;

    getPartyPopcorn(id)
      .then((value) => {
        if (active) {
          setPopcorn(value);
          setFailed(false);
          // 와이어프레임은 첫 카드를 펼쳐 두었다
          setExpanded(value.members[0] ? [value.members[0].user.userId] : []);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    /*
     * 아래 세 가지는 실패해도 본문을 막지 않는다. 이름 · 파티 관리 · `나` 표시 · 맛
     * 이름만 비워진다. 파티 관리는 파티장이 누구인지 알아야 그릴 수 있어 API-050이
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

  /** 확인을 받은 동작을 실제로 보낸다 */
  function run(action: PendingAction) {
    setManaging(true);
    setManageError(undefined);

    const sent =
      action.type === 'leave'
        ? leaveParty(id)
        : action.type === 'close'
          ? closeParty(id)
          : kickPartyMember(id, action.userId);

    sent
      .then(() => {
        setPending(null);

        // 나가기 · 종료는 이 파티를 더 볼 수 없다. 강퇴는 남은 파티원을 다시 읽는다
        if (action.type === 'kick') {
          setManaging(false);
          setReloadCount((count) => count + 1);

          return;
        }

        void navigate(path.party(), { replace: true });
      })
      .catch((cause: unknown) => {
        setManaging(false);
        setManageError(
          cause instanceof ApiError
            ? cause.message
            : '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.',
        );
      });
  }

  function toggle(userId: number) {
    setExpanded((current) =>
      current.includes(userId) ? current.filter((value) => value !== userId) : [...current, userId],
    );
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="파티원 팝콘 한눈에 보기" showBack />

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
        <ScreenHeader title="파티원 팝콘 한눈에 보기" showBack />
      </>
    );
  }

  const flavorByCode = new Map(flavors.map((flavor) => [flavor.code, flavor]));
  // 파티장만 종료(API-056)와 내보내기(API-069)를 부를 수 있다
  const isOwner = party !== null && myUserId !== null && party.owner.userId === myUserId;

  // 파티원 모두의 상위 3개 맛에 들어간 맛
  const shared = popcorn.members.length
    ? popcorn.members
        .map((member) => topFlavorCodes(member, TOP_FLAVOR_COUNT))
        .reduce((acc, codes) => acc.filter((code) => codes.includes(code)))
    : [];

  return (
    <>
      <ScreenHeader
        title="파티원 팝콘 한눈에 보기"
        subtitle={`${party?.name ?? ''} · 멤버 ${popcorn.members.length}명`}
        showBack
      />

      <div className={styles.page}>
        {popcorn.members.map((member) => {
          const isOpen = expanded.includes(member.user.userId);
          const isMe = member.user.userId === myUserId;
          const leadCode = topFlavorCodes(member, 1)[0];
          const leadName = leadCode ? (flavorByCode.get(leadCode)?.displayName ?? '') : '';

          return (
            <section key={member.user.userId} className={styles.memberCard}>
              {/*
               * 이 멤버의 통. 맛 분포대로 알이 쌓이므로 무엇이 담겼는지 그림으로 보인다.
               * 통과 알은 -101에서 구운 3D 스프라이트다.
               */}
              <button
                type="button"
                className={styles.memberHead}
                aria-expanded={isOpen}
                onClick={() => toggle(member.user.userId)}
              >
                <PopcornBucket
                  flavors={kernelCounts(member.flavors)}
                  width={BUCKET_WIDTH}
                  label={`${member.user.nickname}의 팝콘 ${member.totalCount}알`}
                />

                <span className={styles.memberInfo}>
                  <span className={styles.memberName}>
                    {member.user.nickname}
                    {isMe ? ' (나)' : ''}
                  </span>
                  <span className={styles.memberMeta}>대표 맛 {leadName}</span>
                  <span className={styles.memberCount}>팝콘 {member.totalCount}알</span>

                  <span className={styles.expandHint}>
                    {isOpen ? '접기' : '펼쳐서 8가지 맛 개수 보기'}
                    <span className={styles.memberChevron} aria-hidden="true">
                      {isOpen ? <ChevronUpIcon /> : <ChevronDownIcon />}
                    </span>
                  </span>
                </span>
              </button>

              {/*
               * 7.2-B 다른 사용자 공개 프로필로 들어가는 자리 (업무 -305).
               *
               * 와이어프레임 이동표에 7.2-B로 들어오는 화살표가 아예 없었고, 5.8에서
               * 들어가기로 확정했다(2026-09-11, screen-spec-my.md 2장 8번).
               *
               * 통 전체를 링크로 만들지 않았다. 통은 이미 펼치기 버튼이라 같은 자리에
               * 동작이 둘이 되면 어느 쪽이 일어날지 알 수 없다. 별도 줄로 둔다.
               *
               * 내 카드에는 두지 않는다. 내 공개 프로필은 7.2-A로 보는 것이고, 여기서
               * 7.2-B로 들어가면 `다른 사용자` 화면에서 나를 보게 된다.
               */}
              {isMe ? null : (
                <Link to={path.publicProfile(member.user.userId)} className={styles.profileLink}>
                  {member.user.nickname} 님의 취향 보기
                  <ChevronRightIcon />
                </Link>
              )}

              {/*
               * API-069 멤버 강퇴. 파티장에게만, 자기 자신이 아닌 멤버에게만 보인다.
               * 계약도 자기 강퇴를 `400 VALIDATION_FAILED`로 막는다(자신은 API-055로 나간다).
               */}
              {isOwner && !isMe ? (
                <button
                  type="button"
                  className={styles.kickButton}
                  disabled={managing}
                  onClick={() =>
                    setPending({
                      type: 'kick',
                      userId: member.user.userId,
                      nickname: member.user.nickname,
                    })
                  }
                >
                  내보내기
                </button>
              ) : null}

              {isOpen ? (
                <ul className={styles.flavorList}>
                  {flavors.map((flavor) => (
                    <li key={flavor.code} className={styles.flavorItem}>
                      <span
                        className={styles.swatch}
                        style={{ background: flavor.color }}
                        aria-hidden="true"
                      />
                      <span className={styles.flavorText}>
                        {flavor.displayName}{' '}
                        {member.flavors.find((item) => item.flavorCode === flavor.code)
                          ?.popcornCount ?? 0}
                        알
                      </span>
                    </li>
                  ))}
                </ul>
              ) : null}
            </section>
          );
        })}

        <section className={styles.sharedCard}>
          <h2 className={styles.sharedTitle}>서로 겹치는 맛</h2>

          <div className={styles.sharedTags}>
            {shared.map((code) => (
              <span key={code} className={styles.sharedTag}>
                {flavorByCode.get(code)?.displayName ?? code}
              </span>
            ))}
          </div>

          <p className={styles.sharedNote}>파티원 모두 상위 3개 맛에 포함돼요</p>
        </section>

        <Button onClick={() => void navigate(path.partyPopcornMix(id))}>팝콘 섞기 보기</Button>

        {/*
         * 파티 관리. **와이어프레임 5.1~5.9에 진입점이 없다.**
         *
         * 나가기(API-055) · 종료(API-056) · 내보내기(API-069)는 계약에 있는데 누를 자리가
         * 없어 업무 -304가 미뤄 두었다. 파티 하나의 문맥과 파티원 목록이 함께 있는 화면이
         * 여기뿐이라 5.8에 두기로 했다(사용자 결정 2026-09-16). 5.1은 목록만 보여준다.
         *
         * 셋 다 되돌릴 계약이 없어 누르면 바로 보내지 않고 확인을 한 번 받는다.
         */}
        <section className={styles.manageCard}>
          <h2 className={styles.manageTitle}>파티 관리</h2>

          {pending ? (
            <div className={styles.confirm} role="alert">
              <p className={styles.confirmText}>{confirmMessage(pending)}</p>

              <div className={styles.confirmActions}>
                <button
                  type="button"
                  className={styles.confirmYes}
                  disabled={managing}
                  onClick={() => run(pending)}
                >
                  네, 할게요
                </button>

                <button
                  type="button"
                  className={styles.confirmNo}
                  disabled={managing}
                  onClick={() => setPending(null)}
                >
                  취소
                </button>
              </div>
            </div>
          ) : (
            <div className={styles.manageActions}>
              <button
                type="button"
                className={styles.manageButton}
                disabled={managing}
                onClick={() => setPending({ type: 'leave' })}
              >
                파티 나가기
              </button>

              {isOwner ? (
                <button
                  type="button"
                  className={styles.manageButton}
                  disabled={managing}
                  onClick={() => setPending({ type: 'close' })}
                >
                  파티 종료하기
                </button>
              ) : null}
            </div>
          )}

          {manageError ? (
            <p className={styles.manageError} role="alert">
              {manageError}
            </p>
          ) : null}
        </section>
      </div>
    </>
  );
}
