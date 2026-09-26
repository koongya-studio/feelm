/*
 * 2.4-A 감상 확인
 *
 * OTT 링크를 누른 뒤 실제 감상 여부를 확인한다(FR-18). 계약상 클릭 직후부터 묻는다
 * (`utils/watchConfirm.ts`, 업무 -667 · -766). 진입 경로는 알림(8.1 · 8.2)이다
 * (업무 -308에서 이었다).
 * 2.4에서 OTT 링크를 누르면 감상 기록이 만들어지고 이 화면으로 이어진다.
 *
 * 선택지는 계약에 맞춰 두 개다(screen-spec.md 8.1 #5). API-035는 `watched` 하나만
 * 받으므로 `봤어요`(true → `WATCHED_UNRATED`)와 `보지 않을래요`(false → `EXPIRED`)만
 * 구현한다. 와이어프레임의 `아직 못 봤어요`는 대응 값이 없다. 응답하지 않고 화면을
 * 벗어나면 상태가 `LINK_CLICKED`로 남고 7일 뒤 서버가 `EXPIRED`로 바꾸므로, 그 의도는
 * 기본 동작이 대신한다. 하단 안내 문구도 두 선택지에 맞춰 줄였다.
 *
 * **감상 기록도 확인 응답도 실제 API다**(업무 -477, API-034 · API-035). 계약에 단건
 * 조회가 없어 목록에서 번호로 찾는다(`api/watch.ts`).
 *
 * 화면이 가르는 세 가지.
 * - 기록이 없다 — 주소로 직접 들어왔거나 남의 기록이다. 알림도 API-070이 준 실제 감상
 *   기록 번호를 사용하므로 정상 알림에서 이 상태가 나오면 연결된 원본이 이미 닫힌 경우다
 * - 이미 답했다 — `LINK_CLICKED`가 아닌 기록이다. 다시 물으면 서버가 `409`로 거절하므로
 *   묻지 않고 다음 행동만 준다
 * - 조회가 실패했다 — 다시 시도할 수 있다
 *
 * 와이어프레임의 `이동한 지 2일이 지났어요`는 고정 문구였지만, 기록에 실제 클릭 시각이
 * 있으므로(API-034 `clickedAt`) 그 시각으로 적는다. 2.4에서 바로 넘어오면 `방금 전`이다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ApiError } from '../../../api/apiError';
import { confirmWatch, findWatchRecord } from '../../../api/watch';
import { getOtts } from '../../../api/otts';
import { path } from '../../../routes/paths';
import { formatRelativeTime } from '../../../utils/date';
import { directionParticle, topicParticle } from '../../../utils/korean';
import styles from './WatchConfirmPage.module.css';

/** 이 화면이 표시하는 값 */
type ConfirmView = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  /** 실제로 이동한 OTT의 표시명. 표시명은 계약상 API-025에만 있다 */
  ottName: string | null;
  /** OTT 링크를 누른 시각. 클릭 없이 만들어진 기록이면 null */
  clickedAt: string | null;
  /** 아직 답하지 않은 기록인가. `LINK_CLICKED`만 물을 수 있다(API-035) */
  pending: boolean;
  /** 이미 답한 기록이 평가로 이어질 수 있는가 */
  watched: boolean;
};

/** 기록을 찾지 못했으면 null. 조회 자체가 실패하면 던진다 */
async function loadConfirmView(watchRecordId: number): Promise<ConfirmView | null> {
  const [record, otts] = await Promise.all([findWatchRecord(watchRecordId), getOtts()]);

  if (!record) {
    return null;
  }

  return {
    movieId: record.movie.movieId,
    title: record.movie.title,
    posterUrl: record.movie.posterUrl,
    ottName: otts.find((ott) => ott.ottId === record.ott?.ottId)?.name ?? null,
    clickedAt: record.clickedAt,
    pending: record.status === 'LINK_CLICKED',
    watched: record.status === 'WATCHED_UNRATED' || record.status === 'RATED',
  };
}

export default function WatchConfirmPage() {
  return (
    <>
      <ScreenHeader title="감상 확인" showBack fallbackTo={path.notifications()} />
      <WatchConfirmContent />
    </>
  );
}

function WatchConfirmContent() {
  const { watchRecordId } = useParams();
  const navigate = useNavigate();

  const [movie, setMovie] = useState<ConfirmView | null>(null);
  const [missing, setMissing] = useState(false);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  /** 응답을 보내다 실패했다. 기록은 그대로라 다시 고를 수 있다 */
  const [submitFailed, setSubmitFailed] = useState(false);

  useEffect(() => {
    let active = true;

    loadConfirmView(Number(watchRecordId))
      .then((view) => {
        if (active) {
          setMovie(view);
          setMissing(view === null);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    return () => {
      active = false;
    };
  }, [watchRecordId, reloadCount]);

  function retry() {
    setFailed(false);
    setSubmitFailed(false);
    setReloadCount((count) => count + 1);
  }

  async function respond(watched: boolean) {
    if (!movie) {
      return;
    }

    setSubmitting(true);
    setSubmitFailed(false);

    try {
      await confirmWatch(Number(watchRecordId), watched);
    } catch (error: unknown) {
      setSubmitting(false);

      /*
       * `409 INVALID_WATCH_STATUS`는 그 사이에 기록이 답해졌거나 만료된 것이다. 다시
       * 물을 수 없으므로 화면을 현재 상태로 되읽어 `이미 답한` 쪽으로 넘긴다.
       */
      if (error instanceof ApiError && error.status === 409) {
        setReloadCount((count) => count + 1);

        return;
      }

      setSubmitFailed(true);

      return;
    }

    if (watched) {
      // 완료된 감상 확인을 대체해 평가를 미뤄도 이 화면으로 돌아오지 않는다.
      void navigate(path.movieRating(movie.movieId), { replace: true });

      return;
    }

    // 보지 않을래요 → 감상 대기 종료. 시작 화면인 필름으로 돌아간다
    void navigate(path.film());
  }

  if (failed) {
    return (
      <div className={styles.page}>
        <StatusView title="감상 기록을 불러오지 못했어요" description="잠시 문제가 생겼어요">
          <Button onClick={retry}>다시 시도</Button>

          <Button variant="text" onClick={() => void navigate(path.film())}>
            필름으로 이동
          </Button>
        </StatusView>
      </div>
    );
  }

  if (missing) {
    // 없는 기록에는 다시 물어도 같은 답이라 재시도를 주지 않는다
    return (
      <div className={styles.page}>
        <StatusView
          title="감상 기록을 찾을 수 없어요"
          description="주소가 잘못되었거나 이미 정리된 기록이에요"
        >
          <Button onClick={() => void navigate(path.film())}>필름으로 이동</Button>
        </StatusView>
      </div>
    );
  }

  if (!movie) {
    return null;
  }

  if (!movie.pending) {
    return (
      <div className={styles.page}>
        <StatusView
          variant="empty"
          title="이미 답한 감상이에요"
          description={
            movie.watched
              ? // 영화 제목은 서버 값이라 받침에 맞춰 조사를 고른다
                `${movie.title}${topicParticle(movie.title)} 감상한 영화로 남아 있어요`
              : `${movie.title}의 감상 대기가 끝났어요`
          }
        >
          {movie.watched ? (
            <Button onClick={() => void navigate(path.movieRating(movie.movieId))}>
              평가하러 가기
            </Button>
          ) : null}

          <Button variant="text" onClick={() => void navigate(path.film())}>
            필름으로 이동
          </Button>
        </StatusView>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <Poster
        width={120}
        height={168}
        radius={10}
        src={movie.posterUrl ?? undefined}
        title=""
        seed={movie.movieId}
      />

      <div className={styles.movie}>
        <p className={styles.title}>{movie.title}</p>
        {/* OTT 이름이 API-025 값이라 조사를 받침에 맞춰 고른다 (`티빙으로` · `웨이브로`) */}
        {movie.ottName && movie.clickedAt ? (
          <p className={styles.note}>
            {movie.ottName}
            {directionParticle(movie.ottName)} 이동했어요 · {formatRelativeTime(movie.clickedAt)}
          </p>
        ) : null}
      </div>

      <h2 className={styles.question}>이 영화, 잘 보셨나요?</h2>

      <div className={styles.actions}>
        <Button disabled={submitting} onClick={() => void respond(true)}>
          봤어요
        </Button>

        <Button variant="text" disabled={submitting} onClick={() => void respond(false)}>
          보지 않을래요
        </Button>
      </div>

      {submitFailed ? (
        <p className={styles.error} role="alert">
          답을 보내지 못했어요. 잠시 후 다시 골라 주세요
        </p>
      ) : null}

      <p className={styles.hint}>
        봤어요 → 영화 평가 · 보지 않을래요 → 감상 대기 종료
        <br />
        답하지 않으면 7일 뒤 자동으로 감상 대기가 끝나요
      </p>
    </div>
  );
}
