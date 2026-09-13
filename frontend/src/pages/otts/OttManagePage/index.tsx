/*
 * 6.1 구독 OTT 관리
 *
 * 이동표
 * 7.1 → 6.1 OTT 관리 · 6.1 ⇢ 7.1 뒤로 가기
 * 1.2 ⇢ 6.1 초기 설정과 동일한 구독 상태 공유
 * 6.1 ⇢ EXT-01 나에게 맞는 OTT 찾기 (확장 기능)
 *
 * **1.2 구독 OTT 초기 설정과 상태를 공유한다.** 와이어프레임이 화면 아래에
 * `기준 구독: 넷플릭스 · 티빙 — 1.2 구독 OTT 초기 설정과 항상 일치해요`라고 못 박아
 * 두었다. 둘 다 API-026으로 읽고 API-027로 쓰므로 저절로 일치한다. 화면이 따로 상태를
 * 들고 있지 않다.
 *
 * 켜고 끌 때마다 API-027을 보낸다. 계약이 전체 교체(멱등)이므로 현재 목록에 하나를
 * 더하거나 뺀 목록을 통째로 보낸다.
 *
 * `[?]` 요금제와 월 요금(`프리미엄 · 매월 17,000원`)은 계약에 없다. 와이어프레임이
 * 적어 두었으나 API-025 기준정보에도 API-026에도 대응 필드가 없어 넣지 않았다. 사용자가
 * 직접 입력하는 값인지 서비스가 아는 값인지부터 정해야 한다.
 * `[?]` `나에게 맞는 OTT 찾기`(EXT-01)는 확장 기능이라 진입점을 두지 않았다.
 */
import { useEffect, useState } from 'react';
import ScreenHeader from '../../../components/ScreenHeader';
import OttBadge from '../../../components/OttBadge';
import StatusView from '../../../components/StatusView';
import Switch from '../../../components/Switch';
import Button from '../../../components/Button';
import { getMyOtts, getOtts, setMyOtts } from '../../../api/otts';
import { ottBadgeLetter } from '../../../utils/ott';
import type { Ott } from '../../../types/ott';
import styles from './OttManagePage.module.css';

export default function OttManagePage() {
  const [otts, setOtts] = useState<Ott[] | null>(null);
  const [subscribedIds, setSubscribedIds] = useState<number[]>([]);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    Promise.all([getOtts(), getMyOtts()])
      .then(([all, mine]) => {
        if (!active) return;

        setOtts(all);
        setSubscribedIds(mine.map((ott) => ott.ottId));
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  /**
   * 켜고 끄기. 계약이 전체 교체이므로 바뀐 목록을 통째로 보낸다.
   *
   * 화면을 먼저 바꾸고 요청을 보낸다. 실패하면 되돌린다 — 토글은 누르는 즉시 반응해야
   * 하고, 목업에서는 지연을 넣어야 그 차이가 보인다(`?mockDelay=800`).
   *
   * **되돌릴 때 누른 시점의 목록으로 덮지 않는다.** 두 개를 빠르게 연달아 누르면 요청이
   * 겹치는데, 먼저 보낸 것이 나중에 실패하면 그 사이 성공한 다른 토글까지 지워진다.
   * 실패한 요청이 바꾸려던 **그 항목 하나만** 최신 상태에서 되돌린다.
   */
  const toggle = (ottId: number) => {
    const wasSubscribed = subscribedIds.includes(ottId);
    const next = wasSubscribed
      ? subscribedIds.filter((id) => id !== ottId)
      : [...subscribedIds, ottId];

    setSubscribedIds(next);

    setMyOtts(next).catch(() => {
      setSubscribedIds((current) => {
        if (wasSubscribed) {
          return current.includes(ottId) ? current : [...current, ottId];
        }

        return current.filter((id) => id !== ottId);
      });
    });
  };

  const subscribed = (otts ?? []).filter((ott) => subscribedIds.includes(ott.ottId));
  const unsubscribed = (otts ?? []).filter((ott) => !subscribedIds.includes(ott.ottId));

  return (
    <>
      <ScreenHeader title="OTT 관리" subtitle="구독 중인 OTT 기준으로 추천해 드려요" showBack />

      <div className={styles.page}>
        {failed ? (
          <StatusView
            title="OTT 목록을 불러오지 못했어요"
            description="잠시 후 다시 시도해 주세요."
          >
            <Button variant="text" onClick={() => setReloadCount((count) => count + 1)}>
              다시 시도
            </Button>
          </StatusView>
        ) : null}

        {otts ? (
          <>
            <OttGroup
              label={`구독 중 ${subscribed.length}`}
              otts={subscribed}
              subscribed
              onToggle={toggle}
            />

            <OttGroup label="미구독" otts={unsubscribed} subscribed={false} onToggle={toggle} />

            <p className={styles.note}>끄면 해당 OTT 작품은 추천에서 제외돼요</p>
          </>
        ) : null}
      </div>
    </>
  );
}

function OttGroup({
  label,
  otts,
  subscribed,
  onToggle,
}: {
  label: string;
  otts: Ott[];
  subscribed: boolean;
  onToggle: (ottId: number) => void;
}) {
  if (otts.length === 0) return null;

  return (
    <section className={styles.group}>
      <h2 className={styles.groupLabel}>{label}</h2>

      <ul className={styles.list}>
        {otts.map((ott) => (
          <li key={ott.ottId} className={styles.row}>
            <OttBadge
              letter={ottBadgeLetter(ott.code)}
              name={ott.name}
              size={38}
              fontSize={16}
              radius={10}
              variant={subscribed ? 'selected' : 'unselected'}
              labelled={false}
            />

            <span className={styles.body}>
              <span className={styles.name}>{ott.name}</span>
              <span className={styles.state}>{subscribed ? '구독 중' : '구독 안 함'}</span>
            </span>

            <Switch
              checked={subscribed}
              label={`${ott.name} 구독`}
              onChange={() => onToggle(ott.ottId)}
            />
          </li>
        ))}
      </ul>
    </section>
  );
}
