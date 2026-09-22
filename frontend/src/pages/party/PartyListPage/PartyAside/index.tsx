/*
 * 5.1 파티팝콘 우측 보조 패널 (업무 S15P21E106-785).
 *
 * 2026-09-21 PC 시안의 오른쪽 단이다. 카드 두 장이 위아래로 선다.
 *
 * - **우리 영화 모임** — 고른 파티의 멤버 · 공통 취향 · 인원 현황 · 추천 보기
 * - **새로운 파티를 만들어보세요** — 파티가 없을 때도 있을 때도 늘 선다(시안)
 *
 * 카드 껍데기는 업무 -783이 만든 `components/AsidePanel`을 그대로 쓴다. 제목 오른쪽
 * 화살표는 고른 파티의 5.8 파티원 팝콘으로 간다 — 시안의 `›`가 가리키는 곳이다.
 * 시안이 그 옆에 둔 톱니(파티 설정)는 두지 않는다. 파티 관리(나가기 · 종료 · 내보내기)는
 * 5.8의 `⋯` 메뉴가 유일한 진입점이라는 결정(업무 -462)을 이 업무가 바꾸지 않는다.
 *
 * **시안의 `영화 선택 현황 3 / 4명 선택 완료`는 계약에 없다.** 파티원이 영화를 하나씩
 * 고르는 절차 자체가 이 앱에 없다 — 파티는 취향을 섞어 추천을 받는다(API-057 · API-058).
 * 같은 자리에 **인원 참여 현황**을 둔다(사용자 결정 2026-09-22). 멤버 얼굴 아래 배지도
 * 같은 이유로 `선택 완료` · `선택 중`이 아니라 계약이 주는 역할(`방장` · `멤버`)이다.
 *
 * `우리의 공통 취향` 칩은 412px의 강조 카드와 **같은 값**이다 — 파티 팝콘(API-057)을 맛별로
 * 합쳐 알이 많은 셋이다. 두 폭에서 같은 파티가 서로 다른 취향을 말하지 않게 한다.
 */
import { useNavigate } from 'react-router';
import AsidePanel from '../../../../components/AsidePanel';
import Button from '../../../../components/Button';
import PopcornBucket from '../../../../components/PopcornBucket';
import { PlayIcon } from '../../../../components/Icon';
import MemberFaces from '../MemberFaces';
import { path } from '../../../../routes/paths';
import styles from './PartyAside.module.css';
import type { Party } from '../../../../types/party';

/** 빈 통 그림의 폭(px). 시안의 카드 안 그림 크기다 */
const EMPTY_BUCKET_WIDTH = 96;

type Props = {
  /** 고른 파티. 파티가 없으면 `null`이고 첫 카드를 그리지 않는다 */
  party: Party | null;
  /** 고른 파티의 공통 취향 맛 이름. 없으면 구역을 그리지 않는다 */
  commonFlavors: string[];
  onCreateParty: () => void;
};

export default function PartyAside({ party, commonFlavors, onCreateParty }: Props) {
  const navigate = useNavigate();

  return (
    <>
      {party ? (
        <AsidePanel
          title={party.name}
          to={path.partyPopcorn(party.partyId)}
          linkLabel={`${party.name} 파티원 팝콘 보기`}
        >
          <div className={styles.body}>
            <MemberFaces
              variant="panel"
              faces={party.members.map((member) => ({
                key: String(member.userId),
                nickname: member.nickname,
                badge: member.role === 'OWNER' ? '방장' : '멤버',
              }))}
            />

            {commonFlavors.length > 0 ? (
              <section className={styles.section}>
                <h3 className={styles.sectionTitle}>우리의 공통 취향</h3>

                <ul className={styles.chips}>
                  {commonFlavors.map((name) => (
                    <li key={name} className={styles.chip}>
                      {name}
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}

            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>인원 참여 현황</h3>

              <p className={styles.progressLabel}>
                {party.members.length} / {party.maxMembers}명 참여
              </p>

              <div
                className={styles.progressTrack}
                role="progressbar"
                aria-label="파티 인원"
                aria-valuenow={party.members.length}
                aria-valuemin={0}
                aria-valuemax={party.maxMembers}
              >
                <div
                  className={styles.progressFill}
                  style={{ width: `${(party.members.length / party.maxMembers) * 100}%` }}
                />
              </div>
            </section>

            {/*
             * 5.9 팝콘 섞기로 간다. 5.8의 `함께 볼 영화 추천받기`와 같은 곳이며 거기서
             * 5.5 추천 완성으로 이어진다. 흐름을 새로 만들지 않는다.
             */}
            <button
              type="button"
              className={styles.action}
              onClick={() => void navigate(path.partyPopcornMix(party.partyId))}
            >
              <PlayIcon />
              파티 추천 보기
            </button>
          </div>
        </AsidePanel>
      ) : null}

      <AsidePanel title="새로운 파티를 만들어보세요">
        <div className={styles.invite}>
          {/* 빈 통이다. 파티가 아직 없다는 것을 그림이 말한다 */}
          <PopcornBucket flavors={[]} width={EMPTY_BUCKET_WIDTH} label="" />

          <p className={styles.inviteText}>
            친구들을 초대해서
            <br />
            함께 볼 영화를 골라보세요.
            <br />
            좋은 영화는 같이 볼 때 더 특별해요.
          </p>

          <Button variant="secondary" onClick={onCreateParty}>
            파티 만들기
          </Button>
        </div>
      </AsidePanel>
    </>
  );
}
