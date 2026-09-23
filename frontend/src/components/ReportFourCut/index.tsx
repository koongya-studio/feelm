/*
 * 취향 4컷 리포트 — 장르 · 감독 · 배우 · 캐릭터를 한 칸씩.
 *
 * **길게 이어진 검정 인화지 안에 같은 크기의 사진 네 컷이 세로로 놓인다**(사용자 시안
 * 2026-09-15, 업무 -321). 블랙 · 빨강 · 흰색만 쓰는 영화 잡지 모양이다. 위에 `{닉네임}의 취향
 * 4컷`과 인화한 달, 아래에 Feelm 로고와 날짜를 찍는다.
 *
 * | 칸 | 사진 | 큰 이름 |
 * | --- | --- | --- |
 * | 01 장르 | 흑백 | 굵고 좁은 빨간 글자. 누끼가 있으면 인물 뒤로 들어간다 |
 * | 02 감독 | 흑백 · 빨간 바탕 · 작은 대표작 | 흰 세리프 이탤릭 대문자 |
 * | 03 배우 | 흑백을 크게 자름 | 흰 세리프 이탤릭 + 마지막 단어만 빨간 대문자 · 별표 |
 * | 04 캐릭터 | 컬러 | 흰 세리프 이탤릭 + 빨간 밑줄 |
 *
 * **데이터와 모양을 나눈다.** 사진 · 이름 · 날짜만 props로 받고 칸 크기 · 색 · 서체 · 간격 ·
 * 글자 상자는 CSS에 고정한다. 모든 길이가 인화지 폭에 비례해서(`--u`), 3.3 인화기 안의 작은
 * 4컷과 3.4 상세의 큰 4컷이 같은 모양이다.
 *
 * - 이름이 길면 최대 두 줄 안에서 글자만 작아진다(`useFitText`)
 * - 계약이 카드를 주지 않은 칸은 `준비 중`만 적는다(`PendingCut`). 04 캐릭터는 백엔드
 *   구현(업무 -779) 전까지 늘 이 모양이다
 * - **04 캐릭터 칸의 그림만 저장소에 있다.** 카드가 고정 16종이라 계약이 이 칸의 이미지
 *   URL을 내리지 않고 화면이 `characterCard.code`로 짝짓는다(`characterCards.ts`, 업무 -618).
 *   01 ~ 03은 계약이 주는 사진을 쓴다
 * - 사진 한 칸은 `url`(원본) · `cutoutUrl`(누끼)로 세 가지 모양이 된다(`useCutImage`)
 *   - 누끼 — 단색 바탕 위에 인물을 올리고 글자와 겹친다
 *   - 원본 — 원래 배경을 두고 글자 쪽에 어두운 그라데이션을 깐다
 *   - 없거나 못 불러옴 — 칸 크기는 그대로, 이름을 크게 쓴다
 * - 이 인화지 안의 빨강은 **콘텐츠**다. 앱 UI의 무채색 규칙(`design-concept.md` 3장)은 인화지
 *   바깥에 적용된다
 */
import { useState } from 'react';
import { Link } from 'react-router';
import type { CSSProperties, ReactNode } from 'react';
import Wordmark from '../Wordmark';
import { formatPrintDate } from '../../utils/report';
import { useFitText } from './useFitText';
import { characterCardImage } from './characterCards';
import { path } from '../../routes/paths';
import styles from './ReportFourCut.module.css';
import type { ReportCutImage, ReportCutName, ReportFourCut } from '../../types/report';

type Props = {
  cuts: ReportFourCut;
  /** 머리의 `{owner}의 취향 4컷`. 프로필을 못 불러왔으면 `나의 취향 4컷` */
  owner?: string | null;
  /** 리포트를 만든 시각(API-060 `createdAt`) */
  createdAt: string;
  /**
   * 머리(`{닉네임}의 취향 4컷` · 인화한 달)를 그릴지. 7.3 목록의 작은 4컷은 머리 없이 01부터
   * 시작한다(사용자 시안 2026-09-15, 업무 -446). 발의 로고 · 날짜는 늘 그린다
   */
  head?: boolean;
  /**
   * 04 칸 그림을 고를 카드 코드(API-060 `characterCard.code`). 계약이 이 칸의 이미지 URL을
   * 내리지 않으므로 그림은 코드로 찾는다. 없으면 이름만 그린다
   */
  characterCode?: string | null;
  /** 상세 화면에서 감독·배우 칸을 같은 이름의 통합 검색으로 연결한다. */
  linkPeople?: boolean;
};

export default function ReportFourCut({
  cuts,
  owner,
  createdAt,
  head = true,
  characterCode,
  linkPeople = false,
}: Props) {
  const title = `${owner ? `${owner}의` : '나의'} 취향 4컷`;
  const date = formatPrintDate(createdAt);

  return (
    <div className={styles.root}>
      <figure className={styles.strip} data-head={head} aria-label={title}>
        {head ? (
          <div className={styles.head} aria-hidden="true">
            <span className={styles.title}>{title}</span>
            <span className={styles.month}>{date.month}</span>
          </div>
        ) : null}

        <ol className={styles.cuts}>
          {cuts.genre ? <GenreCut {...cuts.genre} /> : <PendingCut kind="genre" label="장르" />}
          {cuts.director ? (
            <DirectorCut {...cuts.director} searchable={linkPeople} />
          ) : (
            <PendingCut kind="director" label="감독" />
          )}
          {cuts.actor ? (
            <ActorCut {...cuts.actor} searchable={linkPeople} />
          ) : (
            <PendingCut kind="actor" label="배우" />
          )}
          {cuts.character ? (
            <CharacterCut {...cuts.character} code={characterCode} />
          ) : (
            <PendingCut kind="character" label="캐릭터" />
          )}
        </ol>

        <figcaption className={styles.foot}>
          <Wordmark tone="inherit" size="calc(var(--u) * 30)" />
          <span className={styles.stamp}>{date.stamp}</span>
        </figcaption>
      </figure>
    </div>
  );
}

// ─────────────────────────── 칸 넷

type CutProps = ReportCutName & { image: ReportCutImage };

function GenreCut({ name, displayName, image }: CutProps) {
  const photo = useCutImage(image);

  return (
    <Cut
      kind="genre"
      label="장르"
      name={name}
      displayName={displayName}
      photo={photo}
      imageError={photo.failed}
    >
      <FitName text={displayName || name} maxLines={2} stretch={1.9} className={styles.genreName}>
        {displayName || name}
      </FitName>
    </Cut>
  );
}

function DirectorCut({
  name,
  displayName,
  image,
  work,
  searchable,
}: CutProps & { work: ReportCutImage | null; searchable: boolean }) {
  const photo = useCutImage(image);
  const [workFailed, setWorkFailed] = useState<string | null>(null);
  const workUrl = work?.url && work.url !== workFailed ? work.url : null;

  return (
    <Cut
      kind="director"
      label="감독"
      name={name}
      displayName={displayName}
      photo={photo}
      searchName={searchable ? name : undefined}
      imageError={photo.failed || Boolean(work?.url && workFailed === work.url)}
      extra={
        workUrl ? (
          <span className={styles.work}>
            <img
              src={workUrl}
              alt=""
              style={imageStyle(work)}
              onError={() => setWorkFailed(workUrl)}
            />
          </span>
        ) : null
      }
    >
      <FitName text={displayName || name} maxLines={2} className={styles.directorName}>
        {displayName || name}
      </FitName>
    </Cut>
  );
}

function ActorCut({ name, displayName, image, searchable }: CutProps & { searchable: boolean }) {
  const photo = useCutImage(image);
  const text = displayName || name;
  const words = text.trim().split(/\s+/);
  const last = words.pop() ?? text;
  const lead = words.join(' ');

  return (
    <Cut
      kind="actor"
      label="배우"
      name={name}
      displayName={displayName}
      photo={photo}
      searchName={searchable ? name : undefined}
      imageError={photo.failed}
    >
      {/* 두 단어를 서로 다른 크기로 한 줄씩 쓴다. 줄 수가 아니라 상자 폭 · 높이로만 맞춘다 */}
      <FitName text={text} maxLines={3} className={styles.actorName}>
        {lead ? <span className={styles.actorLead}>{lead}</span> : null}
        <span className={styles.actorLast}>
          <Sparkle />
          {last}
        </span>
      </FitName>
    </Cut>
  );
}

/*
 * 04 캐릭터. 다른 세 칸과 달리 사진이 계약에 없고 **코드로 저장소 에셋을 찾는다**
 * (`characterCards.ts`, 업무 -618). 그림이 없거나 못 불러오면 `useCutImage`가 `none`으로
 * 떨어져 이름만 크게 쓴다 — 에셋 16장(업무 -781)이 늦어도 칸은 동작한다.
 */
function CharacterCut({ name, displayName, code }: ReportCutName & { code?: string | null }) {
  const photo = useCutImage({ url: characterCardImage(code) });

  return (
    <Cut
      kind="character"
      label="캐릭터"
      name={name}
      displayName={displayName}
      photo={photo}
      imageError={photo.failed}
    >
      <FitName text={displayName || name} maxLines={2} className={styles.characterName}>
        <span className={styles.underlined}>
          {displayName || name}
          <Underline />
        </span>
      </FitName>
    </Cut>
  );
}

/**
 * 계약이 카드를 주지 않은 칸. 칸 크기와 번호 라벨은 그대로 두고 안만 비운다 — 4컷 레이아웃은
 * 확정된 것이라 칸을 빼지 않는다(백엔드 답 2026-09-21).
 *
 * 04 캐릭터는 백엔드 구현(업무 -779) 전까지 늘 여기로 오고, 나머지 칸은 그 반기에 셀 수 있는
 * 장르 · 인물이 없을 때만 온다. 구현 뒤에는 16종을 다 쓰므로 캐릭터 칸이 비지 않는다.
 */
function PendingCut({ kind, label }: { kind: CutKind; label: string }) {
  return (
    <li className={styles.cut} data-cut={kind} data-mode="none">
      <span className={styles.pending}>준비 중</span>
      <CutLabel kind={kind} label={label} />
    </li>
  );
}

/** 칸 왼쪽 위의 `01 장르`. 번호는 칸 종류가 정한다 */
function CutLabel({ kind, label }: { kind: CutKind; label: string }) {
  return (
    <span className={styles.label}>
      {String(CUT_INDEX[kind]).padStart(2, '0')} {label}
    </span>
  );
}

// ─────────────────────────── 칸의 뼈대

type CutKind = 'genre' | 'director' | 'actor' | 'character';

/** 인화지에 찍는 칸 번호 */
const CUT_INDEX: Record<CutKind, number> = { genre: 1, director: 2, actor: 3, character: 4 };

type CutFrameProps = ReportCutName & {
  kind: CutKind;
  label: string;
  photo: CutImageState;
  imageError?: boolean;
  /** 사진과 이름 사이에 놓는 장식. 감독 칸의 대표작 */
  extra?: ReactNode;
  /** 값이 있으면 칸 전체를 해당 인물 검색 링크로 만든다. */
  searchName?: string;
  children: ReactNode;
};

/*
 * 칸 하나. 층은 아래부터 사진 → 그라데이션 → 장식 → 큰 이름 → 누끼 인물 → 라벨이다. 누끼
 * 인물과 큰 이름 중 무엇이 앞인지는 칸마다 CSS가 정한다(장르만 이름이 인물 뒤).
 *
 * 영문 이름이 있으면 큰 글자는 장식이라 읽지 않고, 아래의 한글 이름을 읽는다. 영문 이름이
 * 없으면 한글 이름이 곧 큰 글자라 아래에 한 번 더 쓰지 않는다.
 */
function Cut({
  kind,
  label,
  name,
  displayName,
  photo,
  imageError,
  extra,
  searchName,
  children,
}: CutFrameProps) {
  const hasDisplayName = Boolean(displayName) && displayName !== name;

  return (
    <li
      className={styles.cut}
      data-cut={kind}
      data-mode={photo.mode}
      data-image-error={imageError || undefined}
    >
      {photo.mode === 'photo' && photo.src ? (
        <span className={styles.photo}>
          <img src={photo.src} alt="" style={imageStyle(photo.image)} onError={photo.fail} />
        </span>
      ) : null}

      {extra}

      <div className={styles.nameArea} aria-hidden={hasDisplayName || undefined}>
        {children}
      </div>

      {photo.mode === 'cutout' && photo.src ? (
        <span className={styles.figure}>
          <img src={photo.src} alt="" style={imageStyle(photo.image)} onError={photo.fail} />
        </span>
      ) : null}

      <CutLabel kind={kind} label={label} />
      {hasDisplayName ? <span className={styles.caption}>{name}</span> : null}
      {searchName ? (
        <Link
          className={styles.personSearchLink}
          to={path.searchWith(searchName)}
          aria-label={`${searchName} 검색`}
        />
      ) : null}
    </li>
  );
}

type FitNameProps = {
  text: string;
  maxLines: number;
  stretch?: number;
  className: string;
  children: ReactNode;
};

/** 큰 이름 상자. 상자는 CSS가 고정하고 글자 크기만 맞춘다 */
function FitName({ text, maxLines, stretch, className, children }: FitNameProps) {
  const boxRef = useFitText<HTMLDivElement>(text, { maxLines, stretch });

  return (
    <div ref={boxRef} className={`${styles.nameBox} ${className}`}>
      <p className={styles.name}>{children}</p>
    </div>
  );
}

// ─────────────────────────── 사진

type CutImageState = {
  mode: 'cutout' | 'photo' | 'none';
  src: string | null;
  image: ReportCutImage;
  failed: boolean;
  fail: () => void;
};

/**
 * 누끼 → 원본 → 없음 순으로 쓸 수 있는 사진을 고른다. 못 불러온 주소는 기억해 두고 다음
 * 것으로 넘어간다. 주소가 바뀌면 기억한 실패와 달라져 저절로 다시 시도한다.
 *
 * 계약이 사진을 주지 않는 칸은 이름만 그린다. 다른 사진으로 대신 채우지 않는다 — 리포트에
 * 뽑힌 장르 · 인물과 다른 사진이 그 이름 아래 붙는다(사용자 결정 2026-09-21).
 */
function useCutImage(image: ReportCutImage): CutImageState {
  const [failed, setFailed] = useState<string[]>([]);
  const usable = (url?: string | null) => (url && !failed.includes(url) ? url : null);

  const cutout = usable(image.cutoutUrl);
  const original = usable(image.url);
  const src = cutout ?? original;
  const mode = cutout ? 'cutout' : original ? 'photo' : 'none';

  return {
    mode,
    src,
    image,
    failed: !src && failed.length > 0,
    fail: () => {
      if (src) setFailed((list) => [...list, src]);
    },
  };
}

function imageStyle(image: ReportCutImage | null): CSSProperties | undefined {
  if (!image) return undefined;

  const x = image.position?.x ?? 50;
  const y = image.position?.y ?? 50;

  return {
    '--image-x': `${x}%`,
    '--image-y': `${y}%`,
    '--image-zoom': String(Math.max(image.zoom ?? 1, 1)),
  } as CSSProperties;
}

// ─────────────────────────── 장식

function Sparkle() {
  return (
    <svg className={styles.sparkle} viewBox="0 0 20 20" aria-hidden="true">
      <path d="M10 0 L11 8.6 L20 10 L11 11.4 L10 20 L9 11.4 L0 10 L9 8.6 Z" />
    </svg>
  );
}

function Underline() {
  return (
    <svg
      className={styles.underline}
      viewBox="0 0 100 8"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <path d="M0 7.2 C 30 5.6, 65 3.2, 100 0.6 L100 2.4 C 65 4.8, 30 7, 0 8 Z" />
    </svg>
  );
}
