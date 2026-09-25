/*
 * 5.7 파티 초대 수락 완료
 *
 * 이동표: 5.7 → 5.9 파티팝콘 시작하기 · 5.7 ⇢ 5.1 나중에 할게요
 *
 * **이 화면은 초대한 사람이 본다.** 와이어프레임이 5.9 옆에 그렇게 적어 두었다
 * (`5.6은 초대받은 사용자 관점, 5.7은 초대한 사용자에게 보이는 상태`). 그래서 문구가
 * `무비버디 님이 초대를 수락했어요!` — 내가 부른 사람이 들어왔다는 알림이다.
 *
 * `[?]` **누가 수락했는지 알려 주는 계약이 없다.** 그 규칙과 이유는 `joinedMember.ts`에
 * 두고 테스트로 고정했다. 알림 계약(8.1, 업무 -308)이 정해지면 그 파일만 바꾸면 된다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import StatusView from '../../../components/StatusView';
import { getParty } from '../../../api/parties';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import { findJoinedMember } from './joinedMember';
import type { Party } from '../../../types/party';
import styles from './PartyJoinedPage.module.css';

export default function PartyJoinedPage() {
  const navigate = useNavigate();
  const { partyId } = useParams();
  const id = Number(partyId);

  const [party, setParty] = useState<Party | null>(null);
  const [myUserId, setMyUserId] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    // 누가 들어왔는지 고르려면 내가 누구인지 알아야 한다(`findJoinedMember`)
    Promise.all([getParty(id), getMyProfile()])
      .then(([found, profile]) => {
        if (!active) return;

        if (!found) {
          setFailed(true);

          return;
        }

        setParty(found);
        setMyUserId(profile.userId);
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [id]);

  if (failed) {
    return (
      <div className={styles.page}>
        <StatusView titleAs="h1" title="파티를 찾을 수 없어요">
          <Button variant="text" onClick={() => void navigate(path.party())}>
            내 파티로 가기
          </Button>
        </StatusView>
      </div>
    );
  }

  if (!party || myUserId === null) return null;

  const joined = findJoinedMember(party.members, myUserId);
  const names = party.members.map((member) => member.nickname).join(' × ');

  return (
    <div className={styles.page}>
      <div className={styles.stage}>
        <h1 className={styles.title}>
          {joined ? `${joined.nickname} 님이 초대를 수락했어요!` : '파티가 준비됐어요!'}
        </h1>

        <p className={styles.lead}>
          이제 {party.members.length}명의 취향 필름을 합쳐
          <br />
          같이 볼 영화를 고를 수 있어요
        </p>

        <div className={styles.card}>
          <span className={styles.badge}>PARTY</span>
          <span className={styles.names}>{names}</span>
          <span className={styles.meta}>{party.name}</span>
        </div>
      </div>

      <div className={styles.actions}>
        <Button variant="brand" onClick={() => void navigate(path.partyPopcornMix(id))}>
          파티팝콘 시작하기
        </Button>

        <Button variant="text" onClick={() => void navigate(path.party())}>
          나중에 할게요
        </Button>
      </div>
    </div>
  );
}
