/*
 * 공개된 취향을 보여주는 묶음. 7.2-A와 7.2-B가 같은 구성을 쓴다.
 *
 * 와이어프레임이 두 화면에 같은 카드 세 장을 그린다 — 공개된 취향 키워드 · 공개된
 * 취향 필름 · 팝콘 버킷. 두 화면이 쓰므로 공통에 둔다(frontend-structure.md).
 *
 * **팝콘은 색 막대가 아니라 통으로 그린다.** 와이어프레임 7.2-A · 7.2-B는 8색 누적
 * 막대를 썼지만, 팝콘 8색은 밝은 면에서 4종이 1.01~1.39:1이라 사라진다(tokens.css).
 * 색 막대를 쓰려면 어두운 판이 필요한데 이 카드는 밝은 벽 위에 있다. 3.2 · 5.8 · 5.9가
 * 같은 이유로 3D 통을 쓰기로 했고(팀 결정 2026-09-10) 여기도 따른다.
 *
 * 필름은 가로 스크롤이다. 와이어프레임은 3개를 고정으로 그렸지만 공개 범위에 따라
 * 개수가 달라질 수 있어 넘치면 밀어서 보게 한다.
 */
import { Link } from 'react-router';
import Chip from '../Chip';
import PopcornBucket from '../PopcornBucket';
import Poster from '../Poster';
import Section from '../Section';
import { path } from '../../routes/paths';
import styles from './TasteShowcase.module.css';
import type { PublicProfileView } from '../../types/unspecified';

type Props = {
  keywords: PublicProfileView['keywords'];
  frames: PublicProfileView['frames'];
  popcorn: PublicProfileView['popcorn'];
  /** 팝콘 통 아래에 적을 총 알 수 */
  popcornCount: number;
  /**
   * 필름을 눌러 프레임 상세로 갈 수 있는지.
   *
   * 내 프로필(7.2-A)만 true다. 프레임 상세(3.1-A)는 내 감상 기록을 보여주는 화면이라
   * 다른 사람의 프레임으로는 들어갈 수 없다.
   */
  frameLinks?: boolean;
};

/** 앞의 몇 개를 채운 칩으로 둘지. 와이어프레임 7.2-A는 2개, 7.2-B는 2개를 채웠다 */
const SOLID_KEYWORD_COUNT = 2;

/** 통 폭. 카드 안에 들어가므로 3.2(196px)보다 작다 */
const BUCKET_WIDTH = 148;

export default function TasteShowcase({
  keywords,
  frames,
  popcorn,
  popcornCount,
  frameLinks = false,
}: Props) {
  /* 맛 분포대로 알을 펼친다. 한 알이 영화 한 편이다 (3.2 · 5.8과 같은 규칙) */
  const kernelFlavors = popcorn.flatMap((flavor) =>
    Array.from({ length: flavor.popcornCount }, () => flavor.flavorCode),
  );

  return (
    <>
      {keywords.length > 0 ? (
        <Section label="공개된 취향 키워드">
          <div className={styles.chips}>
            {keywords.map((keyword, index) => (
              <Chip key={keyword.code} variant={index < SOLID_KEYWORD_COUNT ? 'solid' : 'outline'}>
                {keyword.label}
              </Chip>
            ))}
          </div>
        </Section>
      ) : null}

      {frames.length > 0 ? (
        <Section label="공개된 취향 필름">
          <ul className={styles.filmTrack}>
            {frames.map((frame) => {
              const poster = (
                <Poster
                  width={92}
                  height={130}
                  radius={8}
                  src={frame.posterUrl ?? undefined}
                  title={frame.title}
                  seed={frame.movieId}
                />
              );

              return (
                <li key={frame.frameId} className={styles.filmItem}>
                  {frameLinks ? <Link to={path.frameDetail(frame.frameId)}>{poster}</Link> : poster}
                </li>
              );
            })}
          </ul>
        </Section>
      ) : null}

      <Section label="팝콘 버킷">
        <div className={styles.bucket}>
          <PopcornBucket
            kernelFlavors={kernelFlavors}
            width={BUCKET_WIDTH}
            label={`팝콘 ${popcornCount}알`}
          />
          <p className={styles.bucketCount}>팝콘 {popcornCount}알</p>
        </div>
      </Section>
    </>
  );
}
