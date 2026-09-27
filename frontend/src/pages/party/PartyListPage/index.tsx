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
 * **`초대 코드로 참여하기`는 업무 -853에서 제거했다.** API-051 초대는 닉네임 검색
 * (API-019)으로 찾은 `userId`를 받고, 코드나 링크로 참여하는 경로는 정의되어 있지 않다.
 * 제공하지 않는 참여 방법을 준비 중 기능처럼 노출하지 않는다(`wireframe-change-requests.md`
 * CR-26).
 *
 * 목록은 **활성 파티만** 보여준다(API-050 기본값). 나가거나 종료한 파티는 여기서 빠진다.
 *
 * **`지난 파티` 구역은 업무 -826에서 넣었다.** API-050이 `status=CLOSED`로 종료 파티를
 * 내려주고 계약이 그 목적을 `추천 이력 확인용`이라고 적어 두었는데, 프론트는 기본값만
 * 불러 종료 파티를 어디에도 보여 주지 않았다. 백엔드가 파티 추천 이력 API-083을 만들며
 * (업무 -815) 목적지가 생겼으므로 그 길을 여기에 둔다.
 *
 * - 참여 중인 파티 **아래**에 둔다. 지난 일은 지금 하는 일보다 급하지 않다
 * - 끝난 파티가 없으면 구역을 그리지 않는다. 받은 초대 구역과 같은 이유다
 * - 줄을 누르면 5.10 파티 추천 이력으로 간다. 종료 파티의 팝콘(5.8)으로는 가지 않는다 —
 *   계약이 종료 파티에 남겨 둔 것은 추천 이력뿐이다
 * - **끝난 파티를 못 받아 와도 활성 목록은 그대로 둔다.** 서로 다른 요청이다
 * - **첫 장만 보여준다**(`getClosedParties` 머리말). 지난 파티를 끝까지 훑는 화면이 아니다
 *
 * `[?]` 와이어프레임은 파티마다 `케이퍼 · 스릴러` 같은 **공통 장르**를 보여주는데
 * API-050 응답에는 없다(이름 · 상태 · 멤버 · 정원뿐). 없는 값을 지어내지 않고 같은
 * 자리에 멤버 이름을 둔다.
 *
 * ─────────────────────────── 데스크톱 시안 (업무 -785)
 *
 * 1024px 이상에서는 2026-09-21 PC 시안의 배치를 쓴다(`PartyDesktop` · `PartyAside`).
 * **412px 이하 화면은 아래 그대로다** — 폭에 따라 그리는 것 자체가 갈린다
 * (`hooks/useShellSize`, 업무 -782).
 *
 * - 제목 줄 오른쪽에 `파티 만들기`가 선다
 * - `받은 초대`가 카드가 되고 카드에서 **바로 수락**할 수 있다(API-053). 5.6은 그대로 있다
 * - `참여 중인 파티`가 격자가 되고, **카드를 누르면 고른 파티가 바뀐다.** 파티 화면으로
 *   가는 것은 카드 아래의 `파티로 이동`이다
 * - 오른쪽에 고른 파티의 멤버 · 공통 취향 · 인원 현황과 `새로운 파티를 만들어보세요`가 붙는다
 *
 * 시안에 있으나 계약에 없는 값들을 무엇으로 바꿨는지는 `PartyDesktop`의 머리말과
 * `frontend/verification/S15P21E106-785.md`에 있다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import GuideCallout from '../../../components/GuideCallout';
import PopcornBucket from '../../../components/PopcornBucket';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import AppHeader from '../../../components/AppHeader';
import DesktopAside from '../../../components/DesktopAside';
import { PlayIcon } from '../../../components/Icon';
import PartyAside from './PartyAside';
import PartyDesktop from './PartyDesktop';
import {
  acceptInvitation,
  getClosedParties,
  getMyInvitations,
  getMyParties,
  getPartyPopcorn,
} from '../../../api/parties';
import { getPopcornFlavors } from '../../../api/taste';
import { retryNotificationQuery } from '../../../api/notificationRetry';
import { useNotificationRevision } from '../../../hooks/useNotificationRevision';
import { useShellSize } from '../../../hooks/useShellSize';
import { useAuth } from '../../../contexts/authContext';
import { path } from '../../../routes/paths';
import { mixedFlavors, partyStatusLabel } from '../../../utils/party';
import {
  PARTY_GUIDE,
  completeGuide,
  hasSeenGuide,
  readGuideProgress,
  setGuideProgress,
} from '../../../utils/userGuides';
import type { Party, PartyInvitation, PartyMember, PartyPopcorn } from '../../../types/party';
import styles from './PartyListPage.module.css';

/** 강조 카드 오른쪽의 통. 시안의 크기다 */
const BUCKET_WIDTH = 128;

/** `우리의 공통 취향`에 세우는 맛 수. 셋을 넘으면 칩이 두 줄이 된다(시안) */
const COMMON_FLAVOR_COUNT = 3;

/**
 * 얼굴 자리.
 *
 * 사진이 있으면 동그라미에 사진을, 없으면 닉네임 첫 글자를 넣는다(업무 -762). 사진은 파티
 * 응답의 `owner` · `members[]`에만 온다(업무 -811). 받은 초대(API-052)의 방장에는 없어 늘
 * 첫 글자다 — 지어낸 사진을 두는 것보다 낫다.
 *
 * 사진은 장식이다. 이름은 옆 글자나 숨은 글자가 읽힌다.
 */
function Initials({
  people,
  rest = 0,
  tone,
  labelled = false,
}: {
  people: { name: string; avatarUrl?: string | null }[];
  rest?: number;
  tone?: 'dark';
  labelled?: boolean;
}) {
  return (
    <ul className={styles.initials} data-tone={tone ?? null} data-labelled={labelled}>
      {people.map(({ name, avatarUrl }) => (
        <li key={name} className={styles.initial}>
          <span className={styles.initialMark} aria-hidden="true">
            {avatarUrl ? (
              <img className={styles.initialPhoto} src={avatarUrl} alt="" loading="lazy" />
            ) : (
              ([...name][0] ?? '?')
            )}
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

/** 파티원을 얼굴 자리 모양으로. 사진이 없으면 `avatarUrl`이 `null`이라 첫 글자가 된다 */
function toFaces(members: PartyMember[]) {
  return members.map((member) => ({ name: member.nickname, avatarUrl: member.avatarUrl }));
}

export default function PartyListPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [guideDismissed, setGuideDismissed] = useState(false);
  const [guideStep, setGuideStep] = useState<{ userId: number; id: string } | null>(null);
  const showGuide = Boolean(user && !guideDismissed && !hasSeenGuide(user.userId, PARTY_GUIDE));
  const finishGuide = () => {
    if (user) completeGuide(user.userId, PARTY_GUIDE);
    setGuideDismissed(true);
  };
  const desktop = useShellSize() !== 'mobile';
  const [parties, setParties] = useState<Party[] | null>(null);
  /** 끝난 파티(API-050 `status=CLOSED`). 5.10으로 가는 `지난 파티` 구역이 쓴다(업무 -826) */
  const [closedParties, setClosedParties] = useState<Party[]>([]);
  const [invitations, setInvitations] = useState<PartyInvitation[] | null>(null);
  /**
   * 파티별 팝콘(API-057). 412px에서는 첫 파티 하나뿐이고, 데스크톱에서는 카드마다 통이
   * 설 수 있어 목록 전체를 받는다(업무 -785). 못 받은 파티는 그냥 없다 — 빈 통이 된다
   */
  const [popcorns, setPopcorns] = useState<Map<number, PartyPopcorn>>(new Map());
  /**
   * 우측 패널이 말하는 파티. 데스크톱에서만 쓴다.
   *
   * `null`이면 아직 목록을 못 받았거나 파티가 없다는 뜻이다. 목록을 받으면 첫 파티로
   * 맞추고, 고른 파티가 목록에서 사라지면(나가기 · 종료) 다시 첫 파티로 돌아간다.
   */
  const [selectedId, setSelectedId] = useState<number | null>(null);
  /** 초대 카드에서 수락을 보내는 중인 초대(API-053). 데스크톱에서만 쓴다 */
  const [acceptingId, setAcceptingId] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const [invitationsFailed, setInvitationsFailed] = useState(false);
  const [notice, setNotice] = useState('');
  /** 맛 코드 → 이름. 파티 팝콘(API-057)은 코드만 주므로 이름표가 따로 필요하다 */
  const [flavorNames, setFlavorNames] = useState<Map<string, string>>(new Map());
  const [reloadCount, setReloadCount] = useState(0);
  const notificationRevision = useNotificationRevision();

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

    /*
     * 끝난 파티는 활성 목록과 **다른 요청**이다(API-050에 둘을 함께 주는 값이 없다).
     * 못 받아 와도 활성 목록은 그대로 두고 구역만 그리지 않는다 — 지난 파티는 이 화면이
     * 하려는 일(파티 시작하기)의 곁가지다.
     */
    getClosedParties()
      .then((value) => {
        if (active) setClosedParties(value);
      })
      .catch(() => {
        if (active) setClosedParties([]);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  useEffect(() => {
    let active = true;

    const controller = new AbortController();
    retryNotificationQuery(() => getMyInvitations(), controller.signal)
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
      controller.abort();
    };
  }, [reloadCount, notificationRevision]);

  useEffect(() => {
    let active = true;

    const controller = new AbortController();
    retryNotificationQuery(() => getMyParties(), controller.signal)
      .then(async (value) => {
        if (!active) return;

        setParties(value);
        setFailed(false);
        /*
         * 고른 파티를 목록에 맞춘다. 이미 고른 파티가 목록에 남아 있으면 그대로 두고
         * (다시 읽어도 오른쪽 패널이 다른 파티로 튀지 않는다), 없으면 첫 파티로 돌아간다.
         */
        setSelectedId((current) =>
          current !== null && value.some((party) => party.partyId === current)
            ? current
            : (value[0]?.partyId ?? null),
        );

        /*
         * 통을 못 채워도 목록은 보여준다. 빈 통으로 남는다.
         *
         * 412px은 강조 카드 하나뿐이라 첫 파티만 부르고, 데스크톱은 카드마다 통이 설 수
         * 있어 목록 전체를 나란히 부른다. 활성 파티는 사용자당 최대 10개다(API-049).
         */
        const targets = desktop ? value : value.slice(0, 1);

        await Promise.all(
          targets.map(async (party) => {
            try {
              const popcorn = await getPartyPopcorn(party.partyId);

              if (active) {
                setPopcorns((prev) => new Map(prev).set(party.partyId, popcorn));
              }
            } catch {
              // 그대로 둔다
            }
          }),
        );
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [desktop, reloadCount, notificationRevision]);

  const hasParties = parties !== null && parties.length > 0;
  const hasInvitations = invitations !== null && invitations.length > 0;
  const guideSteps = [
    ...(hasInvitations
      ? [
          {
            id: 'invitations',
            title: '받은 초대',
            description: '친구가 초대했다면 여기에서 확인하고 참여할 수 있어요.',
            targetSelector: "[data-guide-target='party-invitations']",
          },
        ]
      : []),
    ...(hasParties
      ? [
          {
            id: 'active',
            title: '참여 중인 파티',
            description: '파티원과 참여 인원, 함께 모인 취향을 여기에서 확인할 수 있어요.',
            targetSelector: "[data-guide-target='party-active']",
          },
          {
            id: 'open',
            title: '파티 안으로 들어가기',
            description: '파티를 열면 멤버의 팝콘을 보고 함께 볼 영화를 추천받을 수 있어요.',
            targetSelector: "[data-guide-target='party-open']",
          },
        ]
      : [
          {
            id: 'create',
            title: '함께 영화 고르기',
            description:
              '파티를 만들면 친구를 초대하고 함께 볼 영화를 고를 수 있어요. 지금은 둘러보기만 해도 됩니다.',
            targetSelector: "[data-guide-target='party-create']",
          },
        ]),
    ...(closedParties.length > 0
      ? [
          {
            id: 'history',
            title: '지난 파티',
            description: '끝난 파티에서 추천받았던 영화는 여기에서 다시 볼 수 있어요.',
            targetSelector: "[data-guide-target='party-history']",
          },
        ]
      : []),
  ];
  const savedGuideStep = user
    ? guideStep?.userId === user.userId
      ? guideStep.id
      : readGuideProgress(user.userId, PARTY_GUIDE)
    : null;
  const activeGuideIndex = Math.max(
    0,
    guideSteps.findIndex((step) => step.id === savedGuideStep),
  );
  const moveGuideTo = (id: string) => {
    if (!user) return;
    setGuideProgress(user.userId, PARTY_GUIDE, id);
    setGuideStep({ userId: user.userId, id });
  };
  const guideCallout =
    showGuide && parties !== null && (invitations !== null || invitationsFailed) && !failed ? (
      <GuideCallout
        step={activeGuideIndex + 1}
        totalSteps={guideSteps.length}
        targetSelector={guideSteps[activeGuideIndex].targetSelector}
        title={guideSteps[activeGuideIndex].title}
        description={guideSteps[activeGuideIndex].description}
        onSkip={finishGuide}
        action={
          activeGuideIndex < guideSteps.length - 1
            ? { label: '다음', onClick: () => moveGuideTo(guideSteps[activeGuideIndex + 1].id) }
            : { label: '둘러보기', onClick: finishGuide }
        }
      />
    ) : null;

  /*
   * 대표 파티 한 방과 나머지. **대표는 아래 줄에서 뺀다** — 같은 파티 이름이 화면에 두 번
   * 서면 어느 것을 눌러야 하는지 알 수 없다. 통에 담는 팝콘도 이 파티의 것이다.
   */
  const firstParty = parties?.[0] ?? null;
  const restParties = parties?.slice(1) ?? [];
  const popcorn = firstParty ? (popcorns.get(firstParty.partyId) ?? null) : null;
  /*
   * 섞은 맛 가운데 많은 셋. 시안의 `우리의 공통 취향` 칩이다. 412px의 강조 카드와
   * 데스크톱 우측 패널이 같은 함수를 쓴다 — 같은 파티가 폭에 따라 다른 취향을 말하지 않는다.
   *
   * **이름을 못 받은 맛은 뺀다.** 파티 팝콘(API-057)은 코드만 주므로 이름표(API-044)가
   * 없으면 `FLAVOR_A`가 그대로 화면에 남는다. 이름이 없으면 칩이 없는 편이 낫다.
   */
  function commonFlavorsOf(value: PartyPopcorn | null): string[] {
    if (!value) return [];

    return [...mixedFlavors(value)]
      .sort((a, b) => b.count - a.count)
      .map((entry) => flavorNames.get(entry.code))
      .filter((name): name is string => Boolean(name))
      .slice(0, COMMON_FLAVOR_COUNT);
  }

  const commonFlavors = commonFlavorsOf(popcorn);

  /*
   * ─────────────────────────── 데스크톱 시안 (업무 -785)
   *
   * 1024px 이상에서는 2026-09-21 PC 시안의 배치를 쓴다. **412px 이하 화면은 아래 그대로다** —
   * 폭에 따라 그리는 것 자체가 갈린다(`hooks/useShellSize`, 업무 -782).
   *
   * 오류 · 안내 · 알림 막대는 두 폭이 함께 쓴다. 상단 검색 · 알림 · 프로필은 데스크톱
   * 셸의 상단 바가 이미 그리므로 `AppHeader`를 두지 않는다.
   */
  if (desktop) {
    const selected = parties?.find((party) => party.partyId === selectedId) ?? null;

    /** 카드에서 바로 수락한다(API-053). 5.6과 같은 호출이고 성공하면 두 목록을 다시 읽는다 */
    const handleAccept = (invitationId: number) => {
      setAcceptingId(invitationId);

      acceptInvitation(invitationId)
        .then(() => {
          setReloadCount((count) => count + 1);
        })
        .catch(() => {
          setNotice('초대를 수락하지 못했어요. 잠시 후 다시 시도해 주세요');
        })
        .finally(() => setAcceptingId(null));
    };

    return (
      <>
        {failed ? (
          <div className={styles.desktopStatus}>
            <StatusView
              title="파티 목록을 불러오지 못했어요"
              description="잠시 후 다시 시도해 주세요."
            >
              <Button variant="text" onClick={() => setReloadCount((count) => count + 1)}>
                다시 시도
              </Button>
            </StatusView>
          </div>
        ) : null}

        <PartyDesktop
          parties={parties ?? []}
          loaded={parties !== null}
          invitations={invitations ?? []}
          popcorns={popcorns}
          mixedFlavorsOf={mixedFlavors}
          commonFlavorsOf={(partyId) => commonFlavorsOf(popcorns.get(partyId) ?? null)}
          selectedId={selectedId}
          onSelect={setSelectedId}
          onOpenParty={(partyId) => {
            finishGuide();
            void navigate(path.partyPopcorn(partyId));
          }}
          onOpenInvitation={(invitationId) => void navigate(path.partyInvitation(invitationId))}
          onAccept={handleAccept}
          acceptingId={acceptingId}
          closedParties={closedParties}
          onOpenHistory={(partyId) => void navigate(path.partyRecommendationHistory(partyId))}
          onCreateParty={() => {
            finishGuide();
            void navigate(path.partyNew());
          }}
        />
        {guideCallout}

        {/* 초대만 못 받아 온 경우다. 파티 목록은 그대로 둔다 */}
        {invitationsFailed ? (
          <p className={styles.desktopInvitationsError}>
            받은 초대를 불러오지 못했어요.{' '}
            <Button variant="text" onClick={() => setReloadCount((count) => count + 1)}>
              초대 다시 불러오기
            </Button>
          </p>
        ) : null}

        {/* 우측 보조 패널. 1280px 이상에서만 자리가 있다(업무 -782) */}
        <DesktopAside>
          <PartyAside
            party={selected}
            commonFlavors={commonFlavorsOf(
              selected ? (popcorns.get(selected.partyId) ?? null) : null,
            )}
            onCreateParty={() => void navigate(path.partyNew())}
          />
        </DesktopAside>

        {notice ? (
          <Snackbar actionLabel="확인" onAction={() => setNotice('')}>
            {notice}
          </Snackbar>
        ) : null}
      </>
    );
  }

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
          <section
            className={styles.invitations}
            aria-labelledby="party-invitations"
            data-guide-target="party-invitations"
          >
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
                     * 얼굴 자리. 받은 초대의 방장에는 사진이 오지 않아(API-052) 닉네임 첫 글자를
                     * 쓴다. `+N`은 나 말고 이미 들어가 있는 사람 수다.
                     */}
                    <Initials
                      people={[{ name: invitation.party.owner.nickname }]}
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
          <section className={styles.hero} data-guide-target="party-active">
            <div className={styles.heroHead}>
              <h2 className={styles.heroTitle}>{firstParty.name}</h2>

              <span className={styles.heroBadge}>
                <span className={styles.heroDot} aria-hidden="true" />
                {partyStatusLabel(firstParty)}
              </span>
            </div>

            <Initials people={toFaces(firstParty.members)} tone="dark" labelled />

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

            {firstParty.members.length < 2 ? (
              <p className={styles.commonLabel}>파티원이 참여하면 공통 취향을 확인할 수 있어요</p>
            ) : commonFlavors.length > 0 ? (
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
              data-guide-target="party-open"
              onClick={() => {
                finishGuide();
                void navigate(path.partyPopcorn(firstParty.partyId));
              }}
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

                    <Initials people={toFaces(party.members)} />
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/*
         * 지난 파티(업무 -826). 끝난 파티는 추천 이력(5.10)으로만 들어간다 — 팝콘 · 멤버는
         * 계약이 종료 파티에 열어 두지 않았다.
         */}
        {closedParties.length > 0 ? (
          <section
            className={styles.closed}
            aria-labelledby="party-closed"
            data-guide-target="party-history"
          >
            <h2 className={styles.sectionLabel} id="party-closed">
              지난 파티 {closedParties.length}
            </h2>

            <ul className={styles.list}>
              {closedParties.map((party) => (
                <li key={party.partyId}>
                  <button
                    type="button"
                    className={styles.closedCard}
                    onClick={() => void navigate(path.partyRecommendationHistory(party.partyId))}
                  >
                    <span className={styles.body}>
                      <span className={styles.name}>{party.name}</span>
                      <span className={styles.inviteText}>그때 추천받은 영화 보기</span>
                    </span>

                    <span className={styles.chevron} aria-hidden="true">
                      ›
                    </span>
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
          <div className={styles.actions} data-guide-target="party-create">
            <Button
              variant="brand"
              onClick={() => {
                finishGuide();
                void navigate(path.partyNew());
              }}
            >
              파티 만들기
            </Button>
          </div>
        ) : null}

        {notice ? (
          <Snackbar actionLabel="확인" onAction={() => setNotice('')}>
            {notice}
          </Snackbar>
        ) : null}
        {guideCallout}
      </div>
    </>
  );
}
