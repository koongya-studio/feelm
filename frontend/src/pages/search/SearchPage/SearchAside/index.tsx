/*
 * 4.2 검색 — 고른 영화의 우측 보조 패널 (업무 S15P21E106-784).
 *
 * 2026-09-21 PC 시안의 오른쪽 단이다. 카드 두 장이다.
 *
 * - **고른 영화** — 포스터 · 연도 · 장르 · 평점 · OTT · `영화 상세 보기` · 줄거리 ·
 *   감독 · 주요 출연진 · 연관 키워드
 * - **유사한 작품** — API-031
 *
 * 값은 모두 영화 상세(API-030)와 유사 영화(API-031)에서 온다. **검색 결과 항목(API-028)만으로는
 * 채울 수 없다** — 줄거리 · 감독 · 출연진이 거기에 없다. 그래서 고를 때마다 두 계약을 부르고,
 * 받는 동안에는 검색 결과가 이미 준 것(제목 · 포스터 · 연도 · 장르 · 평점 · OTT 코드)으로
 * 패널을 세운다. 그러면 고른 순간 패널이 비었다가 채워지지 않는다.
 *
 * **시안의 `연관 키워드`는 계약에 없다.** 사회 계층 · 블랙 코미디 · 칸 영화제 같은 값은
 * 어느 응답에도 없다. 같은 자리에 API-030이 주는 **장르와 제작 국가**를 칩으로 둔다
 * (사용자 결정 2026-09-22).
 *
 * `[!]` 감독 · 출연진은 **이름만** 적는다. 시안은 얼굴과 `›`를 두지만 인물 화면이 이 앱에
 * 없고 계약의 `profileUrl`도 사람에 따라 비어 있다. 누를 수 없는 `›`를 두면 눌리는 것처럼
 * 보인다.
 *
 * 평점은 계약의 `averageRating` 그대로 5점 만점이다(FR-19). 시안의 `8.6`은 10점처럼 보이는
 * 수이지만 이 앱의 평가는 0.5~5.0이고 영화 상세도 5점으로 적는다(업무 -783과 같은 결정).
 */
import { Link } from 'react-router';
import AsidePanel from '../../../../components/AsidePanel';
import OttBadge from '../../../../components/OttBadge';
import Poster from '../../../../components/Poster';
import { PlayIcon } from '../../../../components/Icon';
import { path } from '../../../../routes/paths';
import styles from './SearchAside.module.css';
import type { MovieDetail, SimilarMovie } from '../../../../types/movie';
import type { Ott } from '../../../../types/ott';
import type { MovieSearchItem } from '../../../../types/search';

/** 패널 머리의 포스터 크기(px). 시안의 크기다 */
const POSTER_WIDTH = 96;
/** 유사한 작품 포스터 크기(px) */
const SIMILAR_POSTER_WIDTH = 72;
/** OTT 배지 한 변(px) */
const OTT_SIZE = 30;
/** 주요 출연진 수. 시안이 넷이다 */
const CAST_COUNT = 4;
/** 유사한 작품 수. 시안이 넷이다 */
const SIMILAR_COUNT = 4;

type Props = {
  /** 고른 영화의 검색 결과 항목. 상세를 받기 전에도 이만큼은 그린다 */
  item: MovieSearchItem;
  /** 영화 상세(API-030). 아직 못 받았거나 실패했으면 `null` */
  detail: MovieDetail | null;
  /** 유사 영화(API-031). 못 받았으면 빈 배열이고 그 카드를 그리지 않는다 */
  similar: SimilarMovie[];
  otts: Ott[];
  onClose: () => void;
  /** 상세로 가기 직전. 결과와 스크롤을 적어 두는 자리다 */
  onLeaveToDetail: () => void;
};

/** `2019 · 드라마`. 없는 값은 자리를 비운다 */
function metaLine(year: number | null, genres: string[]): string {
  return [year ? String(year) : null, genres.length > 0 ? genres.join(' · ') : null]
    .filter(Boolean)
    .join(' · ');
}

export default function SearchAside({
  item,
  detail,
  similar,
  otts,
  onClose,
  onLeaveToDetail,
}: Props) {
  const detailPath = path.movieDetail(item.movieId);
  const genres = detail ? detail.genres.map((genre) => genre.name) : item.genres;
  const year = detail?.releaseDate ? Number(detail.releaseDate.slice(0, 4)) : item.releaseYear;
  const rating = detail?.averageRating ?? item.averageRating;
  /*
   * OTT 배지. 상세를 받았으면 그쪽(`ottProviders[].name`)을 쓰고, 받기 전에는 검색 결과의
   * 코드와 기준정보(API-025)를 맞춘다. 이름을 못 찾으면 배지가 코드로 떨어진다.
   */
  const ottBadges = detail
    ? detail.ottProviders.map((ott) => ({ code: ott.code, name: ott.name }))
    : item.ottProviders.map((code) => ({
        code,
        name: otts.find((ott) => ott.code === code)?.name ?? code,
      }));
  /* 시안의 `연관 키워드` 자리. 계약에 키워드가 없어 장르와 제작 국가를 둔다(머리말) */
  const keywords = detail ? [...genres, ...detail.countries.map((country) => country.name)] : [];

  return (
    <>
      <AsidePanel title={item.title} onClose={onClose} closeLabel="고른 영화 닫기">
        <div className={styles.body}>
          <div className={styles.head}>
            <Poster
              width={POSTER_WIDTH}
              height={POSTER_WIDTH * 1.5}
              radius={10}
              src={item.posterUrl ?? undefined}
              title={item.title}
              seed={item.movieId}
            />

            <div className={styles.headText}>
              <p className={styles.title}>{item.title}</p>
              <p className={styles.meta}>{metaLine(year, genres)}</p>

              {/*
               * 평가가 하나도 없으면 계약이 `averageRating: 0`을 준다. 그때는 별을 그리지
               * 않는다 — `★ 0.0`은 모두가 싫어한 영화라는 뜻으로 읽힌다(업무 -783 · -771)
               */}
              {rating > 0 ? (
                <p className={styles.rating}>
                  <span className={styles.star} aria-hidden="true">
                    ★
                  </span>
                  {rating.toFixed(1)}
                </p>
              ) : null}

              {ottBadges.length > 0 ? (
                <ul className={styles.otts}>
                  {ottBadges.map((ott) => (
                    <li key={ott.code}>
                      <OttBadge code={ott.code} name={ott.name} size={OTT_SIZE} />
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          </div>

          {/* 2.4 영화 상세로 간다. 결과와 스크롤을 적어 두어 뒤로 오면 되살아난다 */}
          <Link to={detailPath} className={styles.detailButton} onClick={onLeaveToDetail}>
            <PlayIcon />
            영화 상세 보기
          </Link>

          {detail?.synopsis ? <p className={styles.synopsis}>{detail.synopsis}</p> : null}

          {detail && detail.directors.length > 0 ? (
            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>감독</h3>
              <p className={styles.people}>
                {detail.directors.map((person) => person.name).join(' · ')}
              </p>
            </section>
          ) : null}

          {detail && detail.cast.length > 0 ? (
            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>주요 출연진</h3>
              <ul className={styles.chips}>
                {detail.cast.slice(0, CAST_COUNT).map((person) => (
                  <li key={person.personId} className={styles.chip}>
                    {person.name}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {keywords.length > 0 ? (
            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>장르 · 제작 국가</h3>
              <ul className={styles.chips}>
                {keywords.map((keyword) => (
                  <li key={keyword} className={styles.chip}>
                    {keyword}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
        </div>
      </AsidePanel>

      {similar.length > 0 ? (
        <AsidePanel
          title="유사한 작품"
          to={detailPath}
          linkLabel={`${item.title}의 유사한 작품 더 보기`}
        >
          <ul className={styles.similar}>
            {similar.slice(0, SIMILAR_COUNT).map((movie) => (
              <li key={movie.movieId}>
                <Link
                  to={path.movieDetail(movie.movieId)}
                  className={styles.similarCell}
                  onClick={onLeaveToDetail}
                >
                  <Poster
                    width={SIMILAR_POSTER_WIDTH}
                    height={SIMILAR_POSTER_WIDTH * 1.5}
                    radius={8}
                    src={movie.posterUrl ?? undefined}
                    title={movie.title}
                    seed={movie.movieId}
                  />
                  <span className={styles.similarTitle}>{movie.title}</span>
                </Link>
              </li>
            ))}
          </ul>
        </AsidePanel>
      ) : null}
    </>
  );
}
