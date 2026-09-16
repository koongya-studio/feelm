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
 * 계약 기준으로 확정한 것(screen-spec.md 8.1 #11): `보지 않은 영화로 처리`는 제외한다.
 * API-035는 감상 확인 화면 전용이고 평가 화면에서 감상 상태를 되돌리는 계약이 없다.
 *
 * 한줄평 공개 범위 선택 UI는 와이어프레임에 없어 만들지 않는다(CR-05). 대신 공통 규약의
 * 기본값인 `PRIVATE`을 붙여 보내고, 그 사실을 화면에 안내한다(팀 결정 2026-09-07).
 * CR-05가 반영되면 이 안내를 선택 컨트롤로 교체한다.
 *
 * 이미 평가한 영화로 들어오면 수정이다(업무 -306, API-037). 7.6 · 3.1-A의 `평가 수정`과
 * 2.4의 `내 별점 남기기`가 여기로 온다. 저장 후 2.6 · 2.7이 아니라 온 곳으로 돌아간다.
 *
 * `[?]` `나중에 평가하기`가 어디로 가는지는 미확정이다. 이 화면으로 온 경로가 영화 상세든
 * 감상 확인이든 되돌아가는 것이 자연스러워 뒤로 가기로 두었다.
 * 감상 정보(`6월 2일 · 넷플릭스에서 시청`)는 감상 기록 목록(API-034)에서 읽는다. 2.4-A에서
 * `봤어요`를 고른 기록의 `watchedAt`과 `ott`를 쓰므로 실제로 이동한 OTT가 나온다
 * (업무 -263). 2.4에서 바로 들어와 감상 기록이 없으면 이 줄을 표시하지 않는다.
 *
 * 영화 정보(API-030, 업무 -475)와 감상 기록(API-034, 업무 -477)은 실제 API에서 온다.
 * 평가 저장(API-036 · API-037)은 아직 목업이라 이 화면은 실제 영화 위에서 목업 평가를
 * 만든다. **목업 목록(7.6)에서 들어오면 그 `movieId`가 실제 DB에 없어 오류 상태가 된다.**
 * 7.7은 이제 서버 기록이라 실제 영화로 이어진다.
 *
 * `[!]` **목업 평가는 감상 기록을 `RATED`로 옮기지 못한다**(업무 -477). 여기서 평가해도
 * 서버 기록은 `WATCHED_UNRATED`에 남아 7.7 목록에서 빠지지 않는다. 평가 생성(API-036)이
 * 연동되면 서버가 같은 트랜잭션에서 옮긴다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Poster from '../../../components/Poster';
import RatingInput from '../../../components/RatingInput';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import TextArea from '../../../components/TextArea';
import { getMovieDetail } from '../../../api/movies';
import { getOtts } from '../../../api/otts';
import {
  createRating,
  findWatchedRecord,
  getCountsBeforeRating,
  updateRating,
} from '../../../api/watch';
import { path } from '../../../routes/paths';
import { formatMonthDay } from '../../../utils/date';
import { movieMetaLine } from '../../../utils/movie';
import styles from './MovieRatingPage.module.css';
import type { MovieDetail } from '../../../types/movie';

/** 무엇을 평가하는지 확인시키는 작은 포스터. 이 화면의 주인공은 별점이다 */
const POSTER_WIDTH = 88;
const POSTER_HEIGHT = 132;

export default function MovieRatingPage() {
  const { movieId } = useParams();
  const navigate = useNavigate();

  const id = Number(movieId);

  const [movie, setMovie] = useState<MovieDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  /** 2.5의 감상 정보 줄. 감상을 확인한 기록이 없으면 null */
  const [watchNote, setWatchNote] = useState<string | null>(null);
  const [score, setScore] = useState(0);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;

    getMovieDetail(id)
      .then((detail) => {
        if (active) {
          setMovie(detail);
          setFailed(false);
          setScore(detail.myRating?.score ?? 0);
          setComment(detail.myRating?.comment ?? '');
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

  async function submit() {
    if (!movie || score === 0) {
      return;
    }

    setSubmitting(true);

    const trimmed = comment.trim();

    /*
     * 이미 평가한 영화는 수정이다(API-037). 새 평가를 만들면 프레임과 팝콘이 한 번 더
     * 쌓인다 — 업무 -306 전에는 3.1-A `평가 수정`이 실제로 그렇게 동작했다.
     * 수정은 프레임을 만들지 않으므로 2.6 · 2.7로 가지 않고 온 곳으로 돌아간다
     * (7.6 · 3.1-A · 2.4).
     */
    if (movie.myRating) {
      await updateRating(movie.myRating.ratingId, {
        score,
        comment: trimmed || null,
        visibility: movie.myRating.visibility,
      });

      void navigate(-1);

      return;
    }

    const counts = await getCountsBeforeRating();
    const rating = await createRating(
      movie.movieId,
      // comment가 있으면 visibility가 필수다(API-036). 선택 UI가 없어 기본값 PRIVATE
      trimmed ? { score, comment: trimmed, visibility: 'PRIVATE' } : { score },
      /*
       * 대표 맛은 배정되지 않았으면 `null`이다(API-030). 목업 평가는 맛 코드를 요구하므로
       * 빈 코드로 넘기고, 2.7의 맛 이름은 그 자리에서 빈칸이 된다. 평가가 연동되면
       * 맛은 서버가 정한다(API-036 응답의 `popcorn`).
       */
      movie.flavorCode ?? '',
    );

    // 2.6 · 2.7은 추가 전 수치가 있어야 성립하므로 Router state로 넘긴다
    void navigate(path.ratingResult(movie.movieId), {
      state: { rating, counts, movieTitle: movie.title, posterUrl: movie.posterUrl },
    });
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="평가하기" showBack />

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
      <ScreenHeader title="평가하기" showBack />

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
            onChange={setComment}
            placeholder="한 줄 감상 남기기 (선택)"
            disabled={submitting}
          />
          {comment.trim() ? (
            <p className={styles.visibilityNote}>
              {isEditing
                ? `한줄평은 ${movie.myRating?.visibility === 'PUBLIC' ? '공개' : '비공개'}로 저장돼요 · 공개 범위는 한줄평 관리에서 바꿀 수 있어요`
                : '한줄평은 비공개로 저장돼요 · 공개 범위 선택은 준비 중이에요'}
            </p>
          ) : null}
        </div>

        <div className={styles.actions}>
          <Button disabled={score === 0 || submitting} onClick={() => void submit()}>
            평가 완료
          </Button>

          {/* `[?]` 수정일 때의 안내는 와이어프레임에 없다. 원래 문구는 새 평가에만 맞는다 */}
          <p className={styles.notice}>
            {isEditing
              ? '평가를 고쳐도 프레임과 팝콘은 그대로예요'
              : '평가는 영화당 한 번만 반영돼요 · 프레임 1개 + 팝콘 1알'}
          </p>

          <Button variant="text" disabled={submitting} onClick={() => void navigate(-1)}>
            나중에 평가하기
          </Button>
        </div>
      </div>
    </>
  );
}
