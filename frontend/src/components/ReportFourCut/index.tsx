/*
 * 취향 리포트 4컷 — 장르 · 감독 · 배우 · 캐릭터를 한 칸씩.
 *
 * **리포트는 세로로 네 칸이 이어진 4컷 사진이다**(사용자 결정 2026-09-13, 업무 -308).
 * 와이어프레임 3.4도 좁고 긴 띠 안에 칸 넷을 위에서 아래로 쌓았다. 두 칸씩 나눈 격자로
 * 그리면 그 장면이 사라진다.
 *
 * 3.3 발급(인화기에서 나오는 장면)과 3.4 · 7.4 상세(들고 보는 장면)가 같은 4컷을 쓴다.
 *
 * - 띠가 어두운 블록이고 칸이 사진이다. 사진 자리는 밝은 면이다 — 이미지가 계약에 없어
 *   (API-060 `characterCard.imageUrl`만 있다) 글자가 사진을 대신한다
 * - 칸마다 번호(01 ~ 04)를 붙여 한 칸에 하나라는 것을 드러낸다
 * - 맨 아래 띠 여백에 반기를 적는다. 4컷 사진의 날짜 자리다
 * - 영문 머리말(GENRE · DIRECTOR …)은 와이어프레임 표기다
 */
import styles from './ReportFourCut.module.css';
import type { ReportDetailView } from '../../types/unspecified';

type Props = {
  headline: ReportDetailView['headline'];
  /** 띠 아래 여백에 적는 반기. `2026 상반기` */
  caption: string;
};

export default function ReportFourCut({ headline, caption }: Props) {
  const cuts = [
    { eyebrow: 'GENRE', label: '장르', value: headline.genre },
    { eyebrow: 'DIRECTOR', label: '감독', value: headline.director },
    { eyebrow: 'ACTOR', label: '배우', value: headline.actor },
    { eyebrow: 'CHARACTER', label: '캐릭터', value: headline.character },
  ];

  return (
    <figure className={styles.strip}>
      <ol className={styles.cuts}>
        {cuts.map((cut, index) => (
          <li key={cut.eyebrow} className={styles.cut}>
            <span className={styles.number} aria-hidden="true">
              {String(index + 1).padStart(2, '0')}
            </span>
            <span className={styles.eyebrow}>
              {cut.eyebrow} · {cut.label}
            </span>
            <span className={styles.value}>{cut.value}</span>
          </li>
        ))}
      </ol>

      <figcaption className={styles.caption}>FEELM · {caption}</figcaption>
    </figure>
  );
}
