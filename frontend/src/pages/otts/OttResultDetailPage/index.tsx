/*
 * EXT-04 OTT 추천 상세
 *
 * 이동표: EXT-03 → EXT-04 · EXT-04 ⇢ EXT-03 다른 OTT와 비교하기 · EXT-04 ⇢ 2.2 이 OTT 작품을 더 추천받기
 *
 * 요소는 와이어프레임에서 **일치도 링 · `잘 맞는 이유` · `취향 항목별 일치도`가 빠졌다**
 * (업무 -563 · 지시서 CR-28). 셋 다 산출 규칙이 없고, 계약은 Redis 점수를 유사도나 취향 일치
 * 확률로 응답하지 않기로 정해 두었다. 남는 것은 계약이 실제로 주는 값이다 — 좋아할 만한
 * 미감상 영화 편수(`matchCount`)와 대표 영화 세 편(`topMovies`).
 *
 * `이 OTT에서 좋아할 만한 영화`, `다른 OTT와 비교하기`, `이 OTT 작품을 더 추천받기`는 그대로다.
 *
 * - 어두운 블록은 히어로 하나다. 링이 있던 자리에 편수를 크게 둔다
 * - `다른 OTT와 비교하기`는 뒤로 가기가 아니라 EXT-03으로 **간다.** 결과에서 오지 않고 바로
 *   들어왔을 때도 비교 화면에 닿아야 한다
 * - 계약에 단건 조회가 없어 목록에서 찾는다(`api/ottRecommendations.ts`). 없는 `ottId`는 404다
 *
 * `[?]` 좋아할 만한 영화를 누르면 2.4로 간다. 이동표에 화살표가 없지만 계약이 `movieId`를 주고,
 *       포스터를 눌러도 아무 일이 없는 것이 더 이상하다. 목업이던 동안에는 목업 영화 번호로
 *       실제 상세가 열려 막아 두었는데(업무 -610), 이제 서버가 준 번호라 그대로 연다
 * `[?]` `이 OTT 작품을 더 추천받기`는 2.2로 가지만 2.2가 OTT로 거르지 않는다(API-045에 조건이 없다)
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import OttBadge from '../../../components/OttBadge';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import Section from '../../../components/Section';
import StatusView from '../../../components/StatusView';
import { getOttRecommendation } from '../../../api/ottRecommendations';
import { getMyProfile } from '../../../api/users';
import { ottFailureView } from '../failure';
import { path } from '../../../routes/paths';
import styles from './OttResultDetailPage.module.css';
import type { OttRecommendationItem } from '../../../types/ott';
import type { MyProfile } from '../../../types/user';

export default function OttResultDetailPage() {
  const navigate = useNavigate();
  const { ottId } = useParams();
  const id = Number(ottId);

  const [item, setItem] = useState<OttRecommendationItem | null>(null);
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getOttRecommendation(id)
      .then((value) => {
        if (!active) return;

        setItem(value);
        setFailed(false);
      })
      .catch((error: unknown) => {
        if (!active) return;

        setFailure(error);
        setFailed(true);
      });

    getMyProfile()
      .then((value) => {
        if (active) setProfile(value);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  const header = <ScreenHeader title="OTT 추천 상세" showBack fallbackTo={path.ottResult()} />;

  if (failed) {
    const view = ottFailureView(failure, '추천 정보를 불러오지 못했어요');

    return (
      <>
        {header}
        <StatusView title={view.title} description={view.description}>
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            {view.retryLabel}
          </Button>
        </StatusView>
      </>
    );
  }

  if (!item) {
    return header;
  }

  const personalized = (profile?.stats.ratingCount ?? 0) > 0;
  const kZero = profile?.stats.ratingCount === 0;

  return (
    <>
      {header}

      <div className={styles.page}>
        {/* 화면에서 유일한 어두운 블록 */}
        <section className={styles.hero}>
          <OttBadge
            code={item.ott.code}
            name={item.ott.name}
            size={40}
            radius={11}
            labelled={false}
          />
          <h1 className={styles.heroTitle}>{item.ott.name}</h1>
          <p className={styles.heroMatch}>{item.matchCount}편</p>
          <p className={styles.heroMeta}>
            {personalized ? '좋아할 만한' : '지금 볼 수 있는'} 미감상 영화 ·{' '}
            {item.subscribed ? '구독 중' : '미구독'}
          </p>
        </section>

        {item.topMovies.length > 0 ? (
          <Section
            label={personalized ? '이 OTT에서 좋아할 만한 영화' : '이 OTT에서 볼 수 있는 영화'}
          >
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

        <p className={styles.criteria}>
          {personalized
            ? '평가한 영화와 비슷한 작품 중 아직 보지 않은 영화가 이 OTT에 몇 편 있는지 센 값이에요.'
            : kZero
              ? '아직 평가한 영화가 없어 지금 볼 수 있는 작품을 보여드려요. 영화를 평가하면 내 취향에 맞춘 결과로 바뀌어요.'
              : '현재 후보 중 이 OTT에서 볼 수 있는 영화가 몇 편인지 센 값이에요.'}{' '}
          취향 일치 확률이나 인기순이 아니에요.
        </p>

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
