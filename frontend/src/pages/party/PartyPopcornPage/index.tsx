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
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ChevronDownIcon, ChevronRightIcon, ChevronUpIcon } from '../../../components/Icon';
import { getMyParties, getPartyPopcorn } from '../../../api/parties';
import { getMyProfile } from '../../../api/users';
import { getPopcornFlavors } from '../../../api/taste';
import { path } from '../../../routes/paths';
import styles from './PartyPopcornPage.module.css';
import type { PartyMemberPopcorn, PartyPopcorn } from '../../../types/party';
import type { PopcornFlavor } from '../../../types/taste';

/** 겹치는 맛을 고르는 기준. 와이어프레임 설명 문구가 `상위 3개 맛`으로 적고 있다 */
const TOP_FLAVOR_COUNT = 3;

/**
 * 멤버 통이 그려지는 폭(px). 네 명이 세로로 쌓이므로 3.2(196px)보다 작다.
 * 이름·대표 맛이 옆에 붙을 폭을 남기는 값이다.
 */
const BUCKET_WIDTH = 132;

/** 통 위에 쌓을 알을 맛 분포대로 펼친다. 한 알이 영화 한 편이다 (3.2와 같은 규칙) */
function kernelFlavors(member: PartyMemberPopcorn): string[] {
  return member.flavors.flatMap((flavor) =>
    Array.from({ length: flavor.popcornCount }, () => flavor.flavorCode),
  );
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
                  kernelFlavors={kernelFlavors(member)}
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
      </div>
    </>
  );
}
