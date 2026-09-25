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

/**
 * OTT 로고 한 변. 어두운 정보 영역 위에서 22px은 바탕이 어두운 로고(넷플릭스 · 디즈니+)가
 * 배경에 묻혀 한 단계 키웠다(업무 -917).
 */
const OTT_BADGE_SIZE = 26;

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
  /*
   * 제목 옆의 연도 · 대표 장르(업무 -874). 포스터 그림만으로 영화를 알아봐야 했다. 줄을 새로
   * 두면 포스터가 한 번 더 작아지므로 제목과 같은 줄에 흐리게 붙인다. 장르는 첫 하나만 적는다.
   */
  const meta = [movie.releaseYear ? String(movie.releaseYear) : null, movie.genres[0] ?? null]
    .filter(Boolean)
    .join(' · ');

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

        {/*
         * 정보 영역은 위에서부터 제목 · 추천 이유 · 볼 수 있는 곳이다(업무 -917). OTT 로고가
         * 제목 옆에 있을 때는 OTT가 많을수록 제목이 잘렸다(5개면 일곱 글자). 로고 줄을 따로
         * 두어 제목과 이유가 온전히 보이고, 로고는 개수와 상관없이 늘 같은 자리에 선다 —
         * 데스크톱 카드(`MovieBriefCard`)와 같은 순서다.
         *
         * 포스터 위에 얹는 안은 포스터 아래쪽에 모인 부제 · 크레딧을 가리고, 상세로 가는
         * 포스터 면 안에 OTT 링크가 겹쳐 쓰지 않았다(사용자 결정 2026-09-26).
         */}
        <span className={styles.info}>
          {/*
           * `›`는 카드 전체가 영화 상세로 간다는 단서다(업무 -697). 제목 줄 끝에 둔다(업무 -874,
           * 사용자 결정 2026-09-26) — 추천 이유 줄 끝에 있을 때는 "이유를 더 보여 준다"로도 읽혔다.
           * 파티 목록 카드도 제목 줄 끝에 `›`를 둔다.
           */}
          <span className={styles.titleRow}>
            <strong className={styles.title}>{movie.title}</strong>
            {meta ? <span className={styles.meta}>{meta}</span> : null}
            <span className={styles.detailCue} aria-hidden="true">
              <ChevronRightIcon />
            </span>
          </span>
          <span className={styles.reason}>
            <span className={styles.reasonText}>{reason.summary}</span>
          </span>
          {movie.ottProviders.length > 0 ? (
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
                        <OttBadge
                          code={code}
                          name={ott?.name ?? code}
                          size={OTT_BADGE_SIZE}
                          labelled={false}
                        />
                      </a>
                    ) : (
                      <OttBadge code={code} name={ott?.name ?? code} size={OTT_BADGE_SIZE} />
                    )}
                  </span>
                );
              })}
            </span>
          ) : (
            <span className={styles.ottEmpty}>제공 중인 OTT가 없어요</span>
          )}
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
