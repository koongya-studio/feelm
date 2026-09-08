/*
 * 5.9 파티 팝콘 섞기
 *
 * 요소와 순서는 와이어프레임 5.9 그대로다(screen-spec.md 7장 5.9).
 * ① 참여자 취향 비교 · ② 팝콘 믹서 · ③ 공통 취향 결과
 *
 * 연동 API는 API-057 파티원 팝콘 조회다. 참여자 이름과 팝콘 집계는 여기서 온다.
 *
 * **① 의 `46%` · `38%`, ③ 의 `72%`, 그리고 태그 4종은 계약에 대응이 없다(CR-17).**
 * 산출 규칙이 없고 API-057의 맛별 집계로도 재현되지 않아 `getPartyMix`로 따로 가져온다.
 * 자세한 이유는 types/unspecified.ts에 있다.
 *
 * ① 의 대표 취향 이름(`액션 · 모험`)은 알이 많은 상위 두 맛의 `displayName`을 이어
 * 만든다. 와이어프레임은 장르명을 쓰지만 계약이 API-044의 표시명을 쓰라고 정하고 있다
 * (screen-spec.md 8.1 #2).
 *
 * `[!]` ② 팝콘 믹서는 와이어프레임에서도 `이미지 · SVG · 애니메이션 에셋 영역`이라는
 * 자리로만 표기돼 있다. 시각 표현이 확정되지 않아 그 자리를 그대로 둔다.
 * 확정되면 이 영역만 교체한다.
 *
 * 팀 결정 2026-09-07 — 배경은 확정 토큰(흰색)을 쓴다. 와이어프레임 5.9만 `#F6EFE3`이다.
 *
 * `[?]` 3~4명 파티에서의 배치는 미확정이다. 참여자 카드를 두 칸씩 줄바꿈해 둔다.
 * `[?]` 3.2에서 진입한 경우와 5.8에서 진입한 경우의 차이는 미확정이라 같은 화면을 쓴다.
 */
import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import Chip from '../../../components/Chip';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getPartyMix, getPartyPopcorn } from '../../../api/parties';
import { getPopcornFlavors } from '../../../api/taste';
import styles from './PartyPopcornMixPage.module.css';
import type { PartyPopcorn } from '../../../types/party';
import type { PopcornFlavor } from '../../../types/taste';
import type { PartyMixView } from '../../../types/unspecified';

/** 대표 취향 이름에 쓰는 맛 개수. 와이어프레임이 두 개를 이어 붙인다 */
const LEAD_FLAVOR_COUNT = 2;

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

    Promise.all([getPartyPopcorn(id), getPartyMix(id)])
      .then(([popcornValue, mixValue]) => {
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

    // 맛 기준정보는 실패해도 본문을 막지 않는다. 맛 이름만 코드로 남는다
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
        <section className={styles.card}>
          <h2 className={styles.cardTitle}>① 참여자 취향 비교</h2>

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

        <section className={styles.card}>
          <h2 className={styles.cardTitle}>② 팝콘 믹서</h2>

          {/* 시각 표현이 확정되지 않아 와이어프레임의 에셋 자리를 그대로 둔다 */}
          <div className={styles.assetSlot} />
          <p className={styles.assetNote}>이미지 · SVG · 애니메이션 에셋 영역</p>
        </section>

        <section className={styles.card}>
          <h2 className={styles.cardTitle}>③ 공통 취향 결과</h2>

          <div className={styles.resultRow}>
            <div className={styles.resultAsset} />

            <div className={styles.resultBody}>
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
          </div>
        </section>
      </div>
    </>
  );
}
