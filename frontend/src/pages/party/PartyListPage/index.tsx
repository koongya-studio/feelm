/*
 * 5.1 파티 시작 (참여 중인 파티 목록)
 *
 * 이동표
 * 5.1 → 5.2 파티 만들기
 * 5.1 → 5.8 참여 중인 파티방 선택
 *
 * **사용자 시안 2026-09-17(업무 -462)이 이 화면의 머리를 바꿨다.** 이전에는 `내 파티` ·
 * `참여 중인 방 N개`라는 목록 머리말이었고, 시안은 워드마크와 `파티팝콘`이라는 이름으로
 * 파티 모드가 무엇인지 먼저 말한다. 목록은 그 아래에 놓인다.
 *
 * **통은 파티가 있든 없든 그 자리에 있다.** 시안의 가운데를 통이 잡고 있고, 목록만
 * 두면 목록과 버튼 사이가 빈 화면이 된다. 목록은 제목 아래에 놓이고 통은 그 아래
 * 남은 자리를 쓴다. 파티는 최대 4개까지 흔치 않아 통이 밀리지 않는다.
 *
 * 통에는 **첫 파티의 팝콘**을 담는다(API-057). 빈 통을 두면 파티가 있는데도 아무것도
 * 담기지 않은 것으로 보인다. 파티가 여러 개일 때 어느 것을 담을지는 시안에 없어 목록의
 * 첫 파티를 쓰고, 통의 이름이 그 파티를 말한다. 파티가 없으면 빈 통이다.
 *
 * **`받은 초대` 구역은 업무 -595에서 넣었다.** 2026-09-17 여정 QA가 찾은 차단 결함이다 —
 * 초대는 서버에 생기는데(API-051) 받은 사람이 5.6으로 갈 길이 알림 목록 하나뿐이었고,
 * 그 목록은 전체가 목업이라 실제 초대 번호가 없었다. 초대받은 사람은 수락할 방법이
 * 없었다. 알림 연동(8.1)을 기다리지 않고 **이미 구현된 API-052를 이 화면에서 부른다**
 * (팀 결정 2026-09-18, `wireframe-change-requests.md` CR-27).
 *
 * - 구역은 참여 중인 파티 **위**에 둔다. 답해야 할 일이 이미 들어간 방보다 급하다
 * - `2인`은 **나까지 세었을 때의 인원**이다. API-052 `memberCount`는 지금 참여 중인
 *   사람 수라 1을 더해야 5.6의 카드와 같은 값이 된다
 * - 대기 중 초대가 없으면 구역을 그리지 않는다. 시안이 통에 준 자리를 빈 안내가
 *   밀어내고, 초대는 받지 않는 것이 보통 상태다
 * - API-052는 **대기 중(`PENDING`) 초대만** 준다. 만료·취소·이미 답한 초대는 목록에서
 *   그냥 빠진다 — 따로 표시할 값이 계약에 없다. 목록이 뜬 뒤에 사라진 초대를 눌렀다면
 *   5.6이 `초대를 확인하지 못했어요`로 받는다
 * - 초대를 못 받아 와도 파티 목록은 그대로 둔다. 둘은 다른 요청이다
 *
 * `[?]` **`초대 코드로 참여하기`는 계약에 없다.** API-051 초대는 닉네임 검색(API-019)으로
 * 찾은 `userId`를 받고, 코드나 링크로 참여하는 경로는 정의되어 있지 않다. 시안이 확정한
 * 자리이므로 버튼은 두고, 누르면 준비 중임을 알린다(`wireframe-change-requests.md` CR-26).
 *
 * 목록은 **활성 파티만** 보여준다(API-050 기본값). 나가거나 종료한 파티는 여기서 빠진다.
 *
 * `[?]` 와이어프레임은 파티마다 `케이퍼 · 스릴러` 같은 **공통 장르**를 보여주는데
 * API-050 응답에는 없다(이름 · 상태 · 멤버 · 정원뿐). 없는 값을 지어내지 않고 같은
 * 자리에 멤버 이름을 둔다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import PopcornBucket from '../../../components/PopcornBucket';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import AppHeader from '../../../components/AppHeader';
import { PlayIcon } from '../../../components/Icon';
import { getMyInvitations, getMyParties, getPartyPopcorn } from '../../../api/parties';
import { getPopcornFlavors } from '../../../api/taste';
import { path } from '../../../routes/paths';
import { mixedFlavors } from '../../../utils/party';
import type { Party, PartyInvitation, PartyPopcorn } from '../../../types/party';
import styles from './PartyListPage.module.css';

/** 강조 카드 오른쪽의 통. 시안의 크기다 */
const BUCKET_WIDTH = 128;

/** `우리의 공통 취향`에 세우는 맛 수. 셋을 넘으면 칩이 두 줄이 된다(시안) */
const COMMON_FLAVOR_COUNT = 3;

/**
 * 얼굴 자리.
 *
 * **프로필 사진이 계약에 없다** — 파티가 주는 것은 `userId`와 닉네임뿐이다(API-050 · API-052).
 * 시안의 얼굴 자리에 닉네임 첫 글자를 넣는다. 지어낸 사진을 두는 것보다 낫다.
 */
function Initials({
  names,
  rest = 0,
  tone,
  labelled = false,
}: {
  names: string[];
  rest?: number;
  tone?: 'dark';
  labelled?: boolean;
}) {
  return (
    <ul className={styles.initials} data-tone={tone ?? null} data-labelled={labelled}>
      {names.map((name) => (
        <li key={name} className={styles.initial}>
          <span className={styles.initialMark} aria-hidden="true">
            {[...name][0] ?? '?'}
          </span>
          {labelled ? <span className={styles.initialName}>{name}</span> : null}
          {labelled ? null : <VisuallyHidden>{name}</VisuallyHidden>}
        </li>
      ))}

      {rest > 0 ? (
        <li className={styles.initial}>
          <span className={styles.initialMark} data-rest="true">
            +{rest}
          </span>
        </li>
      ) : null}
    </ul>
  );
}

export default function PartyListPage() {
  const navigate = useNavigate();
  const [parties, setParties] = useState<Party[] | null>(null);
  const [invitations, setInvitations] = useState<PartyInvitation[] | null>(null);
  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [failed, setFailed] = useState(false);
  const [invitationsFailed, setInvitationsFailed] = useState(false);
  const [notice, setNotice] = useState('');
  /** 맛 코드 → 이름. 파티 팝콘(API-057)은 코드만 주므로 이름표가 따로 필요하다 */
  const [flavorNames, setFlavorNames] = useState<Map<string, string>>(new Map());
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    // 이름표를 못 받아도 화면은 그대로 선다. 칩만 서지 않는다
    getPopcornFlavors()
      .then((flavors) => {
        if (active) setFlavorNames(new Map(flavors.map((f) => [f.code, f.displayName])));
      })
      .catch(() => {
        // 그대로 둔다
      });

    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;

    getMyInvitations()
      .then((value) => {
        if (!active) return;

        setInvitations(value);
        setInvitationsFailed(false);
      })
      .catch(() => {
        if (active) setInvitationsFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  useEffect(() => {
    let active = true;

    getMyParties()
      .then(async (value) => {
        if (!active) return;

        setParties(value);
        setFailed(false);

        const first = value[0];

        if (!first) return;

        // 통을 못 채워도 목록은 보여준다. 빈 통으로 남는다
        try {
          const firstPopcorn = await getPartyPopcorn(first.partyId);

          if (active) setPopcorn(firstPopcorn);
        } catch {
          // 그대로 둔다
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  const hasParties = parties !== null && parties.length > 0;
  const hasInvitations = invitations !== null && invitations.length > 0;

  /*
   * 대표 파티 한 방과 나머지. **대표는 아래 줄에서 뺀다** — 같은 파티 이름이 화면에 두 번
   * 서면 어느 것을 눌러야 하는지 알 수 없다. 통에 담는 팝콘도 이 파티의 것이다.
   */
  const firstParty = parties?.[0] ?? null;
  const restParties = parties?.slice(1) ?? [];
  /*
   * 섞은 맛 가운데 많은 셋. 시안의 `우리의 공통 취향` 칩이다.
   *
   * **이름을 못 받은 맛은 뺀다.** 파티 팝콘(API-057)은 코드만 주므로 이름표(API-044)가
   * 없으면 `FLAVOR_A`가 그대로 화면에 남는다. 이름이 없으면 칩이 없는 편이 낫다.
   */
  const commonFlavors = popcorn
    ? [...mixedFlavors(popcorn)]
        .sort((a, b) => b.count - a.count)
        .map((entry) => flavorNames.get(entry.code))
        .filter((name): name is string => Boolean(name))
        .slice(0, COMMON_FLAVOR_COUNT)
    : [];

  return (
    <>
      {/* 필름 · MY와 같은 헤더다(업무 -676) */}
      <AppHeader />

      <div className={styles.page}>
        <div className={styles.titleBlock}>
          <h1 className={styles.title}>파티팝콘</h1>
          <p className={styles.subtitle}>같이 볼 영화, 함께 골라요</p>
        </div>

        {failed ? (
          <StatusView
            title="파티 목록을 불러오지 못했어요"
            description="잠시 후 다시 시도해 주세요."
          >
            <Button variant="text" onClick={() => setReloadCount((count) => count + 1)}>
              다시 시도
            </Button>
          </StatusView>
        ) : null}

        {hasInvitations ? (
          <section className={styles.invitations} aria-labelledby="party-invitations">
            <h2 className={styles.sectionLabel} id="party-invitations">
              받은 초대 {invitations.length}
            </h2>

            <ul className={styles.list}>
              {invitations.map((invitation) => (
                <li key={invitation.invitationId}>
                  <button
                    type="button"
                    className={styles.inviteCard}
                    onClick={() => void navigate(path.partyInvitation(invitation.invitationId))}
                  >
                    <span className={styles.body}>
                      <span className={styles.name}>{invitation.party.name}</span>
                      <span className={styles.inviteText}>
                        {invitation.party.owner.nickname}님의 파티에 초대받았어요!
                      </span>
                    </span>

                    {/*
                     * 얼굴 자리. 프로필 사진은 계약에 없어(API-052) 닉네임 첫 글자를 쓴다.
                     * `+N`은 나 말고 이미 들어가 있는 사람 수다.
                     */}
                    <Initials
                      names={[invitation.party.owner.nickname]}
                      rest={Math.max(invitation.party.memberCount - 1, 0)}
                    />

                    <span className={styles.chevron} aria-hidden="true">
                      ›
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/* 초대만 못 받아 온 경우다. 파티 목록은 아래에 그대로 둔다 */}
        {invitationsFailed ? (
          <p className={styles.invitationsError}>
            받은 초대를 불러오지 못했어요.{' '}
            <Button variant="text" onClick={() => setReloadCount((count) => count + 1)}>
              초대 다시 불러오기
            </Button>
          </p>
        ) : null}

        {/*
         * 대표 파티 한 방(사용자 시안 2026-09-20). 목록의 첫 파티이며 통도 이 파티의
         * 팝콘이다 — 통이 이 카드 안으로 들어오면서 같은 파티를 두 번 말하지 않게 됐다.
         *
         * 누르면 지금까지와 같은 곳(5.8 파티원 팝콘)으로 간다.
         */}
        {firstParty ? (
          <section className={styles.hero}>
            <div className={styles.heroHead}>
              <h2 className={styles.heroTitle}>{firstParty.name}</h2>

              <span className={styles.heroBadge}>
                <span className={styles.heroDot} aria-hidden="true" />
                진행 중
              </span>
            </div>

            <Initials
              names={firstParty.members.map((member) => member.nickname)}
              tone="dark"
              labelled
            />

            <div className={styles.progress}>
              <p className={styles.progressLabel}>
                {firstParty.members.length} / {firstParty.maxMembers}명 참여
              </p>

              <div
                className={styles.progressTrack}
                role="progressbar"
                aria-valuenow={firstParty.members.length}
                aria-valuemin={0}
                aria-valuemax={firstParty.maxMembers}
              >
                <div
                  className={styles.progressFill}
                  style={{
                    width: `${(firstParty.members.length / firstParty.maxMembers) * 100}%`,
                  }}
                />
              </div>
            </div>

            {commonFlavors.length > 0 ? (
              <div className={styles.common}>
                <p className={styles.commonLabel}>우리의 공통 취향</p>

                <ul className={styles.chips}>
                  {commonFlavors.map((name) => (
                    <li key={name} className={styles.chip}>
                      {name}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            <button
              type="button"
              className={styles.heroAction}
              onClick={() => void navigate(path.partyPopcorn(firstParty.partyId))}
            >
              <PlayIcon />
              파티 팝콘 보기
            </button>

            {/* 통은 이 카드의 그림이다. 담긴 것은 이 파티의 팝콘이다(API-057) */}
            <div className={styles.heroBucket} aria-hidden="true">
              <PopcornBucket
                flavors={popcorn ? mixedFlavors(popcorn) : []}
                width={BUCKET_WIDTH}
                label=""
              />
            </div>
          </section>
        ) : null}

        {restParties.length > 0 ? (
          <section className={styles.joined}>
            <h2 className={styles.sectionLabel}>참여 중인 파티 {parties?.length}</h2>

            <ul className={styles.cardRow}>
              {restParties.map((party) => (
                <li key={party.partyId}>
                  <button
                    type="button"
                    className={styles.partyCard}
                    onClick={() => void navigate(path.partyPopcorn(party.partyId))}
                  >
                    <span className={styles.partyCardHead}>
                      <span className={styles.name}>{party.name}</span>
                      <span className={styles.chevron} aria-hidden="true">
                        ›
                      </span>
                    </span>

                    <span className={styles.members}>
                      {party.members.length} / {party.maxMembers}명
                    </span>

                    <Initials names={party.members.map((member) => member.nickname)} />
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/* 파티가 없으면 통이 화면 가운데를 잡는다. 시안의 강조 카드가 설 자리다 */}
        {parties !== null && !hasParties ? (
          <div className={styles.stage}>
            <PopcornBucket
              flavors={[]}
              width={BUCKET_WIDTH}
              label="아직 파티원의 팝콘이 담기지 않은 통"
            />
          </div>
        ) : null}

        {parties !== null ? (
          <div className={styles.actions}>
            <Button variant="brand" onClick={() => void navigate(path.partyNew())}>
              파티 만들기
            </Button>

            <Button
              variant="secondary"
              onClick={() => setNotice('초대 코드로 참여하기는 준비 중이에요')}
            >
              초대 코드로 참여하기
            </Button>
          </div>
        ) : null}

        {notice ? (
          <Snackbar actionLabel="확인" onAction={() => setNotice('')}>
            {notice}
          </Snackbar>
        ) : null}
      </div>
    </>
  );
}
