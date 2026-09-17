/*
 * 5.9 팝콘 섞기 (파티팝콘 흐름 2/3)
 *
 * 이동표: 5.4 → 5.9 취향 모으기 완료 · 5.9 → 5.5 추천 완성
 *
 * **사용자 시안 2026-09-17(업무 -462)에서 흐름의 가운데 단계가 됐다.** 이전에는 5.8
 * 파티원 팝콘과 3.2 팝콘 통에서 따로 들어가 참여자 취향 비교 · 팝콘 믹서 · 공통 취향
 * 결과 세 덩이를 보여주는 화면이었다.
 *
 * **섞는 것은 사용자가 시작한다**(사용자 지시 2026-09-17). `팝콘 섞기`를 누르면 파티원의
 * 팝콘이 기계로 들어가고, 기계 안에서 섞여, 아래 통에 **하나의 새 팝콘**으로 담긴다.
 * 스스로 넘어가던 화면을 버튼으로 바꾼 이유는 이 장면이 이 화면의 내용이어서다 —
 * 지나가는 전환이면 누구도 보지 못한다. 담긴 뒤에 5.5로 간다.
 *
 * **계약에 없던 수치를 모두 걷어냈다**(사용자 결정 2026-09-17). 참여자별 취향 비율
 * `46% · 38%`, 공통 취향 일치 `72%`, 태그 4종은 산출 규칙이 계약에 없어 `getPartyMix`
 * 자리표시자에서 받아 쓰던 값이다(CR-17). 시안에 그 수치가 하나도 없어 화면에서 빼고
 * 자리표시자 호출도 지웠다. 계약이 정해지면 그때 넣는다.
 *
 * 연동 API는 API-057 파티원 팝콘 조회다. 섞인 통의 맛은 여기서 온다.
 * 추천(API-058)은 백엔드에 아직 없어 목업으로 떨어진다(`api/parties.ts`).
 */
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import PartyFlowShell from '../../../components/PartyFlowShell';
import PopcornMachine from '../../../components/PopcornMachine';
import { kernelCounts } from '../../../components/PopcornBucket/kernels';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import { path } from '../../../routes/paths';
import { mixedFlavors } from '../../../utils/party';
import styles from './PartyPopcornMixPage.module.css';
import type { MachinePhase } from '../../../components/PopcornMachine';
import type { PartyPopcorn } from '../../../types/party';

/**
 * 단계마다 머무는 시간(ms). 합이 섞는 장면의 길이다.
 *
 * 붓기 → 섞기 → 담기 순으로 빨라진다. 담기고 나면 결과를 한 번 보여준 뒤 5.5로 간다.
 */
const PHASE_MS: Record<Exclude<MachinePhase, 'idle'>, number> = {
  pouring: 1000,
  mixing: 1900,
  filling: 1100,
  done: 800,
};

/** 단계 순서. `pouring`은 누른 즉시 시작하므로 여기서는 그 다음부터 센다 */
const AFTER_POURING: Exclude<MachinePhase, 'idle' | 'pouring'>[] = ['mixing', 'filling', 'done'];

/** 단계별 제목. 누르기 전 · 섞는 중 · 담긴 뒤가 다르다 */
function phaseTitle(phase: MachinePhase): string {
  if (phase === 'idle') return '취향을 섞어 볼까요?';
  if (phase === 'done') return '새 팝콘이 완성됐어요';

  return '취향이 섞이는 중';
}

/** 단계별 부제 */
function phaseSubtitle(phase: MachinePhase, memberCount: number): string {
  if (phase === 'idle') return '각자의 팝콘을 기계에 넣어 섞어요';
  if (phase === 'done') return `${memberCount}명의 취향을 한 통에 담았어요`;

  return '우리에게 어울리는 영화를 찾고 있어요';
}

/**
 * 진행 표시 위의 문구. 단계가 말이 되게 바뀐다.
 *
 * 기다리는 화면의 유일한 신호가 이 문구라 보조기술도 함께 읽는다
 * (`PartyFlowShell`이 라이브 영역으로 둔다).
 */
function phaseNote(phase: MachinePhase): string {
  if (phase === 'idle') return '팝콘 섞기를 누르면 시작해요';
  if (phase === 'done') return '새 팝콘이 한 통에 담겼어요';

  return '팝콘을 섞고 있어요';
}

export default function PartyPopcornMixPage() {
  const navigate = useNavigate();
  const { partyId } = useParams();

  const id = Number(partyId);

  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [names, setNames] = useState<string[]>([]);
  const [phase, setPhase] = useState<MachinePhase>('idle');
  const [failed, setFailed] = useState(false);
  const timers = useRef<number[]>([]);

  useEffect(() => {
    let active = true;

    getParty(id)
      .then((party) => {
        if (active && party) {
          setNames(party.members.map((member) => member.nickname));
        }
      })
      .catch(() => {
        // 파티 이름 목록을 못 받아도 API-057이 파티원까지 준다
      });

    getPartyPopcorn(id)
      .then((value) => {
        if (active) setPopcorn(value);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [id]);

  useEffect(
    () => () => {
      // 화면을 떠날 때 남은 단계를 끊는다. mix()가 넣은 것을 지워야 하므로 여기서 읽는다
      for (const timer of timers.current) {
        window.clearTimeout(timer);
      }
    },
    [],
  );

  /**
   * 섞기 시작. 단계를 차례로 넘기고 마지막에 5.5로 간다.
   *
   * 추천(API-058)은 여기서 함께 부른다 — 모션이 도는 동안 받아 두면 5.5가 빈 화면으로
   * 시작하지 않고, 실패하면 5.5로 보내지 않고 이 화면에서 알린다.
   */
  function mix() {
    setPhase('pouring');

    let at = PHASE_MS.pouring;
    const scheduled = AFTER_POURING.map((next) => {
      const timer = window.setTimeout(() => setPhase(next), at);

      at += PHASE_MS[next];

      return timer;
    });

    const recommended = getPartyRecommendations(id);

    const move = window.setTimeout(() => {
      recommended
        .then(() => void navigate(path.partyRecommendations(id), { replace: true }))
        .catch(() => setFailed(true));
    }, at);

    timers.current = [...scheduled, move];
  }

  if (failed) {
    return (
      <>
        {/* 오류는 흐름 셸 없이 화면 전체를 덮는다 — 단계를 말할 상태가 아니다. 다만
            헤더는 5.5의 오류와 같이 남겨 뒤로 갈 길을 둔다 */}
        <ScreenHeader title="파티팝콘" showBack fallbackTo={path.party()} />

        <div className={styles.page}>
          <StatusView title="팝콘을 섞지 못했어요" description="잠시 후 다시 시도해 주세요.">
            <Button variant="text" onClick={() => void navigate(path.party())}>
              내 파티로 돌아가기
            </Button>
          </StatusView>
        </div>
      </>
    );
  }

  const shownNames = popcorn ? popcorn.members.map((member) => member.user.nickname) : names;
  const mixing = phase !== 'idle';

  return (
    <PartyFlowShell
      members={shownNames}
      title={phaseTitle(phase)}
      subtitle={phaseSubtitle(phase, shownNames.length)}
      note={phaseNote(phase)}
      step="mix"
    >
      <div className={styles.stage}>
        {popcorn ? (
          <PopcornMachine
            members={popcorn.members.map((member) => ({
              nickname: member.user.nickname,
              flavors: kernelCounts(member.flavors),
            }))}
            mixed={mixedFlavors(popcorn)}
            phase={phase}
          />
        ) : null}
      </div>

      {/*
       * 섞는 동안에는 버튼을 두지 않는다. 장면이 도는 중에 같은 버튼이 남아 있으면
       * 두 번 누를 수 있고, 그러면 단계가 겹쳐 돈다.
       */}
      {popcorn && !mixing ? (
        <div className={styles.actions}>
          <Button variant="brand" onClick={mix}>
            팝콘 섞기
          </Button>
        </div>
      ) : null}
    </PartyFlowShell>
  );
}
