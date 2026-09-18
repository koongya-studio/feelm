/*
 * 프로필 레이아웃. **7.1 · 7.2 · 7.2-A · 7.2-B가 이 하나를 그린다**(업무 S15P21E106-616).
 *
 * 사용자 시안 2026-09-18 네 장을 따른다. 사진 · 닉네임 · 소개가 가운데 서고, 그 아래 수치
 * 카드와 구역 카드(취향 키워드 · 취향 필름 · 팝콘 버킷 · 공개 한줄평)가 이어진다.
 *
 * **화면마다 다른 것은 `mode`뿐이다.** 데이터는 모두 같은 `ProfileView`이고, 공개
 * 프로필(7.2-A · 7.2-B)에 무엇이 남는지는 `api/users.ts`가 정한다. 이 컴포넌트는 값을 가려
 * 내지 않는다.
 *
 * | mode | 화면 | 구역 |
 * | --- | --- | --- |
 * | `view` | 7.1 MY | 머리 · `actions` · 수치 |
 * | `edit` | 7.2 편집 | 머리(사진 · 닉네임 · 소개 편집) · 수치 · 공개된 키워드 · 공개된 필름 |
 * | `public` | 7.2-B 남의 프로필 | 머리 · 수치 · 키워드 · 필름 · 팝콘 버킷 · 공개 한줄평 |
 * | `preview` | 7.2-A 내 공개 프로필 | `public`과 **같다.** 안내 바는 화면이 위에 붙인다 |
 *
 * 편집(`edit`)은 **그 자리에서 한 항목씩** 한다. 연필을 누르면 `onEdit`가 불리고, 화면이
 * `editing`으로 넘긴 편집기가 그 구역의 보기 자리를 대신한다. 전체가 폼으로 바뀌지 않는다.
 * 무엇을 저장하고 언제 막을지는 화면(7.2)이 정한다.
 */
import Avatar from '../Avatar';
import Chip from '../Chip';
import { CameraIcon, PencilIcon, SpeechBubbleIcon } from '../Icon';
import PopcornBucket from '../PopcornBucket';
import { kernelCounts } from '../PopcornBucket/kernels';
import Poster from '../Poster';
import RatingStars from '../RatingStars';
import VisuallyHidden from '../VisuallyHidden';
import styles from './ProfileLayout.module.css';
import type { ReactNode } from 'react';
import type { ProfileView } from '../../types/unspecified';

export type ProfileMode = 'view' | 'edit' | 'public' | 'preview';

/** 7.2에서 하나씩 고치는 항목. 사진은 편집기가 아니라 시트로 바꾸므로 여기 없다 */
export type EditableKey = 'nickname' | 'bio' | 'keywords' | 'films';

type Props = {
  mode: ProfileMode;
  profile: ProfileView;
  /** `edit` 지금 열린 편집기. 그 구역의 보기 자리에 그대로 들어간다 */
  editing?: { key: EditableKey; node: ReactNode } | null;
  /** `edit` 연필을 눌렀을 때 */
  onEdit?: (key: EditableKey) => void;
  /** `edit` 사진을 눌렀을 때 */
  onAvatar?: () => void;
  /** `edit` 사진을 올리는 중. 사진 버튼을 잠그고 표시한다 */
  avatarBusy?: boolean;
  /** `edit` 사진 아래 한 줄. 올리는 중 · 실패 · 형식 오류를 알린다 */
  avatarNote?: ReactNode;
  /** `view` 소개 아래의 버튼 줄. 7.1의 `프로필 편집` · `프로필 공유`다 */
  actions?: ReactNode;
  /** 구역 뒤에 붙는 것. 7.1의 바로가기 · 피드, 7.2의 미리보기 버튼이다 */
  children?: ReactNode;
};

/** 시안의 사진 크기. 편집은 `사진 변경` 띠가 들어가도록 조금 크다 */
const AVATAR_SIZE: Record<ProfileMode, number> = { view: 88, edit: 104, public: 88, preview: 88 };

/** 채운 칩으로 두는 대표 키워드 수. 시안이 앞의 둘을 채웠다(`Chip` `solid`의 용법) */
const LEADING_KEYWORDS = 2;

/** 팝콘 버킷 카드의 통 폭 */
const BUCKET_WIDTH = 120;

/** 공개 한줄평 별 크기 */
const REVIEW_STAR_SIZE = 14;

/** 구역마다 같은 모양의 연필. 보이는 아이콘은 작고 누르는 자리는 44px이다 */
function EditButton({ label, onClick }: { label: string; onClick?: () => void }) {
  return (
    <button type="button" className={styles.editButton} aria-label={label} onClick={onClick}>
      <PencilIcon />
    </button>
  );
}

/** 구역 카드. 제목 · 오른쪽 위 연필 · 내용이다 */
function Card({
  title,
  action,
  editing = false,
  children,
}: {
  title: string;
  action?: ReactNode;
  editing?: boolean;
  children: ReactNode;
}) {
  return (
    <section className={styles.card} data-editing={editing || undefined}>
      <div className={styles.cardHead}>
        <h2 className={styles.cardTitle}>{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function Keywords({ profile }: { profile: ProfileView }) {
  const shown = profile.keywords.filter((keyword) => keyword.isPublic);

  if (shown.length === 0) return <p className={styles.empty}>공개된 취향 키워드가 없어요</p>;

  return (
    <ul className={styles.chips}>
      {shown.map((keyword, index) => (
        <li key={keyword.code}>
          <Chip variant={index < LEADING_KEYWORDS ? 'solid' : 'outline'}>{keyword.label}</Chip>
        </li>
      ))}
    </ul>
  );
}

function Films({ profile, compact }: { profile: ProfileView; compact: boolean }) {
  const shown = profile.frames.filter((frame) => frame.isPublic);

  if (shown.length === 0) return <p className={styles.empty}>공개된 취향 필름이 없어요</p>;

  return (
    <ul className={styles.films} data-compact={compact || undefined}>
      {shown.map((frame) => (
        <li key={frame.frameId}>
          <Poster
            fluid
            width={2}
            height={3}
            radius={10}
            src={frame.posterUrl ?? undefined}
            title={frame.title}
            seed={frame.movieId}
          />
        </li>
      ))}
    </ul>
  );
}

export default function ProfileLayout({
  mode,
  profile,
  editing = null,
  onEdit,
  onAvatar,
  avatarBusy = false,
  avatarNote,
  actions,
  children,
}: Props) {
  const isEdit = mode === 'edit';
  const isPublic = mode === 'public' || mode === 'preview';
  const editorFor = (key: EditableKey) => (editing?.key === key ? editing.node : null);

  const avatar = (
    <Avatar size={AVATAR_SIZE[mode]} src={profile.avatarUrl} nickname={profile.nickname} />
  );

  const nicknameEditor = editorFor('nickname');
  const bioEditor = editorFor('bio');
  const keywordsEditor = editorFor('keywords');
  const filmsEditor = editorFor('films');

  return (
    <div className={styles.layout} data-mode={mode}>
      <header className={styles.identity}>
        {isEdit ? (
          <button
            type="button"
            className={styles.avatarButton}
            aria-label="프로필 사진 변경"
            aria-busy={avatarBusy || undefined}
            disabled={avatarBusy}
            onClick={onAvatar}
          >
            {/* 띠는 원 안에서 잘리고 카메라 표식은 원 밖으로 나온다. 그래서 자르는 층을 나눈다 */}
            <span className={styles.avatarClip}>
              {avatar}
              <span className={styles.avatarBand} aria-hidden="true">
                {avatarBusy ? '올리는 중' : '사진 변경'}
              </span>
            </span>
            <span className={styles.avatarBadge} aria-hidden="true">
              <CameraIcon />
            </span>
          </button>
        ) : (
          avatar
        )}

        {avatarNote ? <div className={styles.avatarNote}>{avatarNote}</div> : null}

        {nicknameEditor ? (
          <div className={styles.inlineEditor}>{nicknameEditor}</div>
        ) : (
          <div className={styles.nameRow}>
            {/* 화면의 h1은 헤더가 갖는다. 7.2-A · 7.2-B는 이름이 곧 그 사람이라 h2로 둔다 */}
            <h2 className={styles.name}>{profile.nickname}</h2>
            {isEdit ? (
              <EditButton label="닉네임 수정" onClick={() => onEdit?.('nickname')} />
            ) : null}
          </div>
        )}

        {bioEditor ? (
          <div className={styles.bioEditor}>{bioEditor}</div>
        ) : isEdit ? (
          <div className={styles.bioRow}>
            <p className={styles.bio} data-empty={!profile.bio || undefined}>
              {profile.bio || '나를 소개하는 한 줄을 적어 보세요'}
            </p>
            <EditButton label="소개 수정" onClick={() => onEdit?.('bio')} />
          </div>
        ) : profile.bio ? (
          <p className={styles.bio}>{profile.bio}</p>
        ) : null}
      </header>

      {mode === 'view' && actions ? <div className={styles.actions}>{actions}</div> : null}

      {/* 이름표(dt)가 먼저인 것이 목록의 규칙이다. 숫자가 위로 가는 것은 CSS가 뒤집는다 */}
      <dl className={styles.stats} data-plain={isEdit || undefined}>
        <div className={styles.stat}>
          <dt>프레임</dt>
          <dd>{profile.stats.frameCount}</dd>
        </div>
        <div className={styles.stat}>
          <dt>팝콘</dt>
          <dd>{profile.stats.popcornCount}</dd>
        </div>
        <div className={styles.stat}>
          <dt>공개 한줄평</dt>
          <dd>{profile.stats.publicReviewCount}</dd>
        </div>
      </dl>

      {isEdit || isPublic ? (
        <Card
          title={isEdit ? '공개된 취향 키워드' : '취향 키워드'}
          editing={Boolean(keywordsEditor)}
          action={
            isEdit && !keywordsEditor ? (
              <EditButton label="공개할 취향 키워드 수정" onClick={() => onEdit?.('keywords')} />
            ) : null
          }
        >
          {keywordsEditor ?? <Keywords profile={profile} />}
        </Card>
      ) : null}

      {isEdit || isPublic ? (
        <Card
          title={isEdit ? '공개된 취향 필름' : '취향 필름'}
          editing={Boolean(filmsEditor)}
          action={
            isEdit && !filmsEditor ? (
              <EditButton label="공개할 취향 필름 수정" onClick={() => onEdit?.('films')} />
            ) : null
          }
        >
          {filmsEditor ?? <Films profile={profile} compact={isEdit} />}
        </Card>
      ) : null}

      {isPublic ? (
        <Card title="팝콘 버킷">
          <div className={styles.bucket}>
            <PopcornBucket
              width={BUCKET_WIDTH}
              flavors={kernelCounts(profile.popcorn)}
              label={`팝콘 ${profile.stats.popcornCount}알`}
            />
            <p className={styles.bucketCount}>팝콘 {profile.stats.popcornCount}알</p>
          </div>
        </Card>
      ) : null}

      {isPublic ? (
        <Card title="공개 한줄평">
          {profile.reviews.length === 0 ? (
            <div className={styles.emptyReviews}>
              <SpeechBubbleIcon />
              <p>아직 공개한 한줄평이 없어요</p>
            </div>
          ) : (
            <ul className={styles.reviews}>
              {profile.reviews.map((review) => (
                <li key={review.ratingId} className={styles.review}>
                  <p className={styles.reviewHead}>
                    <RatingStars score={review.score} size={REVIEW_STAR_SIZE} gap={3} />
                    <span className={styles.reviewScore}>{review.score.toFixed(1)}</span>
                    <span className={styles.reviewMovie}>{review.movieTitle}</span>
                  </p>
                  <p className={styles.reviewComment}>{review.comment}</p>
                </li>
              ))}
            </ul>
          )}
        </Card>
      ) : null}

      {children}
    </div>
  );
}

/**
 * 프로필을 받는 동안. 사진 · 이름 · 소개 · 수치 자리만 같은 크기로 비워 둔다 — 받고 나서
 * 화면이 튀지 않는다.
 */
export function ProfileSkeleton() {
  return (
    <div className={styles.layout} aria-busy="true">
      <VisuallyHidden>
        <span role="status">프로필을 불러오는 중</span>
      </VisuallyHidden>
      <div className={styles.identity} aria-hidden="true">
        <span className={styles.skeletonAvatar} />
        <span className={styles.skeletonLine} data-width="name" />
        <span className={styles.skeletonLine} data-width="bio" />
      </div>
      <span className={styles.skeletonStats} aria-hidden="true" />
    </div>
  );
}
