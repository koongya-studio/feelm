/*
 * 2.4-A 감상 확인
 *
 * OTT 링크 클릭 2일 후 실제 감상 여부를 확인한다(FR-18). 정상 진입 경로는 알림(8.1 · 8.2)
 * 이지만 그 화면들이 범위 밖이라 URL로 직접 접근한다(frontend-structure.md 3.4).
 * 2.4에서 OTT 링크를 누르면 감상 기록이 만들어지고 이 화면으로 이어진다.
 *
 * 선택지는 계약에 맞춰 두 개다(screen-spec.md 8.1 #5). API-035는 `watched` 하나만
 * 받으므로 `봤어요`(true → `WATCHED_UNRATED`)와 `보지 않을래요`(false → `EXPIRED`)만
 * 구현한다. 와이어프레임의 `아직 못 봤어요`는 대응 값이 없다. 응답하지 않고 화면을
 * 벗어나면 상태가 `LINK_CLICKED`로 남고 7일 뒤 서버가 `EXPIRED`로 바꾸므로, 그 의도는
 * 기본 동작이 대신한다. 하단 안내 문구도 두 선택지에 맞춰 줄였다.
 *
 * `[?]` 어느 영화의 기록인지는 API-034 감상 기록 목록으로 조회해야 알 수 있다. 이번
 * 단계는 목업이라 화면에 넘겨받은 값이 없으면 첫 추천 영화를 보여준다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Poster from '../../../components/Poster';
import { confirmWatch } from '../../../api/watch';
import { getMovieDetail } from '../../../api/movies';
import { path } from '../../../routes/paths';
import styles from './WatchConfirmPage.module.css';
import type { MovieDetail } from '../../../types/movie';

/** 목업 기본 영화. 감상 기록에서 영화를 찾는 조회는 API-034 범위다 */
const FALLBACK_MOVIE_ID = 101;

export default function WatchConfirmPage() {
  const { watchRecordId } = useParams();
  const navigate = useNavigate();

  const [movie, setMovie] = useState<MovieDetail | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;

    void getMovieDetail(FALLBACK_MOVIE_ID).then((detail) => {
      if (active) {
        setMovie(detail);
      }
    });

    return () => {
      active = false;
    };
  }, []);

  async function respond(watched: boolean) {
    setSubmitting(true);
    await confirmWatch(Number(watchRecordId), watched);

    if (watched) {
      void navigate(path.movieRating(movie?.movieId ?? FALLBACK_MOVIE_ID));

      return;
    }

    // 보지 않을래요 → 감상 대기 종료. 홈으로 돌아간다
    void navigate(path.home());
  }

  if (!movie) {
    return null;
  }

  const ottName = movie.ottProviders[0]?.name ?? 'OTT';

  return (
    <div className={styles.page}>
      <Poster width={120} height={168} radius={10} src={movie.posterUrl ?? undefined} title="" />

      <div className={styles.movie}>
        <p className={styles.title}>{movie.title}</p>
        <p className={styles.note}>{ottName}로 이동한 지 2일이 지났어요</p>
      </div>

      <h1 className={styles.question}>이 영화, 잘 보셨나요?</h1>

      <div className={styles.actions}>
        <Button disabled={submitting} onClick={() => void respond(true)}>
          봤어요
        </Button>

        <Button variant="text" disabled={submitting} onClick={() => void respond(false)}>
          보지 않을래요
        </Button>
      </div>

      <p className={styles.hint}>
        봤어요 → 영화 평가 · 보지 않을래요 → 감상 대기 종료
        <br />
        답하지 않으면 7일 뒤 자동으로 감상 대기가 끝나요
      </p>
    </div>
  );
}
