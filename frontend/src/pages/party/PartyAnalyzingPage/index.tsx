/*
 * 5.4 파티 공통 취향 분석 중
 *
 * 이동표: 5.3 → 5.4 파티 시작하기 · 5.4 → 5.5 공통 취향 분석 완료
 * 5.5 ⇢ 5.4 다시 추천받기 · 5.7 → 5.4 파티 모드 시작하기
 *
 * **분석은 별도 계약이 아니다.** API-058이 동기로 추천을 돌려주므로, 이 화면은 그 응답을
 * 기다리는 동안 보여 주는 전환 화면이다. 응답이 오면 5.5로 대체한다(`replace`).
 *
 * `[!]` **API-058은 백엔드에 아직 없다.** 파티원 이름은 실제 API-050에서 오지만 추천은
 * 목업이다(`api/parties.ts`).
 *
 * 화면이 곧바로 사라지면 깜빡이는 것처럼 보이므로 최소 노출 시간을 둔다. 와이어프레임이
 * `공통 필름 현상 중`이라는 장면을 하나의 프레임으로 그린 이상, 응답이 빨라도 그 장면은
 * 보여야 한다.
 *
 * `[?]` 와이어프레임은 파티원 이름 넷을 나열한다. 아직 파티를 막 만든 시점이라 실제
 * 멤버는 나 혼자일 수 있어, API-050에서 받은 실제 멤버를 쓴다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import StatusView from '../../../components/StatusView';
import { getParty, getPartyRecommendations } from '../../../api/parties';
import { path } from '../../../routes/paths';
import styles from './PartyAnalyzingPage.module.css';

/** 장면이 깜빡이지 않을 만큼의 최소 노출 시간 */
const MIN_VISIBLE_MS = 1200;

export default function PartyAnalyzingPage() {
  const navigate = useNavigate();
  const { partyId } = useParams();
  const id = Number(partyId);

  const [members, setMembers] = useState<string[]>([]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getParty(id)
      .then((party) => {
        if (active && party) {
          setMembers(party.members.map((member) => member.nickname));
        }
      })
      .catch(() => {
        // 이름을 못 받아도 분석은 진행한다. 자리만 비워 둔다
      });

    const waited = new Promise((resolve) => window.setTimeout(resolve, MIN_VISIBLE_MS));

    Promise.all([getPartyRecommendations(id), waited])
      .then(() => {
        if (active) void navigate(path.partyRecommendations(id), { replace: true });
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [id, navigate]);

  if (failed) {
    return (
      <div className={styles.page}>
        <StatusView
          titleAs="h1"
          title="공통 취향을 분석하지 못했어요"
          description="잠시 후 다시 시도해 주세요."
        >
          <Button variant="text" onClick={() => void navigate(path.party())}>
            내 파티로 돌아가기
          </Button>
        </StatusView>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.stage}>
        <h1 className={styles.title}>공통 필름 현상 중</h1>
        <p className={styles.lead}>두 취향이 겹치는 영화로 새 필름을 만들어요</p>

        <div className={styles.strip} aria-hidden="true">
          <span className={styles.light} />
        </div>

        {members.length > 0 ? (
          <ul className={styles.members}>
            {members.map((nickname) => (
              <li key={nickname} className={styles.member}>
                {nickname}
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </div>
  );
}
