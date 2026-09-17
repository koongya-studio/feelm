/*
 * 5.1 파티 시작 (참여 중인 파티 목록)
 *
 * 이동표
 * 5.1 → 5.2 파티 만들기
 * 5.1 → 5.8 참여 중인 파티방 선택
 *
 * **사용자 시안 2026-09-17(업무 -462)이 이 화면의 머리를 바꿨다.** 이전에는 `내 파티` ·
 * `참여 중인 방 N개`라는 목록 머리말이었고, 시안은 워드마크와 `파티팝콘`이라는 이름으로
 * 파티 모드가 무엇인지 먼저 말한다. 목록은 그 아래에 놓인다.
 *
 * **통은 파티가 있든 없든 그 자리에 있다.** 시안의 가운데를 통이 잡고 있고, 목록만
 * 두면 목록과 버튼 사이가 빈 화면이 된다. 목록은 제목 아래에 놓이고 통은 그 아래
 * 남은 자리를 쓴다. 파티는 최대 4개까지 흔치 않아 통이 밀리지 않는다.
 *
 * 통에는 **첫 파티의 팝콘**을 담는다(API-057). 빈 통을 두면 파티가 있는데도 아무것도
 * 담기지 않은 것으로 보인다. 파티가 여러 개일 때 어느 것을 담을지는 시안에 없어 목록의
 * 첫 파티를 쓰고, 통의 이름이 그 파티를 말한다. 파티가 없으면 빈 통이다.
 *
 * `[?]` **`초대 코드로 참여하기`는 계약에 없다.** API-051 초대는 닉네임 검색(API-019)으로
 * 찾은 `userId`를 받고, 코드나 링크로 참여하는 경로는 정의되어 있지 않다. 시안이 확정한
 * 자리이므로 버튼은 두고, 누르면 준비 중임을 알린다(`wireframe-change-requests.md` CR-26).
 *
 * 목록은 **활성 파티만** 보여준다(API-050 기본값). 나가거나 종료한 파티는 여기서 빠진다.
 *
 * `[?]` 와이어프레임은 파티마다 `케이퍼 · 스릴러` 같은 **공통 장르**를 보여주는데
 * API-050 응답에는 없다(이름 · 상태 · 멤버 · 정원뿐). 없는 값을 지어내지 않고 같은
 * 자리에 멤버 이름을 둔다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import PopcornBucket from '../../../components/PopcornBucket';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import Wordmark from '../../../components/Wordmark';
import { getMyParties, getPartyPopcorn } from '../../../api/parties';
import { path } from '../../../routes/paths';
import { mixedFlavors } from '../../../utils/party';
import type { Party, PartyPopcorn } from '../../../types/party';
import styles from './PartyListPage.module.css';

/** 화면 가운데를 잡는 통. 시안의 크기다 */
const BUCKET_WIDTH = 188;

export default function PartyListPage() {
  const navigate = useNavigate();
  const [parties, setParties] = useState<Party[] | null>(null);
  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [failed, setFailed] = useState(false);
  const [notice, setNotice] = useState('');
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMyParties()
      .then(async (value) => {
        if (!active) return;

        setParties(value);
        setFailed(false);

        const first = value[0];

        if (!first) return;

        // 통을 못 채워도 목록은 보여준다. 빈 통으로 남는다
        try {
          const firstPopcorn = await getPartyPopcorn(first.partyId);

          if (active) setPopcorn(firstPopcorn);
        } catch {
          // 그대로 둔다
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  const hasParties = parties !== null && parties.length > 0;

  return (
    <div className={styles.page}>
      <div className={styles.brand}>
        <Wordmark />

        <p className={styles.copy}>
          좋은 영화는,
          <br />
          함께 볼 때 더 특별하니까
        </p>
      </div>

      <div className={styles.titleBlock}>
        <h1 className={styles.title}>파티팝콘</h1>
        <p className={styles.subtitle}>같이 볼 영화, 함께 골라요</p>
      </div>

      {failed ? (
        <StatusView title="파티 목록을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요.">
          <Button variant="text" onClick={() => setReloadCount((count) => count + 1)}>
            다시 시도
          </Button>
        </StatusView>
      ) : null}

      {hasParties ? (
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

      {parties !== null ? (
        <div className={styles.stage}>
          <PopcornBucket
            flavors={popcorn ? mixedFlavors(popcorn) : []}
            width={BUCKET_WIDTH}
            label={
              hasParties && parties[0]
                ? `${parties[0].name}의 팝콘`
                : '아직 파티원의 팝콘이 담기지 않은 통'
            }
          />
        </div>
      ) : null}

      {parties !== null ? (
        <div className={styles.actions}>
          <p className={styles.hint}>친구와 취향을 모아 영화를 추천받아요</p>

          <Button variant="brand" onClick={() => void navigate(path.partyNew())}>
            파티 만들기
          </Button>

          <Button
            variant="secondary"
            onClick={() => setNotice('초대 코드로 참여하기는 준비 중이에요')}
          >
            초대 코드로 참여하기
          </Button>
        </div>
      ) : null}

      {notice ? (
        <Snackbar actionLabel="확인" onAction={() => setNotice('')}>
          {notice}
        </Snackbar>
      ) : null}
    </div>
  );
}
