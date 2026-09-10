/*
 * 5.1 참여 중인 파티 목록
 *
 * 이동표
 * 5.1 → 5.2 새 파티 만들기
 * 5.1 → 5.8 참여 중인 파티방 선택
 *
 * 파티방을 고르면 5.8 파티원 팝콘으로 간다. 5.8은 에픽 266에서 이미 구현했다.
 *
 * 와이어프레임은 파티마다 인원 배지와 `케이퍼 · 스릴러` 같은 공통 장르를 보여준다.
 * `[?]` **공통 장르는 API-050에 없다.** 파티 목록 응답은 이름 · 상태 · 멤버 · 정원뿐이다.
 * 없는 값을 지어내지 않고 멤버 이름으로 대신했다 — 5.1이 첫 파티에 쓰는
 * `김난다 × 무비버디` 표기가 같은 자리에 있다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getMyParties } from '../../../api/parties';
import { path } from '../../../routes/paths';
import type { PartyView } from '../../../types/unspecified';
import styles from './PartyListPage.module.css';

export default function PartyListPage() {
  const navigate = useNavigate();
  const [parties, setParties] = useState<PartyView[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMyParties()
      .then((value) => {
        if (!active) return;

        setParties(value);
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  return (
    <>
      <ScreenHeader
        title="내 파티"
        subtitle={parties ? `참여 중인 방 ${parties.length}개` : undefined}
      />

      <div className={styles.page}>
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

        {parties && parties.length === 0 ? (
          <StatusView
            variant="empty"
            title="참여 중인 파티가 없어요"
            description="친구와 취향 필름을 합쳐 볼까요?"
          />
        ) : null}

        {parties && parties.length > 0 ? (
          <ul className={styles.list}>
            {parties.map((party) => (
              <li key={party.partyId}>
                <button
                  type="button"
                  className={styles.row}
                  onClick={() => void navigate(path.partyPopcorn(party.partyId))}
                >
                  <span className={styles.count} aria-hidden="true">
                    {party.members.length}
                  </span>

                  <span className={styles.body}>
                    <span className={styles.name}>{party.name}</span>
                    <span className={styles.members}>
                      {party.members.map((member) => member.nickname).join(' × ')}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}

        {parties ? (
          <div className={styles.actions}>
            <Button onClick={() => void navigate(path.partyNew())}>새 파티 만들기</Button>
          </div>
        ) : null}
      </div>
    </>
  );
}
