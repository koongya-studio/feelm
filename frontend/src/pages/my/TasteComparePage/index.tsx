/*
 * 7.2-C 사용자 취향 비교
 *
 * 와이어프레임에서 가져온 것 — 두 사용자와 전체 일치율, 겹치는 팝콘 맛, 항목별 비교
 * 4줄(장르 · 국가 · 시대 · 감독), `같이 볼 영화 추천받기 (파티)`.
 *
 * 배치는 레퍼런스 두 장을 섞는다.
 * - `Dormy App.jpg` — 핵심 비교 결과를 카드 크기로 구분한다. 일치율 히어로가 가장
 *   크고 어둡고, 나머지는 밝은 카드다
 * - `Apple Fitness Plus` — 같이 볼 영화를 둥근 썸네일 가로 스크롤로 둔다
 *
 * 와이어프레임은 전체 일치율도 가로 진행 바로 그렸다. **원형 링으로 바꿨다** —
 * 항목별 비교가 이미 가로 바 4줄이라 다섯 줄이 같은 모양이면 무엇이 대표 수치인지
 * 보이지 않는다(MatchRing 주석).
 *
 * 문구는 승패로 읽히지 않게 쓴다(레퍼런스 지시 4.5). `누가 더`가 아니라 `무엇을 함께`다.
 *
 * `같이 볼 영화 추천받기`는 **5.2 인원 설정**으로 간다(팀 결정 2026-09-11).
 * 이동표에 목적지가 없던 화살표다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Avatar from '../../../components/Avatar';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import MatchRing from '../../../components/MatchRing';
import MeterRow from '../../../components/MeterRow';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import Section from '../../../components/Section';
import StatusView from '../../../components/StatusView';
import { getMockPopcornFlavors } from '../../../api/taste';
import { getTasteCompare } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './TasteComparePage.module.css';
import type { PopcornFlavor } from '../../../types/taste';
import type { TasteCompareView } from '../../../types/unspecified';

export default function TasteComparePage() {
  const { userId } = useParams();
  const navigate = useNavigate();

  const id = Number(userId);

  const [compare, setCompare] = useState<TasteCompareView | null>(null);
  const [flavors, setFlavors] = useState<PopcornFlavor[]>([]);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getTasteCompare(id)
      .then((value) => {
        if (active) {
          setCompare(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    /*
     * 이 화면의 맛 집계는 목업이라 기준정보도 목업을 쓴다. 실제 API-044의 맛 코드와
     * 목업 집계의 코드가 다르기 때문이다(api/taste.ts `getMockPopcornFlavors`).
     */
    getMockPopcornFlavors()
      .then((value) => {
        if (active) {
          setFlavors(value);
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  if (failed) {
    return (
      <>
        <ScreenHeader title="취향 비교" showBack />

        <StatusView
          title="취향 비교 결과를 불러오지 못했어요"
          description="주소가 잘못되었거나 잠시 문제가 생겼어요"
        >
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  if (!compare) {
    return <ScreenHeader title="취향 비교" showBack />;
  }

  const flavorByCode = new Map(flavors.map((flavor) => [flavor.code, flavor]));

  return (
    <>
      <ScreenHeader
        title="취향 비교"
        subtitle={`${compare.me.nickname} × ${compare.other.nickname}`}
        showBack
      />

      <div className={styles.page}>
        {/* 화면에서 유일한 어두운 블록. 대표 수치가 여기 있다 */}
        <section className={styles.hero}>
          <div className={styles.faces}>
            <Avatar size={56} src={compare.me.avatarUrl} nickname={compare.me.nickname} />
            <MatchRing rate={compare.matchRate} />
            <Avatar size={56} src={compare.other.avatarUrl} nickname={compare.other.nickname} />
          </div>

          <p className={styles.heroCaption}>두 사람이 이만큼 같은 걸 좋아해요</p>
        </section>

        {compare.sharedFlavorCodes.length > 0 ? (
          <Section label="함께 좋아하는 맛">
            <div className={styles.chips}>
              {compare.sharedFlavorCodes.map((code) => (
                <Chip key={code} variant="solid">
                  {flavorByCode.get(code)?.displayName ?? code}
                </Chip>
              ))}
            </div>
          </Section>
        ) : null}

        <Section label="항목별로 보면">
          <div className={styles.meters}>
            {compare.items.map((item, index) => (
              <MeterRow key={item.label} label={item.label} rate={item.rate} delayIndex={index} />
            ))}
          </div>
        </Section>

        {compare.recommendations.length > 0 ? (
          <Section label="둘 다 좋아할 만한 영화">
            <ul className={styles.posterTrack}>
              {compare.recommendations.map((movie) => (
                <li key={movie.movieId} className={styles.posterItem}>
                  <Poster
                    width={96}
                    height={136}
                    radius={12}
                    src={movie.posterUrl ?? undefined}
                    title={movie.title}
                    seed={movie.movieId}
                  />
                </li>
              ))}
            </ul>
          </Section>
        ) : null}

        {/*
         * 화면의 주 동작. 파티 인원 설정(5.2)으로 간다.
         * 와이어프레임 문구의 `(파티)`는 뺐다 — 도착 화면이 파티 만들기라 괄호로
         * 설명하지 않아도 이어진다.
         */}
        <Button onClick={() => void navigate(path.partyNew())}>같이 볼 영화 추천받기</Button>
      </div>
    </>
  );
}
