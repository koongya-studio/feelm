/*
 * 2.5 영화 평가
 *
 * 구성은 확정 시안을 따른다(`docs/screens/design-concept.md`). 시안은 이 화면이
 * 한 가지만 말하게 한다. **몇 점인가.**
 *
 * 팀 결정 2026-09-10 (업무 -99)
 * - 직접 만든 헤더를 공통 `ScreenHeader`로 바꿨다. 명세의 뒤로가기형 헤더가 바로
 *   이 컴포넌트이고, 제목을 h1으로 렌더하는 것도 여기 한 곳에서 하게 된다
 * - 질문을 화면 제목 크기로 올렸다. 이 화면이 묻는 한 문장이다
 * - 질문과 별점을 화면 가운데로 모으고 주 동작을 아래에 붙였다. 이전에는 위에서부터
 *   차곡차곡 쌓여 별점이 다른 요소와 같은 무게로 읽혔다
 *
 * 요소와 문구는 와이어프레임 2.5를 따른다(screen-spec.md 6장 2.5).
 * 별점은 0.5점 단위 0.5~5.0이며 계약도 같은 규칙이다(FR-19, API-036 `multipleOf: 0.5`).
 *
 * **수정에서는 평가를 지울 수 있다**(업무 -724, API-038). 평가와 프레임이 함께 사라지고 팝콘도
 * 한 알 빠진다. 감상 기록은 남아 `WATCHED_UNRATED`로 되돌아가므로 그 영화는 7.1의 `안 남긴 영화`로
 * 간다 — 본 것은 맞고 평가만 없는 상태다.
 *
 * `보지 않은 영화로 처리`는 여전히 두지 않는다. 계약은 열렸지만(업무 -721이 API-035에
 * `WATCHED_UNRATED → EXPIRED`를 더했다) **필름에서 빼는 일은 하지 않기로 했다**
 * (사용자 결정 2026-09-20). 감상까지 무르려면 삭제 뒤 API-035를 한 번 더 불러야 한다.
 *
 * 한줄평 공개 범위 선택 UI는 와이어프레임에 없어 만들지 않는다(CR-05). 대신 공통 규약의
 * 기본값인 `PRIVATE`을 붙여 보내고, 그 사실을 화면에 안내한다(팀 결정 2026-09-07).
 * CR-05가 반영되면 이 안내를 선택 컨트롤로 교체한다.
 *
 * 이미 평가한 영화로 들어오면 수정이다(업무 -306, API-037). 7.6 · 3.1-A의 `평가 수정`과
 * 2.4의 `내 별점 남기기`가 여기로 온다. 저장 후 2.6 · 2.7이 아니라 온 곳으로 돌아간다.
 *
 * `[?]` **새 평가의** `나중에 평가하기`가 어디로 가는지는 미확정이다. 이 화면으로 온 경로가
 * 영화 상세든 감상 확인이든 되돌아가는 것이 자연스러워 뒤로 가기로 두었다. 수정에는 이
 * 버튼을 두지 않으므로(업무 -716) 미확정은 새 평가에 한정된다.
 * 감상 정보(`6월 2일 · 넷플릭스에서 시청`)는 감상 기록 목록(API-034)에서 읽는다. 2.4-A에서
 * `봤어요`를 고른 기록의 `watchedAt`과 `ott`를 쓰므로 실제로 이동한 OTT가 나온다
 * (업무 -263). 2.4에서 바로 들어와 감상 기록이 없으면 이 줄을 표시하지 않는다.
 *
 * 영화 정보(API-030, 업무 -475) · 감상 기록(API-034, 업무 -477) · 평가 생성(API-036,
 * 업무 -491) · 평가 수정(API-037, 업무 -213)은 실제 API다. 저장하면 서버가 같은
 * 트랜잭션에서 감상 기록을 `RATED`로 옮기므로 평가한 영화는 7.7 `안 남긴 영화`에서 빠진다.
 * 목업 목록(7.6)에서 들어오면 그 `movieId`가 실제 DB에 없어 오류 상태가 될 수 있다.
 *
 * 저장 실패는 화면에 남기고 이동하지 않는다. 계약이 가르는 것 중 화면이 달리 말하는 것은
 * `409 RATING_ALREADY_EXISTS` 하나다 — 상세를 읽은 뒤 다른 곳에서 평가가 생긴 경우라,
 * 알리고 상세를 다시 읽어 수정 모드로 바꾼다. 나머지(`400` 검증 · `404` · 네트워크)는
 * 서버 문구를 그대로 보여준다.
 */
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import ConfirmDialog from '../../../components/ConfirmDialog';
import Poster from '../../../components/Poster';
import RatingInput from '../../../components/RatingInput';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import TextArea from '../../../components/TextArea';
import { ApiError } from '../../../api/apiError';
import { getMovieDetail } from '../../../api/movies';
import { getOtts } from '../../../api/otts';
import {
  createRating,
  deleteRating,
  findWatchedRecord,
  getRatingCounts,
  updateRating,
} from '../../../api/watch';
import { useBack } from '../../../hooks/useBack';
import { path } from '../../../routes/paths';
import { formatMonthDay } from '../../../utils/date';
import { movieMetaLine } from '../../../utils/movie';
import styles from './MovieRatingPage.module.css';
import type { RatingCounts } from '../../../api/watch';
import type { MovieDetail } from '../../../types/movie';

/** 무엇을 평가하는지 확인시키는 작은 포스터. 이 화면의 주인공은 별점이다 */
const POSTER_WIDTH = 88;
const POSTER_HEIGHT = 132;

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  RATING_ALREADY_EXISTS: '이미 평가한 영화예요. 저장된 평가를 불러왔어요',
  RESOURCE_NOT_FOUND: '영화를 찾을 수 없어요',
};

function failureMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return MESSAGE[error.code] ?? error.message;
  }

  return '평가를 저장하지 못했어요. 잠시 후 다시 시도해 주세요';
}

export default function MovieRatingPage() {
  const { movieId } = useParams();
  const navigate = useNavigate();
  // 2.4 · 7.1 · 3.1-A에서 온다. 바로 들어왔으면 MY로 돌아간다(업무 -610)
  const back = useBack(path.my());

  const id = Number(movieId);

  /*
   * 추가 전 수치를 화면에 들어올 때 미리 받아 둔다(업무 -635). `평가 완료`를 누른 뒤
   * 수치 조회 · 저장 · 재조회를 차례로 기다리느라 2.6이 늦게 떴다. 별점을 고르는 사이 수치가
   * 바뀌는 것은 다른 기기의 평가나 대표 맛 배정뿐이고, 그래도 2.7은 저장 후 실측과의 차이로
   * 적는다. 실패했으면 누를 때 다시 받는다
   */
  const countsRequest = useRef<Promise<RatingCounts> | null>(null);
  const [movie, setMovie] = useState<MovieDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  /** 2.5의 감상 정보 줄. 감상을 확인한 기록이 없으면 null */
  const [watchNote, setWatchNote] = useState<string | null>(null);
  const [score, setScore] = useState(0);
  const [comment, setComment] = useState('');
  const [spoiler, setSpoiler] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  /** 삭제 확인 창이 떠 있는지. 되돌릴 수 없는 동작이라 한 번 묻는다(업무 -724) */
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  /** 저장 실패 문구. 다시 저장을 시도하면 지운다 */
  const [failure, setFailure] = useState<string | null>(null);

  /** 실패한 요청은 비워서 다음에 누를 때 새로 받게 한다 */
  function requestCounts() {
    const request = getRatingCounts();

    request.catch(() => {
      if (countsRequest.current === request) {
        countsRequest.current = null;
      }
    });

    return request;
  }

  useEffect(() => {
    let active = true;

    getMovieDetail(id)
      .then((detail) => {
        if (active) {
          // 수정은 프레임을 만들지 않아 수치를 쓰지 않는다
          if (!detail.myRating && !countsRequest.current) {
            countsRequest.current = requestCounts();
          }
          setMovie(detail);
          setFailed(false);
          setScore(detail.myRating?.score ?? 0);
          setComment(detail.myRating?.comment ?? '');
          setSpoiler(detail.myRating?.spoiler ?? false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    // 감상 정보 줄에만 쓰이므로 실패하면 그 줄만 표시하지 않는다
    Promise.all([findWatchedRecord(id), getOtts()])
      .then(([record, otts]) => {
        if (!active || !record?.watchedAt) {
          return;
        }

        // 클릭 없이 평가로 만들어진 기록에는 OTT가 없다(API-034). 그때는 날짜만 남는다
        const ottName = otts.find((ott) => ott.ottId === record.ott?.ottId)?.name;

        setWatchNote(
          ottName
            ? `${formatMonthDay(record.watchedAt)} · ${ottName}에서 시청`
            : formatMonthDay(record.watchedAt),
        );
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  /**
   * 평가 삭제(API-038, 업무 -724).
   *
   * **평가와 프레임이 함께 사라진다.** 감상 기록은 남아 `안 남긴 영화`로 옮겨 간다 — 본 것은
   * 맞고 평가만 없는 상태다. 지운 뒤에는 저장했을 때와 같이 온 곳으로 돌아간다.
   *
   * 404는 오류로 남기지 않는다. 다른 곳에서 이미 지운 평가라는 뜻이라, 사용자가 바라던
   * 결과가 이미 이루어져 있다(API-038 처리 규칙).
   */
  async function remove() {
    if (!movie?.myRating) return;

    setDeleting(true);
    setFailure(null);

    try {
      await deleteRating(movie.myRating.ratingId);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 404) {
        setFailure(failureMessage(error));
        setDeleting(false);
        setConfirming(false);

        return;
      }
    }

    // 창을 먼저 닫는다. 돌아갈 곳이 없어 화면에 남더라도 확인 창이 떠 있으면 안 된다
    setConfirming(false);
    setDeleting(false);
    back();
  }

  async function submit() {
    if (!movie || score === 0) {
      return;
    }

    setSubmitting(true);
    setFailure(null);

    const trimmed = comment.trim();

    try {
      /*
       * 이미 평가한 영화는 수정이다(API-037). 새 평가를 만들면 서버가 `409`를 주고, 그 전
       * 목업 시절에는 프레임과 팝콘이 한 번 더 쌓였다(업무 -306 전의 3.1-A `평가 수정`).
       * 수정은 프레임을 만들지 않으므로 2.6 · 2.7로 가지 않고 온 곳으로 돌아간다
       * (7.6 · 3.1-A · 2.4).
       */
      if (movie.myRating) {
        await updateRating(movie.myRating.ratingId, {
          score,
          comment: trimmed || null,
          /*
           * 한줄평이 없던 평가는 서버가 주는 `visibility`가 **`null`** 이다(API-030 처리 규칙).
           * 그 값을 그대로 실어 한줄평을 새로 붙이면 `comment`가 있는데 `visibility`가 없어
           * 계약상 `400`이다(API-037, 업무 -763). 2.5에는 공개 범위 선택 UI가 없으므로 생성
           * 경로와 같은 기본값 `PRIVATE`로 채운다(CR-05). 이미 값이 있던 평가는 그대로 둔다.
           */
          visibility: movie.myRating.visibility ?? 'PRIVATE',
          spoiler: trimmed ? spoiler : false,
        });

        back();

        return;
      }

      // 추가 전 수치가 있어야 저장한다. 여기서 실패하면 저장하지 않으므로 다시 눌러도 `409`가 없다
      countsRequest.current ??= requestCounts();
      const counts = await countsRequest.current;
      const rating = await createRating(
        movie.movieId,
        // comment가 있으면 visibility가 필수다(API-036). 선택 UI가 없어 기본값 PRIVATE
        trimmed ? { score, comment: trimmed, visibility: 'PRIVATE', spoiler } : { score },
      );

      /*
       * 2.6은 추가 전 수치만 그리므로 저장이 끝나면 바로 넘어간다. 추가 **후** 수치는 2.6이
       * 떠 있는 동안 그 화면이 읽는다(업무 -635). 전에는 여기서 재조회까지 기다렸다
       */
      void navigate(path.ratingResult(movie.movieId), {
        state: { rating, counts, movieTitle: movie.title, posterUrl: movie.posterUrl },
      });
    } catch (error) {
      setFailure(failureMessage(error));
      setSubmitting(false);

      // 상세를 읽은 뒤 다른 곳에서 평가가 생겼다. 다시 읽으면 `myRating`이 차서 수정 모드가 된다
      if (error instanceof ApiError && error.code === 'RATING_ALREADY_EXISTS') {
        setReloadCount((count) => count + 1);
      }
    }
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="평가하기" showBack fallbackTo={path.search()} />

        <StatusView
          title="영화 정보를 불러오지 못했어요"
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

  if (!movie) {
    return null;
  }

  const isEditing = movie.myRating !== null;
  // 개봉일 · 장르 · 상영 시간은 원천에 없으면 빠진다(API-030). 있는 값만 잇는다
  const meta = movieMetaLine(movie);

  return (
    <>
      {/*
       * 이미 평가한 영화면 제목이 `평가 수정`이다(업무 -638). 7.1 `평가한 영화` → 2.4
       * `내 별점 수정하기`로 오는 길이 생겨, 새 평가와 같은 제목이면 무엇을 하는 화면인지 흐렸다.
       * 불러오기 실패 화면은 어느 쪽인지 모르므로 `평가하기` 그대로다.
       */}
      <ScreenHeader
        title={isEditing ? '평가 수정' : '평가하기'}
        showBack
        fallbackTo={path.search()}
      />

      <div className={styles.page}>
        <div className={styles.movie}>
          <Poster
            width={POSTER_WIDTH}
            height={POSTER_HEIGHT}
            radius={10}
            src={movie.posterUrl ?? undefined}
            title={movie.title}
            seed={movie.movieId}
          />

          <div className={styles.movieInfo}>
            <p className={styles.movieTitle}>{movie.title}</p>
            {meta ? <p className={styles.movieMeta}>{meta}</p> : null}
            {/* 감상 기록이 없으면 이 줄을 표시하지 않는다 */}
            {watchNote ? <p className={styles.movieMeta}>{watchNote}</p> : null}
          </div>
        </div>

        {/* 이 화면이 묻는 한 문장과 그 답. 화면 가운데를 차지한다 */}
        <div className={styles.ask}>
          <p className={styles.question}>
            이 영화를
            <br />
            어떻게 평가하시겠어요?
          </p>

          <RatingInput value={score} onChange={setScore} disabled={submitting} />
        </div>

        <div className={styles.commentArea}>
          <TextArea
            label="한줄평"
            value={comment}
            onChange={(value) => {
              setComment(value);
              if (!value.trim()) setSpoiler(false);
            }}
            placeholder="한 줄 감상 남기기 (선택)"
            disabled={submitting}
          />
          <label className={styles.spoilerControl}>
            <input
              type="checkbox"
              checked={spoiler}
              onChange={(event) => setSpoiler(event.currentTarget.checked)}
              disabled={!comment.trim() || submitting}
            />
            <span>스포일러가 포함되어 있어요</span>
          </label>
          {comment.trim() ? (
            <p className={styles.visibilityNote}>
              {isEditing
                ? `한줄평은 ${movie.myRating?.visibility === 'PUBLIC' ? '공개' : '비공개'}로 저장돼요 · 공개 범위는 한줄평 관리에서 바꿀 수 있어요`
                : '한줄평은 비공개로 저장돼요 · 공개 범위 선택은 준비 중이에요'}
            </p>
          ) : null}
        </div>

        <div className={styles.actions}>
          {failure ? (
            <p className={styles.failure} role="alert">
              {failure}
            </p>
          ) : null}

          <Button disabled={score === 0 || submitting} onClick={() => void submit()}>
            {submitting ? '저장하는 중…' : '평가 완료'}
          </Button>

          {/* `[?]` 수정일 때의 안내는 와이어프레임에 없다. 원래 문구는 새 평가에만 맞는다 */}
          <p className={styles.notice}>
            {isEditing
              ? '평가를 고쳐도 프레임과 팝콘은 그대로예요'
              : '평가는 영화당 한 번만 반영돼요 · 프레임 1개 + 팝콘 1알'}
          </p>

          {/*
           * **수정에는 `나중에 평가하기`를 두지 않는다**(업무 -716, 사용자 지적 2026-09-20).
           * 이미 평가한 사람에게 나중에 평가하라는 말은 앞뒤가 맞지 않는다. 되돌아가는 길은
           * 머리말의 뒤로 가기가 그대로 맡는다.
           *
           * 그 자리에 **평가 삭제**가 선다(업무 -724). 새 평가에는 지울 것이 없으므로 수정일
           * 때만이다. 글자 행동이라 `평가 완료`와 무게가 겹치지 않는다.
           */}
          {isEditing ? (
            <Button
              variant="text"
              disabled={submitting || deleting}
              onClick={() => setConfirming(true)}
            >
              평가 삭제
            </Button>
          ) : (
            <Button variant="text" disabled={submitting} onClick={back}>
              나중에 평가하기
            </Button>
          )}
        </div>
      </div>

      {/* 되돌릴 수 없다. 무엇까지 사라지는지 그 자리에서 말한다 */}
      {confirming ? (
        <ConfirmDialog
          title="평가를 삭제할까요?"
          description="이 영화의 별점과 한줄평이 사라지고 필름의 프레임과 팝콘 한 알도 함께 빠져요. 되돌릴 수 없어요."
          confirmLabel="삭제"
          busy={deleting}
          onConfirm={() => void remove()}
          onCancel={() => setConfirming(false)}
        />
      ) : null}
    </>
  );
}
