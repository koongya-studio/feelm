/*
 * 5.9 팝콘 모으기 · 섞기 (파티팝콘 흐름 1 · 2/3)
 *
 * 이동표: 5.3 → 5.9 파티 시작하기 · 5.8 → 5.9 함께 볼 영화 추천받기 · 5.7 → 5.9 파티 모드
 * 시작하기 · 5.5 ⇢ 5.9 다시 추천받기 · 5.9 → 5.5 추천 완성
 *
 * **모으기와 섞기가 한 화면의 이어지는 장면이다**(업무 -773, 사용자 결정 2026-09-21). 한때
 * 5.4 `우리의 취향을 모아요`가 따로 된 화면이라 큰 통 두 개를 보여 준 뒤 이 화면으로 넘어왔고,
 * 페이지가 통째로 바뀌며 장면이 뚝 끊겼다. 이제 기계가 처음부터 있고 파티원 통이 기계 위로
 * 내려와 모인다(`idle`) — 그것이 모으기다. 모이면 **저절로** 붓고 섞고 담은 뒤 5.5로 간다.
 *
 * **`팝콘 섞기` 버튼은 없다.** 2026-09-17에는 "섞는 것은 사용자가 시작한다"고 정해 버튼을
 * 두었는데, 파티 메인 → 파티 팝콘 보기 → 추천받기 → 팝콘 섞기로 누를 곳이 많다는 피드백을
 * 받아 사용자가 바꿨다. 장면은 그대로 끝까지 보여 준다 — 스스로 흘러도 기계가 도는 시간이
 * 이 화면의 내용이다.
 *
 * **계약에 없던 수치를 모두 걷어냈다**(사용자 결정 2026-09-17). 참여자별 취향 비율
 * `46% · 38%`, 공통 취향 일치 `72%`, 태그 4종은 산출 규칙이 계약에 없어 시안에서도 뺐다(CR-17).
 *
 * 연동 API는 API-057 파티원 팝콘 조회와 API-058 파티 영화 추천이다. 섞인 통의 맛은
 * API-057에서 오고, 추천은 **화면을 열자마자** 부른다 — 장면이 도는 동안 받아 두면 5.5가 빈
 * 화면으로 시작하지 않는다. 추천을 받지 못하면 장면을 끝까지 보여 준 뒤 이 화면에서 알린다.
 *
 * **다시 받기는 이 화면이 보낸다**(업무 -802). 5.5의 `다른 영화 추천받기`는 라우터 state
 * `refresh: true`를 들고 여기로 오고, 그때만 API-058에 `refresh=true`를 붙여 다음 묶음을
 * 받는다. 5.5가 그 뒤에 부르는 본요청은 일반 조회라 방금 노출한 묶음이 그대로 온다. 파티
 * 목록 · 5.8 · 5.3에서 들어온 첫 진입은 일반 조회다 — 들를 때마다 묶음이 앞으로 가면 안 된다.
 * 처음 그릴 때 한 번만 읽고 주소의 state는 지운다. 새로고침이 다시 받기가 되면 안 된다.
 */
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import PartyFlowShell from '../../../components/PartyFlowShell';
import PopcornMachine from '../../../components/PopcornMachine';
import { kernelCounts } from '../../../components/PopcornBucket/kernels';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { isApiError } from '../../../api/apiError';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import { path } from '../../../routes/paths';
import { mixedFlavors } from '../../../utils/party';
import styles from './PartyPopcornMixPage.module.css';
import type { MachinePhase } from '../../../components/PopcornMachine';
import type { PartyPopcorn } from '../../../types/party';

/**
 * 모으기. 파티원 통이 기계 위로 다 내려온 뒤 한 번 보이고 나서 섞기가 시작된다.
 *
 * 응답을 기다리는 동안이 아니라 **팝콘(API-057)이 온 뒤**에 센다. 통이 모인 그림이 모으기의
 * 결과이므로 응답이 늦으면 그림이 한 번도 보이지 않고 섞기로 넘어가 버린다.
 */
const GATHER_MS = 1400;

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

/** 단계 순서. `pouring`은 모으기가 끝나는 즉시 시작하므로 여기서는 그 다음부터 센다 */
const AFTER_POURING: Exclude<MachinePhase, 'idle' | 'pouring'>[] = ['mixing', 'filling', 'done'];

/** 단계별 제목. 모으기 · 섞는 중 · 담긴 뒤가 다르다 */
function phaseTitle(phase: MachinePhase): string {
  if (phase === 'idle') return '우리의 취향을 모아요';
  if (phase === 'done') return '새 팝콘이 완성됐어요';

  return '취향이 섞이는 중';
}

/** 단계별 부제 */
function phaseSubtitle(phase: MachinePhase, memberCount: number): string {
  if (phase === 'idle') return '각자의 팝콘이 하나의 기계로';
  if (phase === 'done') return `${memberCount}명의 취향을 한 통에 담았어요`;

  return '우리에게 어울리는 영화를 찾고 있어요';
}

/**
 * 진행 표시 위의 문구. 단계가 말이 되게 바뀐다.
 *
 * 스스로 흐르는 화면의 유일한 신호가 이 문구라 보조기술도 함께 읽는다
 * (`PartyFlowShell`이 라이브 영역으로 둔다).
 */
function phaseNote(phase: MachinePhase): string {
  if (phase === 'idle') return '취향 팝콘을 담고 있어요';
  if (phase === 'done') return '새 팝콘이 한 통에 담겼어요';

  return '팝콘을 섞고 있어요';
}

/** 추천을 받지 못했을 때의 안내. 준비 중(503)이면 기다리면 될 일이라 따로 말한다 */
function recommendFailure(error: unknown): string {
  if (isApiError(error) && error.code === 'RECOMMENDATION_UNAVAILABLE') {
    return '추천을 준비하고 있어요. 잠시 후 다시 시도해 주세요.';
  }

  return '잠시 후 다시 시도해 주세요.';
}

type Failure = { kind: 'popcorn' } | { kind: 'recommend'; message: string };

/** 5.5가 `다른 영화 추천받기`로 넘길 때 싣는 state */
export type PartyMixEntry = { refresh: true };

/** 라우터 state에서 다시 받기 표시를 꺼낸다. 다른 화면이 남긴 state는 없는 것으로 본다 */
function isRefreshEntry(state: unknown): boolean {
  return Boolean(
    state && typeof state === 'object' && (state as { refresh?: unknown }).refresh === true,
  );
}

export default function PartyPopcornMixPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { partyId } = useParams();

  const id = Number(partyId);

  /** 다시 받기로 들어왔는지. 처음 그릴 때 한 번만 읽는다 — 아래에서 state를 지운다 */
  const [refresh] = useState(() => isRefreshEntry(location.state));

  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [names, setNames] = useState<string[]>([]);
  const [phase, setPhase] = useState<MachinePhase>('idle');
  const [failure, setFailure] = useState<Failure | null>(null);
  const [retrying, setRetrying] = useState(false);
  /**
   * 화면이 떠 있는지. `다시 시도`의 응답은 화면을 떠난 뒤에 올 수 있다 — `내 파티로 돌아가기`를
   * 누르고 나서 성공이 오면 5.1에 있던 사용자를 5.5로 끌고 간다(!531 리뷰).
   */
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;

    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (refresh) void navigate(location.pathname, { replace: true, state: null });
    // 한 번만. `location`을 의존성에 넣으면 지운 뒤의 이동이 다시 효과를 돌린다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    let active = true;
    const timers: number[] = [];

    // 장면이 도는 동안 받아 둔다. 실패는 여기서 삼키고 장면이 끝날 때 알린다
    const request = refresh
      ? getPartyRecommendations(id, { refresh: true })
      : getPartyRecommendations(id);
    request.catch(() => undefined);

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
        if (!active) return;

        setPopcorn(value);

        // 모으기 → 붓기 → 섞기 → 담기 → 완성. 모두 스스로 넘어간다
        let at = GATHER_MS;
        timers.push(window.setTimeout(() => setPhase('pouring'), at));
        at += PHASE_MS.pouring;

        for (const next of AFTER_POURING) {
          timers.push(window.setTimeout(() => setPhase(next), at));
          at += PHASE_MS[next];
        }

        timers.push(
          window.setTimeout(() => {
            request
              .then(() => {
                if (active) void navigate(path.partyRecommendations(id), { replace: true });
              })
              .catch((error: unknown) => {
                if (active) setFailure({ kind: 'recommend', message: recommendFailure(error) });
              });
          }, at),
        );
      })
      .catch(() => {
        if (active) setFailure({ kind: 'popcorn' });
      });

    return () => {
      // 화면을 떠나면 남은 단계를 끊는다
      active = false;
      for (const timer of timers) window.clearTimeout(timer);
    };
  }, [id, navigate, refresh]);

  /**
   * 추천만 다시 묻는다. 팝콘은 이미 섞였으므로 장면을 되풀이하지 않는다. 다시 받기로
   * 들어왔으면 재시도도 다시 받기다 — 실패한 요청은 아무것도 노출하지 않았다.
   */
  async function retryRecommendation() {
    setRetrying(true);

    try {
      await (refresh
        ? getPartyRecommendations(id, { refresh: true })
        : getPartyRecommendations(id));
      if (mounted.current) void navigate(path.partyRecommendations(id), { replace: true });
    } catch (error) {
      if (!mounted.current) return;

      setRetrying(false);
      setFailure({ kind: 'recommend', message: recommendFailure(error) });
    }
  }

  if (failure) {
    return (
      <>
        {/* 오류는 흐름 셸 없이 화면 전체를 덮는다 — 단계를 말할 상태가 아니다. 다만
            헤더는 5.5의 오류와 같이 남겨 뒤로 갈 길을 둔다 */}
        <ScreenHeader title="파티팝콘" showBack fallbackTo={path.party()} />

        <div className={styles.page}>
          {failure.kind === 'popcorn' ? (
            <StatusView
              title="파티원의 팝콘을 모으지 못했어요"
              description="잠시 후 다시 시도해 주세요."
            >
              <Button variant="text" onClick={() => void navigate(path.party())}>
                내 파티로 돌아가기
              </Button>
            </StatusView>
          ) : (
            <StatusView title="함께 볼 영화를 아직 고르지 못했어요" description={failure.message}>
              <Button disabled={retrying} onClick={() => void retryRecommendation()}>
                다시 시도
              </Button>
              <Button variant="text" onClick={() => void navigate(path.party())}>
                내 파티로 돌아가기
              </Button>
            </StatusView>
          )}
        </div>
      </>
    );
  }

  const shownNames = popcorn ? popcorn.members.map((member) => member.user.nickname) : names;

  return (
    <PartyFlowShell
      members={shownNames}
      title={phaseTitle(phase)}
      subtitle={phaseSubtitle(phase, shownNames.length)}
      note={phaseNote(phase)}
      step={phase === 'idle' ? 'gather' : 'mix'}
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
    </PartyFlowShell>
  );
}
