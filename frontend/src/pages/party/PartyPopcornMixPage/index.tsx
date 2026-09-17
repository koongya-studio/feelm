/*
 * 5.9 파티 팝콘 섞기
 *
 * 요소와 순서는 와이어프레임 5.9 그대로다(screen-spec.md 7장 5.9).
 * ① 참여자 취향 비교 · ② 팝콘 믹서 · ③ 공통 취향 결과
 *
 * 연동 API는 API-057 파티원 팝콘 조회다. 참여자 이름과 팝콘 집계는 여기서 온다.
 * 업무 -393에서 실제 호출로 바뀌었다.
 *
 * **① 의 `46%` · `38%`, ③ 의 `72%`, 그리고 태그 4종은 계약에 대응이 없다(CR-17).**
 * 산출 규칙이 없고 API-057의 맛별 집계로도 재현되지 않아 `getPartyMix`로 따로 가져온다.
 * 자세한 이유는 types/unspecified.ts에 있다.
 *
 * ① 의 대표 취향 이름(`액션 · 모험`)은 알이 많은 상위 두 맛의 `displayName`을 이어
 * 만든다. 와이어프레임은 장르명을 쓰지만 계약이 API-044의 표시명을 쓰라고 정하고 있다
 * (screen-spec.md 8.1 #2).
 *
 * 팀 결정 2026-09-10 (업무 -320)
 * - **② 팝콘 믹서를 실제 팝콘 통으로 채웠다.** 와이어프레임이 `이미지 · SVG · 애니메이션
 *   에셋 영역`이라는 빈 자리로만 표기했고 이전 구현도 그 자리를 그대로 두었다. 업무
 *   -320이 "8맛 3D 팝콘과 버킷 표현을 사용한다"고 정하고, -101에서 그 에셋과
 *   `PopcornBucket` 컴포넌트가 나왔다. 파티원 전원의 맛을 합쳐 한 통에 담는다.
 *   섞는다는 말이 그림으로 그대로 보인다
 * - **알 수를 정규화한다.** 네 명이면 300알이 넘어가 알이 서로를 덮어 실루엣이
 *   뭉개지고 `<image>` 요소도 그만큼 늘어난다. 비율을 유지하며 줄인다. 업무 -577부터
 *   그 요약은 `PopcornBucket`이 30알로 하므로 이 화면은 맛별 합계만 넘긴다
 * - **번호 카드 ①②③을 걷어냈다.** 번호는 와이어프레임 표기였고, 테두리 카드 세 개가
 *   쌓이면 화면이 목록이 된다. 홈·취향 필름·영화 상세와 같이 카드 없는 섹션으로 뒀다
 * - 통은 밝은 벽에 그대로 둔다. 시안 2.5장의 어두운 블록 규칙은 **팝콘 8색**(범례·막대)에
 *   대한 것이고, 3D 렌더는 3.2와 같이 벽에 걸린다
 *
 * **파티원 수 배치 규칙** (팀 결정 2026-09-10, 업무 -320): 참여자 카드는 두 칸 격자에
 * 순서대로 채운다. 파티는 최대 4명이므로(FR-20) 1~2명은 한 줄, 3~4명은 두 줄이 되고
 * 3명이면 마지막 칸이 빈다. 인원에 따라 칸 수를 바꾸지 않는다 — 2명일 때 한 칸씩 크게
 * 두면 같은 화면이 인원마다 다른 리듬을 갖는다. 섞은 통은 인원과 무관하게 하나다.
 * `[?]` 3.2에서 진입한 경우와 5.8에서 진입한 경우의 차이는 미확정이라 같은 화면을 쓴다.
 */
import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import Chip from '../../../components/Chip';
import Button from '../../../components/Button';
import PopcornBucket from '../../../components/PopcornBucket';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getPartyMix, getPartyPopcorn } from '../../../api/parties';
import { getPopcornFlavors } from '../../../api/taste';
import styles from './PartyPopcornMixPage.module.css';
import type { FlavorCount } from '../../../components/PopcornBucket/kernels';
import type { PartyPopcorn } from '../../../types/party';
import type { PopcornFlavor } from '../../../types/taste';
import type { PartyMixView } from '../../../types/unspecified';

/** 대표 취향 이름에 쓰는 맛 개수. 와이어프레임이 두 개를 이어 붙인다 */
const LEAD_FLAVOR_COUNT = 2;

/** 섞은 통이 그려지는 폭(px). 3.2와 같은 값이라 두 화면의 통이 같은 크기로 보인다 */
const BUCKET_WIDTH = 196;

/**
 * 파티원 전원의 맛을 한 통에 합친다.
 *
 * 맛별 합계만 내면 된다. **비율을 유지하며 알 수를 줄이는 일은 통이 한다**(업무 -577).
 * 전에는 여기서 100알로 나누고 잔여를 채웠는데, `PopcornBucket`이 같은 요약을 다시
 * 하게 되면서 두 번 나누는 셈이 됐다.
 */
function mixedFlavors(popcorn: PartyPopcorn): FlavorCount[] {
  const totals = new Map<string, number>();

  for (const member of popcorn.members) {
    for (const flavor of member.flavors) {
      totals.set(flavor.flavorCode, (totals.get(flavor.flavorCode) ?? 0) + flavor.popcornCount);
    }
  }

  return [...totals.entries()].map(([code, count]) => ({ code, count }));
}

export default function PartyPopcornMixPage() {
  const { partyId } = useParams();

  const id = Number(partyId);

  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [mix, setMix] = useState<PartyMixView | null>(null);
  const [flavors, setFlavors] = useState<PopcornFlavor[]>([]);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    /*
     * 섞기 값(`getPartyMix`)은 계약이 없는 자리표시자라 누구의 값인지 알 수 없다.
     * 실제 파티원에게 붙이려면 먼저 API-057로 파티원을 알아야 하므로 이어서 부른다.
     */
    getPartyPopcorn(id)
      .then(async (popcornValue) => {
        const mixValue = await getPartyMix(
          id,
          popcornValue.members.map((member) => member.user.userId),
        );

        if (active) {
          setPopcorn(popcornValue);
          setMix(mixValue);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    /*
     * 집계(API-057)와 기준정보(API-044)가 모두 실제 응답이므로 맛 코드가 같은 곳에서
     * 온다. 기준정보가 비어 오면 맛 이름 자리가 빈다(`api/taste.ts`).
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

  if (failed) {
    return (
      <>
        <ScreenHeader title="파티 팝콘 섞기" showBack />

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

  if (!popcorn || !mix) {
    return (
      <>
        <ScreenHeader title="파티 팝콘 섞기" showBack />
      </>
    );
  }

  const flavorByCode = new Map(flavors.map((flavor) => [flavor.code, flavor]));

  return (
    <>
      <ScreenHeader title="파티 팝콘 섞기" showBack />

      <div className={styles.page}>
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>참여자 취향 비교</h2>

          <div className={styles.memberGrid}>
            {popcorn.members.map((member) => {
              const view = mix.members.find((item) => item.userId === member.user.userId);
              const leadName = [...member.flavors]
                .sort((a, b) => b.popcornCount - a.popcornCount)
                .slice(0, LEAD_FLAVOR_COUNT)
                .map((item) => flavorByCode.get(item.flavorCode)?.displayName ?? '')
                .filter(Boolean)
                .join(' · ');

              return (
                <div key={member.user.userId} className={styles.memberCard}>
                  <div className={styles.memberHead}>
                    <span className={styles.avatar} aria-hidden="true" />
                    <span className={styles.memberName}>{member.user.nickname}</span>
                  </div>

                  <div className={styles.shareRow}>
                    <span className={styles.shareLabel}>{leadName}</span>
                    <span className={styles.track}>
                      <span
                        className={styles.trackFill}
                        style={{ width: `${view?.tasteShare ?? 0}%` }}
                      />
                    </span>
                    <span className={styles.shareValue}>{view?.tasteShare ?? 0}%</span>
                  </div>

                  <div className={styles.tags}>
                    {(view?.tags ?? []).map((tag) => (
                      <Chip key={tag}>{tag}</Chip>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        {/*
         * 파티원 전원의 맛을 합쳐 한 통에 담는다. 섞는다는 말이 그림으로 보인다.
         * 통과 알은 -101에서 구운 3D 스프라이트다.
         */}
        <section className={styles.mixer}>
          <PopcornBucket
            flavors={mixedFlavors(popcorn)}
            width={BUCKET_WIDTH}
            label={`파티원 ${popcorn.members.length}명의 팝콘을 섞은 통`}
          />
          <p className={styles.mixerNote}>
            파티원 {popcorn.members.length}명의 팝콘을 한 통에 담았어요
          </p>
        </section>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>공통 취향 결과</h2>

          {/* ② 에 실제 통이 들어갔으므로 장식용 에셋 상자는 두지 않는다 */}
          <div className={styles.resultRow}>
            <p className={styles.resultTitle}>공통 취향 일치 {mix.commonRate}%</p>

            <span className={styles.track}>
              <span className={styles.trackFill} style={{ width: `${mix.commonRate}%` }} />
            </span>

            <div className={styles.tags}>
              {mix.tags.map((tag) => (
                <Chip key={tag}>{tag}</Chip>
              ))}
            </div>
          </div>
        </section>
      </div>
    </>
  );
}
