/*
 * 5.5 추천 완성 (파티팝콘 흐름 3/3)
 *
 * 이동표
 * 5.9 → 5.5 팝콘 섞기 완료
 * 5.5 ⇢ 2.4 추천 영화 선택
 * 5.5 ⇢ 5.4 다른 영화 추천받기
 *
 * **사용자 시안 2026-09-17(업무 -462)에서 흐름의 마지막 단계가 됐다.** 들어오는 곳이
 * 5.4에서 5.9로 바뀌었고, 섞인 통이 결과의 머리에 놓인다 — 세 사람의 팝콘을 한 통에
 * 담은 결과가 이 3편이라는 말을 그림이 먼저 한다.
 *
 * `다른 영화 추천받기`가 5.4로 되돌아가는 것은 이동표가 정한 것이다. 결과만 갈아
 * 끼우지 않고 모으기 · 섞기 장면을 다시 보여준다.
 *
 * **계약에 없던 값을 모두 걷어냈다**(사용자 결정 2026-09-17).
 * - 상단 공통 키워드 칩. 대응 필드가 API-058에 없어 `getPartyMix`(CR-17) 자리표시자를
 *   실제 파티원에게 붙여 쓰고 있었다
 * - 카드마다 붙던 `일치 94%`. 계약이 주는 것은 `reason.memberFit[]` — **사용자별**
 *   점수이고, 화면이 쓰던 하나의 값은 그 평균이라 계약에 없었다
 * - `왜 같이 추천됐나요?` 문장. API-058 `reason`은 카드별 `summary` 하나뿐이다
 *
 * 시안에 이 셋이 하나도 없어 화면에서 빼고 자리표시자 호출도 지웠다. 계약이 정해지면
 * 그때 넣는다. 카드의 한 줄 이유는 `reason.summary` 그대로이므로 남는다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import PartyFlowShell from '../../../components/PartyFlowShell';
import PopcornBucket from '../../../components/PopcornBucket';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ChevronRightIcon } from '../../../components/Icon';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import { path } from '../../../routes/paths';
import { mixedFlavors } from '../../../utils/party';
import type { PartyPopcorn, PartyRecommendationItem } from '../../../types/party';
import styles from './PartyRecommendationPage.module.css';

/** 목록의 포스터 크기. 시안은 제목과 이유 두 줄에 맞춘 정사각에 가까운 판이다 */
const POSTER_WIDTH = 56;
const POSTER_HEIGHT = 80;

/** 결과 머리의 섞인 통. 5.9의 통보다 작다 — 여기서 주인공은 3편이다 */
const BUCKET_WIDTH = 132;

export default function PartyRecommendationPage() {
  const navigate = useNavigate();
  const { partyId } = useParams();
  const id = Number(partyId);

  const [items, setItems] = useState<PartyRecommendationItem[] | null>(null);
  const [members, setMembers] = useState<string[]>([]);
  const [popcorn, setPopcorn] = useState<PartyPopcorn | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getPartyRecommendations(id)
      .then((set) => {
        if (!active) return;

        setItems(set.items);
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
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
    return (
      <>
        <ScreenHeader title="파티팝콘" showBack />
        <div className={styles.page}>
          <StatusView title="추천을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요.">
            <Button variant="text" onClick={() => void navigate(path.partyAnalyzing(id))}>
              다시 추천받기
            </Button>
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
            <button
              type="button"
              className={styles.row}
              onClick={() => void navigate(path.movieDetail(item.movie.movieId))}
            >
              <Poster
                width={POSTER_WIDTH}
                height={POSTER_HEIGHT}
                title={item.movie.title}
                seed={item.movie.movieId}
                src={item.movie.posterUrl ?? undefined}
              />

              <span className={styles.body}>
                <span className={styles.title}>{item.movie.title}</span>
                <span className={styles.summary}>{item.reason.summary}</span>
              </span>

              <span className={styles.chevron} aria-hidden="true">
                <ChevronRightIcon />
              </span>
            </button>
          </li>
        ))}
      </ul>

      <div className={styles.actions}>
        <Button variant="text" onClick={() => void navigate(path.partyAnalyzing(id))}>
          다른 영화 추천받기
        </Button>
      </div>
    </PartyFlowShell>
  );
}
