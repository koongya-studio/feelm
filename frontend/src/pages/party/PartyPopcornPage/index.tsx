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
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ChevronDownIcon, ChevronUpIcon } from '../../../components/Icon';
import { getMyParties, getPartyPopcorn } from '../../../api/parties';
import { getMyProfile } from '../../../api/users';
import { getPopcornFlavors } from '../../../api/taste';
import { path } from '../../../routes/paths';
import styles from './PartyPopcornPage.module.css';
import type { PartyMemberPopcorn, PartyPopcorn } from '../../../types/party';
import type { PopcornFlavor } from '../../../types/taste';

/** 겹치는 맛을 고르는 기준. 와이어프레임 설명 문구가 `상위 3개 맛`으로 적고 있다 */
const TOP_FLAVOR_COUNT = 3;

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
  const [partyName, setPartyName] = useState('');
  const [myUserId, setMyUserId] = useState<number | null>(null);
  const [flavors, setFlavors] = useState<PopcornFlavor[]>([]);
  const [expanded, setExpanded] = useState<number[]>([]);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

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

    // 아래 세 가지는 실패해도 본문을 막지 않는다. 이름 · `나` 표시 · 맛 이름만 비워진다
    getMyParties()
      .then((parties) => {
        if (active) {
          setPartyName(parties.find((party) => party.partyId === id)?.name ?? '');
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

    getPopcornFlavors().then((value) => {
      if (active) {
        setFlavors(value);
      }
    });

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

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
        subtitle={`${partyName} · 멤버 ${popcorn.members.length}명`}
        showBack
      />

      <div className={styles.page}>
        {popcorn.members.map((member) => {
          const isOpen = expanded.includes(member.user.userId);
          const isMe = member.user.userId === myUserId;
          const leadCode = topFlavorCodes(member, 1)[0];
          const leadName = leadCode ? (flavorByCode.get(leadCode)?.displayName ?? '') : '';
          // 막대는 맛 순서대로 그린다. 와이어프레임 첫 카드와 같은 순서다
          const ordered = flavors
            .map((flavor) => ({
              flavor,
              count:
                member.flavors.find((item) => item.flavorCode === flavor.code)?.popcornCount ?? 0,
            }))
            .filter((item) => item.count > 0);

          return (
            <section key={member.user.userId} className={styles.memberCard}>
              <button
                type="button"
                className={styles.memberHead}
                aria-expanded={isOpen}
                onClick={() => toggle(member.user.userId)}
              >
                <span className={styles.avatar} aria-hidden="true" />

                <span className={styles.memberInfo}>
                  <span className={styles.memberName}>
                    {member.user.nickname}
                    {isMe ? ' (나)' : ''}
                  </span>
                  <span className={styles.memberMeta}>
                    대표 맛 {leadName} · 팝콘 {member.totalCount}알
                  </span>
                </span>

                <span className={styles.memberChevron} aria-hidden="true">
                  {isOpen ? <ChevronUpIcon /> : <ChevronDownIcon />}
                </span>
              </button>

              <span className={styles.bar} aria-hidden="true">
                {ordered.map((item) => (
                  <span
                    key={item.flavor.code}
                    className={styles.barPart}
                    style={{
                      width: `${((item.count / member.totalCount) * 100).toFixed(1)}%`,
                      background: item.flavor.color,
                    }}
                  />
                ))}
              </span>

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
              ) : (
                <p className={styles.expandHint}>펼쳐서 8가지 맛 개수 보기</p>
              )}
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
      </div>
    </>
  );
}
