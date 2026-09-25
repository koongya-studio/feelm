/*
 * 파티 추천 진입 (업무 S15P21E106-855)
 *
 * 5.8의 `함께 볼 영화 추천받기`와 데스크톱 5.1 보조 패널의 `파티 추천 보기`가 쓴다. 두 자리가
 * 같은 규칙으로 움직이도록 한 곳에 둔다.
 *
 * **5.9 모으기 · 섞기 장면은 내가 아직 못 본 묶음일 때만 보여 준다.** 이미 본 묶음을 다시 여는
 * 것이면 장면을 건너뛰고 5.5를 바로 연다. 파티원마다 따로 가린다 — A가 본 묶음이어도 B가 처음
 * 보면 B에게는 장면을 보여 준다. 한때 두 자리 모두 늘 5.9로 보내, 이미 본 결과도 들어올 때마다
 * 섞는 장면을 다시 거쳐야 했다.
 *
 * 1. API-058 일반 조회(`refresh=false`)로 지금 묶음을 받는다. 5.9가 장면을 시작하기 전에 부르는
 *    것과 같은 요청이라 서버 상태는 기존 흐름과 같다 — 기존 묶음은 쓰기 없이 그대로 오고, 처음
 *    추천받거나 추천 입력이 바뀐 파티는 서버가 새 묶음을 노출한다(API-058 처리 규칙)
 * 2. 그 묶음을 이 계정이 5.5에서 본 적이 있으면 5.5, 없으면 5.9(`utils/partyRecommendationSeen`).
 *    5.9 · 5.5가 다시 부르는 일반 조회는 같은 묶음을 그대로 돌려준다
 *
 * 기존 흐름(5.9)을 지키는 경우 — 모르면 장면을 보여 주는 쪽이 안전하다
 * - 파티원이 최소 인원보다 적다. 5.9가 친구의 입장을 기다린다
 * - 추천을 못 받았다. 준비 중 · 끝난 파티 등의 안내는 5.9가 장면 뒤에 기존대로 한다
 *
 * 5.5의 `다른 영화 추천받기`(`refresh=true`)는 이 규칙과 무관하게 늘 5.9를 거친다 — 새 묶음을
 * 받는 일이다.
 */
import { useContext, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { AuthContext } from '../contexts/authContext';
import { getPartyRecommendations, PARTY_MIN_MEMBERS } from '../api/parties';
import { path } from '../routes/paths';
import { hasSeenPartyRecommendation } from '../utils/partyRecommendationSeen';

export function usePartyRecommendationEntry(): {
  /** 파티의 추천으로 간다. 확인하는 동안은 다시 눌러도 무시한다 */
  open: (partyId: number, memberCount: number) => void;
  /** 지금 묶음을 본 적이 있는지 확인하는 중이다 */
  checking: boolean;
} {
  const navigate = useNavigate();
  // 로그인한 화면에서만 쓰지만, 계정을 모르면 본 적 없는 것으로 보고 장면을 보여 준다
  const userId = useContext(AuthContext)?.user?.userId ?? null;
  const [checking, setChecking] = useState(false);
  const checkingRef = useRef(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;

    return () => {
      mountedRef.current = false;
    };
  }, []);

  async function hasSeenCurrent(partyId: number): Promise<boolean> {
    if (userId === null) return false;

    try {
      return hasSeenPartyRecommendation(userId, partyId, await getPartyRecommendations(partyId));
    } catch {
      return false;
    }
  }

  async function open(partyId: number, memberCount: number) {
    if (checkingRef.current) return;

    if (memberCount < PARTY_MIN_MEMBERS) {
      void navigate(path.partyPopcornMix(partyId));
      return;
    }

    checkingRef.current = true;
    setChecking(true);

    const seen = await hasSeenCurrent(partyId);

    checkingRef.current = false;

    // 확인하는 사이 화면을 떠났으면 끌고 가지 않는다
    if (!mountedRef.current) return;

    setChecking(false);
    void navigate(seen ? path.partyRecommendations(partyId) : path.partyPopcornMix(partyId));
  }

  return {
    open: (partyId, memberCount) => void open(partyId, memberCount),
    checking,
  };
}
