/*
 * EXT-04 OTT 추천 상세
 *
 * 이동표: EXT-03 → EXT-04 · EXT-04 ⇢ EXT-03 다른 OTT와 비교하기 · EXT-04 ⇢ 2.2 이 OTT 작품을 더 추천받기
 *
 * 요소는 와이어프레임 그대로다 — `넷플릭스 · 취향 일치 92%`, `잘 맞는 이유`, `취향 항목별 일치도`
 * (장르 · 감독 · 배우 · 캐릭터 취향), `이 OTT에서 좋아할 만한 영화`, `다른 OTT와 비교하기` ·
 * `이 OTT 작품을 더 추천받기`.
 *
 * 영화 목록만 계약(API-068 `topMovies`)에 있다. 나머지는 와이어프레임대로다(팀 결정 2026-09-13).
 *
 * - 일치도는 7.2-C와 같은 원형 링으로 강조한다. 링이 이 화면의 어두운 블록 안에 있다
 * - `다른 OTT와 비교하기`는 뒤로 가기가 아니라 EXT-03으로 **간다.** 결과에서 오지 않고 바로
 *   들어왔을 때도 비교 화면에 닿아야 한다
 *
 * `[?]` 좋아할 만한 영화를 누르면 2.4로 간다. 이동표에 화살표가 없지만 계약이 `movieId`를 주고,
 *       포스터를 눌러도 아무 일이 없는 것이 더 이상하다
 * `[?]` `이 OTT 작품을 더 추천받기`는 2.2로 가지만 2.2가 OTT로 거르지 않는다(API-045에 조건이 없다)
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import MatchRing from '../../../components/MatchRing';
import MeterRow from '../../../components/MeterRow';
import OttBadge from '../../../components/OttBadge';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import Section from '../../../components/Section';
import StatusView from '../../../components/StatusView';
import { getOttRecommendation } from '../../../api/ottRecommendations';
import { path } from '../../../routes/paths';
import { ottBadgeLetter } from '../../../utils/ott';
import styles from './OttResultDetailPage.module.css';
import type { OttRecommendationItemView } from '../../../types/unspecified';

export default function OttResultDetailPage() {
  const navigate = useNavigate();
  const { ottId } = useParams();
  const id = Number(ottId);

  const [item, setItem] = useState<OttRecommendationItemView | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getOttRecommendation(id)
      .then((value) => {
        if (active) {
          setItem(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  if (failed) {
    return (
      <>
        <ScreenHeader title="OTT 추천 상세" showBack />
        <StatusView
          title="추천 정보를 불러오지 못했어요"
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

  if (!item) {
    return <ScreenHeader title="OTT 추천 상세" showBack />;
  }

  return (
    <>
      <ScreenHeader title="OTT 추천 상세" showBack />

      <div className={styles.page}>
        {/* 화면에서 유일한 어두운 블록 */}
        <section className={styles.hero}>
          <OttBadge
            letter={ottBadgeLetter(item.ott.code)}
            name={item.ott.name}
            size={40}
            radius={11}
            labelled={false}
          />
          <h1 className={styles.heroTitle}>
            {item.ott.name} · 취향 일치 {item.matchRate}%
          </h1>
          <MatchRing rate={item.matchRate} size={112} />
        </section>

        <Section label="잘 맞는 이유">
          <ul className={styles.reasons}>
            {item.reasons.map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
        </Section>

        <Section label="취향 항목별 일치도">
          <div className={styles.meters}>
            {item.itemRates.map((rate, index) => (
              <MeterRow key={rate.label} label={rate.label} rate={rate.rate} delayIndex={index} />
            ))}
          </div>
        </Section>

        {item.topMovies.length > 0 ? (
          <Section label="이 OTT에서 좋아할 만한 영화">
            <ul className={styles.movies}>
              {item.topMovies.map((movie) => (
                <li key={movie.movieId}>
                  <Link to={path.movieDetail(movie.movieId)} className={styles.movie}>
                    <Poster
                      width={96}
                      height={136}
                      radius={8}
                      src={movie.posterUrl ?? undefined}
                      title={movie.title}
                      seed={movie.movieId}
                    />
                    <span className={styles.movieTitle}>{movie.title}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </Section>
        ) : null}

        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => void navigate(path.ottResult())}>
            다른 OTT와 비교하기
          </Button>
          <Button onClick={() => void navigate(path.recommendations())}>
            이 OTT 작품을 더 추천받기
          </Button>
        </div>
      </div>
    </>
  );
}
