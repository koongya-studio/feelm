/* 2.2 모바일 추천 카드. 릴 칸 높이 안에서 포스터와 정보 영역을 나눈다(업무 -906). */
import { Link } from 'react-router';
import Badge from '../../../../components/Badge';
import IconButton from '../../../../components/IconButton';
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

      <span className={styles.dismissButton}>
        <IconButton
          label={`${movie.title} 관심없음`}
          icon={<BlockIcon />}
          size={26}
          shape="rounded"
          variant="translucent"
          disabled={dismissing}
          onClick={(event) => onDismiss(event, item)}
        />
      </span>

      {isDiscovery ? (
        <span className={styles.badge}>
          <Badge tone="accent">새로운 맛</Badge>
        </span>
      ) : null}
    </div>
  );
}
