/*
 * 5.4 취향 모으기 (파티팝콘 흐름 1/3)
 *
 * 이동표: 5.3 → 5.4 파티 시작하기 · 5.8 → 5.4 함께 볼 영화 추천받기
 * 5.4 → 5.9 취향 모으기 완료 · 5.7 → 5.4 파티 모드 시작하기 · 5.5 ⇢ 5.4 다시 추천받기
 *
 * **사용자 시안 2026-09-17(업무 -462)에서 이 화면이 흐름의 첫 단계가 됐다.** 이전에는
 * `공통 필름 현상 중`이라는 필름 띠 장면이었고 곧바로 5.5로 갔다. 시안은 모으기 →
 * 섞기 → 추천 세 단계를 같은 제목 아래 두므로, 다음은 5.9 팝콘 섞기다.
 *
 * 화면이 말하는 것은 **각자의 팝콘이 한 곳으로 모인다**는 것이다. 그래서 파티원마다
 * 자기 통을 그린다(API-057). 이름만 나열하던 이전 구성과 달라진 부분이다.
 *
 * **기계는 다음 화면에 있다.** 시안은 통들이 기계로 쏟아지는 장면을 5.4에 그렸지만,
 * 기계를 두 화면에 두면 같은 그림이 두 번 나오고 5.9의 섞는 장면이 시작점을 잃는다.
 * 여기서는 각자의 통까지 보여주고, 기계에 붓는 것은 5.9가 한다.
 *
 * 화면이 곧바로 사라지면 깜빡이는 것처럼 보이므로 최소 노출 시간을 둔다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import PartyFlowShell from '../../../components/PartyFlowShell';
import PopcornBucket from '../../../components/PopcornBucket';
import ScreenHeader from '../../../components/ScreenHeader';
import { kernelCounts } from '../../../components/PopcornBucket/kernels';
import StatusView from '../../../components/StatusView';
import { getParty, getPartyPopcorn } from '../../../api/parties';
import { path } from '../../../routes/paths';
import type { PartyMemberPopcorn } from '../../../types/party';
import styles from './PartyAnalyzingPage.module.css';

/**
 * 통이 모인 그림을 보여 주고 나서 다음 단계로 넘어가기까지의 시간.
 *
 * 응답을 기다리는 동안이 아니라 **응답이 온 뒤**에 센다. 그림이 모으기의 결과이므로
 * 응답이 늦게 오면 그림이 한 번도 보이지 않고 화면이 넘어가 버린다.
 */
const DWELL_MS = 1000;

/** 파티원 통. 넷이 한 줄에 들어가는 폭이다 */
const BUCKET_WIDTH = 84;

export default function PartyAnalyzingPage() {
  const navigate = useNavigate();
  const { partyId } = useParams();
  const id = Number(partyId);

  const [members, setMembers] = useState<PartyMemberPopcorn[]>([]);
  const [names, setNames] = useState<string[]>([]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getParty(id)
      .then((party) => {
        if (active && party) {
          setNames(party.members.map((member) => member.nickname));
        }
      })
      .catch(() => {
        // 파티 이름 목록을 못 받아도 팝콘은 API-057이 파티원까지 준다
      });

    let timer = 0;

    getPartyPopcorn(id)
      .then((popcorn) => {
        if (!active) return;

        setMembers(popcorn.members);

        timer = window.setTimeout(() => {
          if (active) void navigate(path.partyPopcornMix(id), { replace: true });
        }, DWELL_MS);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [id, navigate]);

  if (failed) {
    return (
      <>
        {/* 오류는 흐름 셸 없이 화면 전체를 덮는다 — 단계를 말할 상태가 아니다. 다만
            헤더는 5.5의 오류와 같이 남겨 뒤로 갈 길을 둔다 */}
        <ScreenHeader title="파티팝콘" showBack fallbackTo={path.party()} />

        <div className={styles.page}>
          <StatusView
            title="파티원의 팝콘을 모으지 못했어요"
            description="잠시 후 다시 시도해 주세요."
          >
            <Button variant="text" onClick={() => void navigate(path.party())}>
              내 파티로 돌아가기
            </Button>
          </StatusView>
        </div>
      </>
    );
  }

  /*
   * 파티원 이름은 두 곳에서 온다. API-057이 오기 전에도 줄이 비지 않도록 API-050의
   * 이름을 먼저 쓰고, 팝콘이 오면 그 응답의 파티원 순서를 따른다.
   */
  const shownNames = members.length > 0 ? members.map((member) => member.user.nickname) : names;

  return (
    <PartyFlowShell
      members={shownNames}
      title="우리의 취향을 모아요"
      subtitle="각자의 팝콘이 하나의 기계로"
      note="취향 팝콘을 담고 있어요"
      step="gather"
    >
      <div className={styles.buckets}>
        {members.map((member) => (
          <div key={member.user.userId} className={styles.bucket}>
            <PopcornBucket
              flavors={kernelCounts(member.flavors)}
              width={BUCKET_WIDTH}
              label={`${member.user.nickname}의 팝콘 ${member.totalCount}알`}
            />
          </div>
        ))}
      </div>
    </PartyFlowShell>
  );
}
