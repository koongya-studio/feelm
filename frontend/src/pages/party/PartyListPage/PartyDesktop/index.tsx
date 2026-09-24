/*
 * 5.1 파티팝콘 데스크톱 본문 (업무 S15P21E106-785).
 *
 * 2026-09-21 PC 시안의 가운데 단이다. 위에서부터 제목 줄 · `받은 초대` · `참여 중인 파티` ·
 * `지난 파티`다. 마지막 구역은 시안에 없고 업무 -826에서 412px과 나란히 넣었다 — 종료 파티로
 * 들어가는 길이 폭에 따라 있다가 없으면 안 된다.
 * **412px 이하는 이 파일을 지나지 않는다** — 폭에 따라 그리는 것 자체가 갈린다
 * (`hooks/useShellSize`, 업무 -782).
 *
 * 데이터를 읽고 API를 부르는 일은 모두 `../index.tsx`가 한다. 여기서는 받은 값을 시안의
 * 자리에 놓기만 한다 — 같은 상태를 두 곳에서 관리하면 폭이 바뀔 때 화면이 갈린다.
 *
 * ─────────────────────────── 시안에 있으나 계약에 없는 것
 *
 * - **초대 카드의 영화 포스터** — 초대(API-052)에 영화가 없다. 파티는 취향을 섞어 추천을
 *   받는 것이라 초대 시점에 고른 영화라는 것 자체가 없다. 같은 자리에 팝콘 장식 타일을
 *   둔다(사용자 결정 2026-09-22). 가짜 영화를 그리지 않는다
 * - **초대 카드의 한마디** (`이번 주에 같이 볼 영화 골라봐요!`) — 초대에 메시지 필드가 없다.
 *   같은 줄에 파티 이름을 적는다
 * - **초대 카드의 참여자 얼굴** — API-052가 주는 것은 `memberCount` 하나다. 인원 수가
 *   이미 오른쪽 위에 있어 얼굴 없이도 몇이 모였는지 읽힌다
 * - **`토론 중` · `영화 선택 중` 상태** — 계약의 파티 상태는 `ACTIVE` · `CLOSED`뿐이다.
 *   목록에 오는 것은 활성 파티뿐이라(API-050 기본값) 그 자리에 **정원으로 가를 수 있는 두
 *   가지**를 둔다(사용자 결정 2026-09-22) — 자리가 남았으면 `모집 중`, 다 찼으면 `진행 중`
 */
import Button from '../../../../components/Button';
import PopcornBucket from '../../../../components/PopcornBucket';
import { ChevronRightIcon, PartyGroupIcon } from '../../../../components/Icon';
import MemberFaces from '../MemberFaces';
import { formatRelativeTime } from '../../../../utils/date';
import styles from './PartyDesktop.module.css';
import type { Party, PartyInvitation, PartyPopcorn } from '../../../../types/party';
import type { FlavorCount } from '../../../../components/PopcornBucket/kernels';

/** 고른 파티 카드 안의 통. 카드 아래를 채우는 그림이다 */
const CARD_BUCKET_WIDTH = 124;

/** 파티가 하나도 없을 때 가운데에 서는 빈 통 */
const EMPTY_BUCKET_WIDTH = 168;

type Props = {
  parties: Party[];
  /** 파티 목록을 받았는지. 받기 전에는 `파티가 없어요` 안내를 그리지 않는다 */
  loaded: boolean;
  invitations: PartyInvitation[];
  /** 파티별 팝콘(API-057). 아직 못 받았거나 실패한 파티는 없다 */
  popcorns: Map<number, PartyPopcorn>;
  mixedFlavorsOf: (popcorn: PartyPopcorn) => FlavorCount[];
  /** 파티의 공통 취향 맛 이름. 우측 패널과 같은 값이다 */
  commonFlavorsOf: (partyId: number) => string[];
  /** 지금 고른 파티. 우측 패널이 이 파티를 말한다 */
  selectedId: number | null;
  onSelect: (partyId: number) => void;
  onOpenParty: (partyId: number) => void;
  onOpenInvitation: (invitationId: number) => void;
  /** 카드에서 바로 수락한다(API-053). 5.6의 `초대 수락하기`와 같은 동작이다 */
  onAccept: (invitationId: number) => void;
  /** 수락을 보내는 중인 초대. 그동안 그 카드의 두 버튼은 눌리지 않는다 */
  acceptingId: number | null;
  onCreateParty: () => void;
  /** 끝난 파티(API-050 `status=CLOSED`). 추천 이력(5.10)으로만 들어간다(업무 -826) */
  closedParties: Party[];
  onOpenHistory: (partyId: number) => void;
};

export default function PartyDesktop({
  parties,
  loaded,
  invitations,
  popcorns,
  mixedFlavorsOf,
  commonFlavorsOf,
  selectedId,
  onSelect,
  onOpenParty,
  onOpenInvitation,
  onAccept,
  acceptingId,
  onCreateParty,
  closedParties,
  onOpenHistory,
}: Props) {
  return (
    <div className={styles.page}>
      <header className={styles.head}>
        <div className={styles.heading}>
          <p className={styles.eyebrow}>PARTY POPCORN</p>
          <h1 className={styles.title}>파티팝콘</h1>
          <p className={styles.subtitle}>같이 볼 영화, 함께 골라요</p>
        </div>

        <div className={styles.headActions}>
          <Button variant="brand" onClick={onCreateParty}>
            {/* 시안의 `+`. 더하기 아이콘이 없어 글자로 둔다 — 새 자산을 만들지 않는다 */}
            <span aria-hidden="true">+</span>
            파티 만들기
          </Button>
        </div>
      </header>

      {invitations.length > 0 ? (
        <section className={styles.section} aria-labelledby="party-invitations">
          <h2 className={styles.sectionTitle} id="party-invitations">
            받은 초대 {invitations.length}
          </h2>

          <ul className={styles.inviteGrid}>
            {invitations.map((invitation) => {
              const { party } = invitation;
              const busy = acceptingId === invitation.invitationId;

              return (
                <li key={invitation.invitationId} className={styles.inviteCard}>
                  {/* 포스터 자리의 장식 타일. 초대에 영화가 없다(머리말) */}
                  <div className={styles.inviteTile} aria-hidden="true">
                    <PopcornBucket flavors={[]} width={88} label="" />
                  </div>

                  <div className={styles.inviteBody}>
                    <div className={styles.inviteHead}>
                      <span className={styles.inviteMark} aria-hidden="true">
                        {[...party.owner.nickname][0] ?? '?'}
                      </span>

                      <div className={styles.inviteWho}>
                        <p className={styles.inviteName}>{party.owner.nickname} 님의 파티</p>
                        <p className={styles.inviteTime}>
                          {formatRelativeTime(invitation.createdAt)}
                        </p>
                      </div>

                      {/*
                       * `memberCount`는 지금 참여 중인 사람 수라 **나까지 세려면 1을 더한다**
                       * (5.6과 같은 계산). 정원은 계약이 정한 값이며 초대 응답에 없어
                       * 정원 표시는 파티 응답의 `maxMembers`를 쓴다.
                       */}
                      <p className={styles.inviteCount}>
                        <PartyGroupIcon />
                        {party.memberCount + 1}명 참여 중
                      </p>
                    </div>

                    <p className={styles.invitePartyName}>{party.name}</p>

                    <div className={styles.inviteActions}>
                      <Button
                        variant="brand"
                        disabled={busy}
                        onClick={() => onAccept(invitation.invitationId)}
                      >
                        수락하기
                      </Button>

                      <Button
                        variant="secondary"
                        disabled={busy}
                        onClick={() => onOpenInvitation(invitation.invitationId)}
                      >
                        자세히 보기
                        <ChevronRightIcon />
                      </Button>
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {parties.length > 0 ? (
        <section className={styles.section} aria-labelledby="party-joined">
          <h2 className={styles.sectionTitle} id="party-joined">
            참여 중인 파티 {parties.length}
          </h2>

          <ul className={styles.partyGrid}>
            {parties.map((party) => {
              const selected = party.partyId === selectedId;
              const popcorn = popcorns.get(party.partyId);
              const full = party.members.length >= party.maxMembers;

              return (
                <li key={party.partyId}>
                  {/*
                   * 카드를 누르면 **고른 파티가 바뀐다**(우측 패널이 따라간다). 파티 화면으로
                   * 가는 것은 카드 안의 `파티로 이동`이다 — 시안이 그 둘을 나눠 두었고,
                   * 누르는 것마다 화면이 떠나면 오른쪽 패널을 볼 수 없다.
                   */}
                  <button
                    type="button"
                    className={styles.partyCard}
                    data-selected={selected}
                    aria-pressed={selected}
                    onClick={() => onSelect(party.partyId)}
                  >
                    <span className={styles.partyHead}>
                      <span className={styles.partyName}>{party.name}</span>

                      <span className={styles.partyBadge}>
                        <span className={styles.partyDot} aria-hidden="true" />
                        {full ? '진행 중' : '모집 중'}
                      </span>
                    </span>

                    <MemberFaces
                      variant="row"
                      tone={selected ? 'dark' : undefined}
                      faces={party.members.map((member) => ({
                        key: String(member.userId),
                        nickname: member.nickname,
                        avatarUrl: member.avatarUrl,
                      }))}
                    />

                    {selected ? (
                      <>
                        <span className={styles.partyLead}>우리의 취향이 섞이고 있어요</span>

                        {/* 이 파티의 팝콘이다(API-057). 못 받았으면 빈 통이다 */}
                        <span className={styles.partyBucket}>
                          <PopcornBucket
                            flavors={popcorn ? mixedFlavorsOf(popcorn) : []}
                            width={CARD_BUCKET_WIDTH}
                            label=""
                          />
                        </span>
                      </>
                    ) : (
                      /*
                       * 고르지 않은 카드에는 통 대신 **공통 취향 칩**을 둔다. 시안은 그 자리에
                       * 팝콘 알을 흩어 놓지만 알은 통 안에 담겨야 읽히는 그림이고(3.2 · 5.9),
                       * 같은 값을 글로 적는 편이 카드 크기와 무관하게 읽힌다. 우측 패널의 칩과
                       * 같은 값이다(API-057).
                       */
                      <span className={styles.partyFlavors}>
                        {commonFlavorsOf(party.partyId).map((name) => (
                          <span key={name} className={styles.partyChip}>
                            {name}
                          </span>
                        ))}
                      </span>
                    )}

                    <span className={styles.partyCount}>
                      <PartyGroupIcon />
                      {party.members.length} / {party.maxMembers}명
                    </span>
                  </button>

                  <Button variant="secondary" onClick={() => onOpenParty(party.partyId)}>
                    파티로 이동
                    <ChevronRightIcon />
                  </Button>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {/*
       * 지난 파티(업무 -826). 412px과 같은 자리 · 같은 문구이며 격자가 아니라 줄 목록이다 —
       * 끝난 파티에는 보여 줄 통도 취향도 없다(계약이 종료 파티에 남긴 것은 추천 이력뿐이다).
       */}
      {closedParties.length > 0 ? (
        <section className={styles.section} aria-labelledby="party-closed">
          <h2 className={styles.sectionTitle} id="party-closed">
            지난 파티 {closedParties.length}
          </h2>

          <ul className={styles.closedList}>
            {closedParties.map((party) => (
              <li key={party.partyId}>
                <button
                  type="button"
                  className={styles.closedCard}
                  onClick={() => onOpenHistory(party.partyId)}
                >
                  <span className={styles.closedBody}>
                    <span className={styles.partyName}>{party.name}</span>
                    <span className={styles.closedText}>그때 추천받은 영화 보기</span>
                  </span>

                  <ChevronRightIcon />
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {/*
       * 참여 중인 파티도 받은 초대도 없을 때. **시안에 없는 상태다** — 시안은 파티 셋과
       * 초대 둘이 있는 화면이다. 우측 패널의 `새로운 파티를 만들어보세요`가 이 말을 하지만
       * 그 패널은 1280px 이상에서만 있어(업무 -782) 1024~1279px에서는 제목 줄만 남는다.
       * 412px이 빈 통으로 자리를 잡는 것과 같은 그림을 가운데에 둔다.
       */}
      {loaded && parties.length === 0 && invitations.length === 0 ? (
        <div className={styles.empty}>
          <PopcornBucket flavors={[]} width={EMPTY_BUCKET_WIDTH} label="" />

          <p className={styles.emptyTitle}>아직 참여 중인 파티가 없어요</p>
          <p className={styles.emptyText}>친구를 초대해 취향을 모으면 함께 볼 영화를 골라 줘요</p>
        </div>
      ) : null}
    </div>
  );
}
