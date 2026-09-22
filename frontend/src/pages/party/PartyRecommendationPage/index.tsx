/*
 * 5.5 추천 완성 (파티팝콘 흐름 3/3)
 *
 * 이동표
 * 5.9 → 5.5 팝콘 섞기 완료
 * 5.5 ⇢ 2.4 추천 영화 선택
 * 5.5 ⇢ 5.9 다른 영화 추천받기
 *
 * **사용자 시안 2026-09-17(업무 -462)에서 흐름의 마지막 단계가 됐다.** 들어오는 곳이
 * 5.4에서 5.9로 바뀌었고, 섞인 통이 결과의 머리에 놓인다 — 세 사람의 팝콘을 한 통에
 * 담은 결과가 이 3편이라는 말을 그림이 먼저 한다.
 *
 * `다른 영화 추천받기`가 모으기로 되돌아가는 것은 이동표가 정한 것이다(한때 5.4, 업무
 * -773부터 5.9). 결과만 갈아 끼우지 않고 모으기 · 섞기 장면을 다시 보여준다. **그 이동에
 * `refresh: true`를 실어 보낸다**(업무 -802) — 5.9가 API-058에 `refresh=true`를 붙여 다음
 * 묶음을 받고, 이 화면의 본요청은 일반 조회라 방금 노출한 묶음이 그대로 온다. 한때는 같은
 * 세 편이 되풀이됐다(백엔드 -792 전). 응답의 `hasNext`가 거짓이면 더 보여줄 후보가 없는
 * 것이라 버튼을 감추고 그렇다고 알린다. 파티원이 평가 · 관심없음 · 감상 확정을 하거나
 * 파티원이 바뀌면 새 세트가 생겨 다시 참이 될 수 있다.
 *
 * **계약에 없던 값을 모두 걷어냈다**(사용자 결정 2026-09-17).
 * - 상단 공통 키워드 칩. 대응 필드가 API-058에 없어 `getPartyMix`(CR-17) 자리표시자를
 *   실제 파티원에게 붙여 쓰고 있었다
 * - 카드마다 붙던 `일치 94%`. 화면이 쓰던 하나의 값은 목업의 사용자별 점수 평균이었다
 * - `왜 같이 추천됐나요?` 문장
 *
 * 시안에 이 셋이 하나도 없어 화면에서 빼고 자리표시자 호출도 지웠다. 계약이 정해지면
 * 그때 넣는다.
 *
 * **실제 API-058에 연결했다**(업무 -705). 실제 응답에는 `reason` 자체가 없어 카드의 한 줄
 * 이유(`reason.summary`)도 걷어냈다 — 목업만 채우던 값이다. 카드는 서버 영화이므로 다시
 * 영화 상세(2.4)로 간다.
 *
 * 추천을 못 받은 까닭을 가른다. 가를 수 있어야 사용자가 다음에 할 일을 안다.
 * - `404` 없는 파티이거나 파티원이 아니다 · `409 PARTY_CLOSED` 끝난 파티다 → 내 파티로
 * - `503 RECOMMENDATION_UNAVAILABLE` 파티원 취향이 아직 준비되지 않았다 → 다시 추천받기
 * - 추천이 0편 → 다시 추천받기. 파티원이 이미 평가 · 감상 · 관심없음 처리한 영화는 서버가
 *   **묶음을 새로 고르는 시점**(세트 생성 · 첫 노출 · 다시 받기)에 뺀다. 이미 노출 중인 묶음은
 *   그 사이 파티원이 무엇을 해도 바뀌지 않는다 — 조회마다 실시간으로 걸러 주지 않는다
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import PartyFlowShell from '../../../components/PartyFlowShell';
import PopcornBucket from '../../../components/PopcornBucket';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ApiError } from '../../../api/apiError';
import { ChevronRightIcon } from '../../../components/Icon';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import { path } from '../../../routes/paths';
import { mixedFlavors } from '../../../utils/party';
import type { PartyMixEntry } from '../PartyPopcornMixPage';
import type { PartyPopcorn, PartyRecommendationItem } from '../../../types/party';
import styles from './PartyRecommendationPage.module.css';

/** 목록의 포스터 크기. 시안 그대로다 */
const POSTER_WIDTH = 56;
const POSTER_HEIGHT = 80;

/** 결과 머리의 섞인 통. 5.9의 통보다 작다 — 여기서 주인공은 3편이다 */
const BUCKET_WIDTH = 132;

/** 추천을 못 받은 까닭. `null`은 까닭을 모르는 실패(네트워크 · 서버 오류)다 */
type Failure = 'gone' | 'closed' | 'unavailable' | 'empty' | null;

const FAILURE_COPY: Record<Exclude<Failure, null>, { title: string; description: string }> = {
  gone: { title: '파티를 찾을 수 없어요', description: '파티가 없어졌거나 파티원이 아니에요.' },
  closed: { title: '끝난 파티예요', description: '끝난 파티는 추천을 받을 수 없어요.' },
  unavailable: {
    title: '아직 추천을 준비하고 있어요',
    description: '파티원의 취향을 모으는 중이에요. 잠시 후 다시 추천받아 주세요.',
  },
  empty: {
    title: '지금은 함께 볼 영화를 찾지 못했어요',
    description: '잠시 후 다시 추천받아 주세요.',
  },
};

function failureOf(error: unknown): Failure {
  if (!(error instanceof ApiError)) return null;
  if (error.status === 404) return 'gone';
  if (error.code === 'PARTY_CLOSED') return 'closed';
  if (error.code === 'RECOMMENDATION_UNAVAILABLE') return 'unavailable';

  return null;
}

export default function PartyRecommendationPage() {
  const navigate = useNavigate();
  const { partyId } = useParams();
  const id = Number(partyId);

  const [items, setItems] = useState<PartyRecommendationItem[] | null>(null);
  /** 다시 받기로 더 보여줄 후보가 남았는지. 값이 없으면(옛 응답) 있는 것으로 본다 */
  const [hasNext, setHasNext] = useState(true);
  const [members, setMembers] = useState<string[]>([]);
  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [failed, setFailed] = useState(false);
  const [failure, setFailure] = useState<Failure>(null);

  useEffect(() => {
    let active = true;

    getPartyRecommendations(id)
      .then((set) => {
        if (!active) return;

        setItems(set.items);
        setHasNext(set.hasNext !== false);
        setFailure(set.items.length === 0 ? 'empty' : null);
        setFailed(set.items.length === 0);
      })
      .catch((error: unknown) => {
        if (!active) return;

        setFailure(failureOf(error));
        setFailed(true);
      });

    getParty(id)
      .then((party) => {
        if (active && party) {
          setMembers(party.members.map((member) => member.nickname));
        }
      })
      .catch(() => {
        // 파티원 이름이 없어도 추천 목록은 보여준다
      });

    getPartyPopcorn(id)
      .then((value) => {
        if (active) setPopcorn(value);
      })
      .catch(() => {
        // 통을 못 그려도 추천 목록은 보여준다
      });

    return () => {
      active = false;
    };
  }, [id]);

  if (failed) {
    const copy = failure
      ? FAILURE_COPY[failure]
      : { title: '추천을 불러오지 못했어요', description: '잠시 후 다시 시도해 주세요.' };
    // 없는 파티 · 끝난 파티는 다시 추천받아도 같다. 내 파티로 돌려보낸다
    const leave = failure === 'gone' || failure === 'closed';

    return (
      <>
        <ScreenHeader title="파티팝콘" showBack fallbackTo={path.party()} />
        <div className={styles.page}>
          <StatusView title={copy.title} description={copy.description}>
            {leave ? (
              <Button variant="text" onClick={() => void navigate(path.party())}>
                내 파티로 돌아가기
              </Button>
            ) : (
              <Button variant="text" onClick={() => void navigate(path.partyPopcornMix(id))}>
                다시 추천받기
              </Button>
            )}
          </StatusView>
        </div>
      </>
    );
  }

  if (!items) return null;

  const shownNames = popcorn ? popcorn.members.map((member) => member.user.nickname) : members;

  return (
    <PartyFlowShell
      members={shownNames}
      title={`우리에게 어울리는 ${items.length}편`}
      subtitle={`${shownNames.length}명의 취향을 한 통에 담았어요`}
    >
      {popcorn ? (
        <div className={styles.bucket}>
          <PopcornBucket
            flavors={mixedFlavors(popcorn)}
            width={BUCKET_WIDTH}
            label={`파티원 ${popcorn.members.length}명의 팝콘을 섞은 통`}
          />
        </div>
      ) : null}

      <ul className={styles.list}>
        {items.map((item) => (
          <li key={item.recommendationItemId}>
            {/* 서버 영화라 상세(2.4)로 간다. 목업이던 동안(업무 -610)에는 막아 두었다 */}
            <Link to={path.movieDetail(item.movie.movieId)} className={styles.row}>
              <Poster
                width={POSTER_WIDTH}
                height={POSTER_HEIGHT}
                title={item.movie.title}
                seed={item.movie.movieId}
                src={item.movie.posterUrl ?? undefined}
              />

              <span className={styles.title}>{item.movie.title}</span>

              <span className={styles.chevron} aria-hidden="true">
                <ChevronRightIcon />
              </span>
            </Link>
          </li>
        ))}
      </ul>

      <div className={styles.actions}>
        {hasNext ? (
          <Button
            variant="text"
            onClick={() =>
              void navigate(path.partyPopcornMix(id), {
                state: { refresh: true } satisfies PartyMixEntry,
              })
            }
          >
            다른 영화 추천받기
          </Button>
        ) : (
          <p className={styles.exhausted}>
            지금은 더 보여줄 영화가 없어요. 파티원이 영화를 평가하면 새로 추천해 드려요.
          </p>
        )}
      </div>
    </PartyFlowShell>
  );
}
