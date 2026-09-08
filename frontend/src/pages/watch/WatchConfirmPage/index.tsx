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
 * 어느 영화의 어느 OTT 기록인지는 감상 기록 목록(API-034)에서 읽는다. 2.4에서 OTT
 * 링크를 누를 때 남은 기록을 그대로 찾으므로, 실제로 누른 OTT가 문구에 나온다
 * (업무 -263). URL로 직접 들어와 기록이 없으면 첫 추천 영화를 보여준다.
 *
 * 계약에 단건 조회 API는 없고 목록만 있어(API-034) 목록에서 번호로 찾는다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Poster from '../../../components/Poster';
import { confirmWatch, getWatchRecords } from '../../../api/watch';
import { getMovieDetail } from '../../../api/movies';
import { getOtts } from '../../../api/otts';
import { path } from '../../../routes/paths';
import { directionParticle } from '../../../utils/korean';
import styles from './WatchConfirmPage.module.css';

/** 기록을 찾지 못했을 때 보여줄 영화 (URL 직접 접근) */
const FALLBACK_MOVIE_ID = 101;

/** 이 화면이 표시하는 값 */
type ConfirmView = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  /** 실제로 이동한 OTT의 표시명. 표시명은 계약상 API-025에만 있다 */
  ottName: string;
};

async function loadConfirmView(watchRecordId: number): Promise<ConfirmView> {
  const [records, otts] = await Promise.all([getWatchRecords(), getOtts()]);
  const record = records.items.find((item) => item.watchRecordId === watchRecordId);

  if (record) {
    return {
      movieId: record.movie.movieId,
      title: record.movie.title,
      posterUrl: record.movie.posterUrl,
      ottName: otts.find((ott) => ott.ottId === record.ott.ottId)?.name ?? 'OTT',
    };
  }

  // 기록 없이 URL로 들어온 경우
  const detail = await getMovieDetail(FALLBACK_MOVIE_ID);

  return {
    movieId: detail.movieId,
    title: detail.title,
    posterUrl: detail.posterUrl,
    ottName: detail.ottProviders[0]?.name ?? 'OTT',
  };
}

export default function WatchConfirmPage() {
  const { watchRecordId } = useParams();
  const navigate = useNavigate();

  const [movie, setMovie] = useState<ConfirmView | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;

    void loadConfirmView(Number(watchRecordId)).then((view) => {
      if (active) {
        setMovie(view);
      }
    });

    return () => {
      active = false;
    };
  }, [watchRecordId]);

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

  return (
    <div className={styles.page}>
      <Poster width={120} height={168} radius={10} src={movie.posterUrl ?? undefined} title="" />

      <div className={styles.movie}>
        <p className={styles.title}>{movie.title}</p>
        {/* OTT 이름이 API-025 값이라 조사를 받침에 맞춰 고른다 (`티빙으로` · `웨이브로`) */}
        <p className={styles.note}>
          {movie.ottName}
          {directionParticle(movie.ottName)} 이동한 지 2일이 지났어요
        </p>
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
