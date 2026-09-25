/* 2.2 모바일 추천 카드. 릴 칸 높이 안에서 포스터와 정보 영역을 나눈다(업무 -906). */
import { Link } from 'react-router';
import Badge from '../../../../components/Badge';
import OttBadge from '../../../../components/OttBadge';
import { BlockIcon, ChevronRightIcon } from '../../../../components/Icon';
import Poster from '../../../../components/Poster';
import PosterCue from '../../../../components/PosterCue';
import { path } from '../../../../routes/paths';
import { ottMovieUrl } from '../../../../utils/ott';
import styles from './RecommendationCard.module.css';
import type { MouseEvent } from 'react';
import type { Ott } from '../../../../types/ott';
import type { RecommendationItem } from '../../../../types/recommendation';

type Props = {
  item: RecommendationItem;
  otts: Ott[];
  /** 관심없음을 처리하는 중이면 버튼을 막는다 */
  dismissing: boolean;
  onDismiss: (event: MouseEvent<HTMLButtonElement>, item: RecommendationItem) => void;
};

export default function RecommendationCard({ item, otts, dismissing, onDismiss }: Props) {
  const { movie, reason, type } = item;
  const isDiscovery = type === 'DISCOVERY';

  return (
    <div className={styles.card}>
      <Link
        to={path.movieDetail(movie.movieId)}
        state={{ recommendationItemId: item.recommendationItemId }}
        className={styles.posterLink}
        data-guide-target="recommendation"
        aria-label={`${movie.title} 상세 보기`}
      />
      <div className={styles.cardContent}>
        <span className={styles.posterArea}>
          <PosterCue cue={false}>
            <Poster
              width={156}
              height={220}
              radius={0}
              src={movie.posterUrl ?? undefined}
              title=""
              seed={movie.movieId}
            />
          </PosterCue>
        </span>

        <span className={styles.info}>
          <span className={styles.infoTop}>
            <strong className={styles.title}>{movie.title}</strong>
            <span className={styles.ottList}>
              {movie.ottProviders.map((code) => {
                const ott = otts.find((candidate) => candidate.code === code);
                const destination = ottMovieUrl(code, movie.title);

                return (
                  <span key={code}>
                    {destination ? (
                      <a
                        href={destination}
                        target="_blank"
                        rel="noopener noreferrer"
                        className={styles.ottLink}
                        aria-label={`${ott?.name ?? code}에서 ${movie.title} 보기 (새 창)`}
                      >
                        <OttBadge code={code} name={ott?.name ?? code} size={22} labelled={false} />
                      </a>
                    ) : (
                      <OttBadge code={code} name={ott?.name ?? code} size={22} />
                    )}
                  </span>
                );
              })}
            </span>
          </span>
          <span className={styles.reason}>
            <span className={styles.reasonText}>{reason.summary}</span>
            <span className={styles.reasonCue} aria-hidden="true">
              <ChevronRightIcon />
            </span>
          </span>
        </span>
      </div>

      {/*
       * 관심없음. 아이콘에 글자를 붙인 작은 알약이다(업무 -873). 2.4 영화 상세의 관심없음과 같은
       * 어두운 유리 면이라, 밝은 유리 사각형처럼 어두운 필름 틀 위에서 혼자 떠 보이지 않는다.
       */}
      <button
        type="button"
        className={styles.dismissButton}
        aria-label={`${movie.title} 관심없음`}
        disabled={dismissing}
        onClick={(event) => onDismiss(event, item)}
      >
        <BlockIcon />
        <span aria-hidden="true">관심없음</span>
      </button>

      {isDiscovery ? (
        <span className={styles.badge}>
          <Badge tone="accent">새로운 맛</Badge>
        </span>
      ) : null}
    </div>
  );
}
