/*
 * 5.6 파티 초대 수신
 *
 * 이동표
 * 5.6 → 5.1 초대 수락
 * 5.6 ⇢ 8.1 초대 거절 후 알림 목록 복귀
 *
 * 와이어프레임이 5.9 옆에 덧붙여 둔 설명: **5.6은 초대받은 사용자 관점의 화면이고,
 * 5.7은 초대한 사용자에게 보이는 상태다.** 그래서 두 화면이 짝이 아니라 서로 다른
 * 사람이 본다.
 *
 * 거절하면 8.1 알림 목록으로 간다(업무 -308에서 이었다). 수락은 5.1 내 파티다.
 *
 * `[?]` `2인`은 **나까지 세었을 때의 인원**이다. API-052 `memberCount`는 지금 참여 중인
 * 사람 수이므로 여기에 1을 더해야 수락한 뒤의 5.7 `김난다 × 무비버디`와 앞뒤가 맞는다.
 *
 * `[!]` **초대 단건 조회 계약이 없다.** API-052 목록에서 번호로 고르므로 이미 답했거나
 * 파티가 종료된 초대는 목록에 없어 찾지 못한 상태가 된다. 두 경우를 가르는 값이 없어
 * 안내도 하나다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import StatusView from '../../../components/StatusView';
import { acceptInvitation, declineInvitation, getInvitation } from '../../../api/parties';
import { path } from '../../../routes/paths';
import type { PartyInvitation } from '../../../types/party';
import styles from './PartyInvitationPage.module.css';

export default function PartyInvitationPage() {
  const navigate = useNavigate();
  const { invitationId } = useParams();
  const id = Number(invitationId);

  const [invitation, setInvitation] = useState<PartyInvitation | null>(null);
  const [failed, setFailed] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;

    getInvitation(id)
      .then((value) => {
        if (!active) return;

        // 목록에 없는 초대다. 이미 답했거나 파티가 종료된 경우다
        if (!value) {
          setFailed(true);

          return;
        }

        setInvitation(value);
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [id]);

  const answer = (accept: boolean) => {
    setSubmitting(true);

    const request = accept ? acceptInvitation(id) : declineInvitation(id);

    request
      .then(() => {
        void navigate(accept ? path.party() : path.notifications(), { replace: true });
      })
      .catch(() => {
        setSubmitting(false);
        setFailed(true);
      });
  };

  if (failed) {
    return (
      <div className={styles.page}>
        <StatusView
          titleAs="h1"
          title="초대를 확인하지 못했어요"
          description="이미 응답했거나 파티가 종료됐을 수 있어요."
        >
          <Button variant="text" onClick={() => void navigate(path.party())}>
            내 파티로 가기
          </Button>
        </StatusView>
      </div>
    );
  }

  if (!invitation) return null;

  const { party } = invitation;

  return (
    <div className={styles.page}>
      <div className={styles.stage}>
        <h1 className={styles.title}>파티 초대가 도착했어요!</h1>
        <p className={styles.lead}>
          {party.owner.nickname} 님이 취향 필름을 합쳐
          <br />
          같이 볼 영화를 고르고 싶어 해요
        </p>

        <div className={styles.card}>
          <span className={styles.badge}>PARTY INVITE</span>
          <span className={styles.partyName}>{party.owner.nickname} 님의 파티</span>
          <span className={styles.partyMeta}>
            {party.memberCount + 1}인 · {party.name}
          </span>
        </div>
      </div>

      <div className={styles.actions}>
        <Button disabled={submitting} onClick={() => answer(true)}>
          초대 수락하기
        </Button>

        <Button variant="text" disabled={submitting} onClick={() => answer(false)}>
          거절
        </Button>
      </div>
    </div>
  );
}
