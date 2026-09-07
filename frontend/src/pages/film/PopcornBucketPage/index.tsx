/*
 * 3.2 100알의 취향
 *
 * 요소와 순서는 와이어프레임 3.2 그대로다(screen-spec.md 7장 3.2).
 * 제목·카운트 · 안내 문구 · 팝콘 버킷 · 맛 범례 8개 · 설명 · 요약 문장
 *
 * 계약 기준으로 확정한 세 가지를 적용한다(screen-spec.md 8.1).
 * - 맛 이름·색상은 API-044의 `displayName`·`color`를 쓰고 장르명을 하드코딩하지 않는다
 * - `테마 · 장르 · 별점` 3개 탭은 구현하지 않는다. API-041에 대응이 없다
 * - 맛 선호도(평균 평점)를 범례에 함께 표시한다(FR-06 인수 조건)
 *
 * 팝콘 알은 SVG로 렌더한다(ADR-0013). 와이어프레임은 22px 원 100개를 10열 격자에
 * 6px 간격으로 둔다. 알은 맛 순서대로 채운다.
 *
 * 팀 결정 2026-09-07
 * - 배경은 확정 토큰(흰색)을 쓴다. 와이어프레임 3.2만 `#F7F3F2`이고 다른 화면은 모두
 *   흰색이며 `DESIGN_GOALS.md` 1.1 · 1.2가 흰색으로 확정돼 있다
 * - 와이어프레임에 없는 `파티 팝콘 섞기 보기` 버튼을 추가해 5.9 진입점을 만든다.
 *   screen-spec.md 3.2가 진입 요소 미명시로 표시한 항목이다
 *
 * `[?]` 화면 문구가 100알을 전제로 쓰여 있다. 합이 100이 아닐 때의 표현은 미확정이라
 * 수치를 그대로 넣어 문장을 만든다.
 * `[?]` `새로운 맛`(API-041 `isNewFlavor`) 표시는 와이어프레임에 없어 쓰지 않는다.
 * `[?]` 평균 평점 표기 형식은 FR-06이 정하지 않아 `★ 4.4`로 둔다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import { getPopcornBucket } from '../../../api/taste';
import { path } from '../../../routes/paths';
import { conjunctionParticle, subjectParticle } from '../../../utils/korean';
import styles from './PopcornBucketPage.module.css';
import type { PopcornBucket, PopcornBucketFlavor } from '../../../types/taste';

/** 와이어프레임 실측값. 알 22px · 간격 6px · 10열 */
const KERNEL_SIZE = 22;
const KERNEL_GAP = 6;
const COLUMNS = 10;

/** 목업 파티. 파티 목록(5.1)이 범위 밖이라 파티를 고르는 화면이 없다 */
const FALLBACK_PARTY_ID = 9001;

/** 알 하나가 어느 맛인지. 맛 순서대로 채운다 */
function kernelColors(flavors: PopcornBucketFlavor[]): string[] {
  return flavors.flatMap((flavor) =>
    Array.from({ length: flavor.popcornCount }, () => flavor.color),
  );
}

function percent(count: number, total: number): number {
  if (total === 0) {
    return 0;
  }

  return Math.round((count / total) * 100);
}

export default function PopcornBucketPage() {
  const navigate = useNavigate();

  const [bucket, setBucket] = useState<PopcornBucket | null>(null);

  useEffect(() => {
    let active = true;

    void getPopcornBucket().then((value) => {
      if (active) {
        setBucket(value);
      }
    });

    return () => {
      active = false;
    };
  }, []);

  if (!bucket) {
    return (
      <>
        <ScreenHeader title="100알의 취향" showBack />
      </>
    );
  }

  const total = bucket.totalCount;
  const colors = kernelColors(bucket.flavors);
  const rows = Math.max(1, Math.ceil(colors.length / COLUMNS));
  const gridSize = (size: number) => size * KERNEL_SIZE + (size - 1) * KERNEL_GAP;

  // 상위 두 맛으로 요약 문장을 만든다. 산출 규칙은 계약에 없어 화면에서 계산한다
  const top = [...bucket.flavors].sort((a, b) => b.popcornCount - a.popcornCount).slice(0, 2);
  const topShare = top.reduce((sum, flavor) => sum + percent(flavor.popcornCount, total), 0);
  const summary = top
    .map((flavor, index) =>
      index === top.length - 1
        ? `${flavor.displayName}${subjectParticle(flavor.displayName)}`
        : `${flavor.displayName}${conjunctionParticle(flavor.displayName)}`,
    )
    .join(' ');

  return (
    <>
      <ScreenHeader
        title="100알의 취향"
        showBack
        action={<span className={styles.count}>본 영화 {total}편</span>}
      />

      <div className={styles.page}>
        <div className={styles.lead}>
          <p className={styles.leadTitle}>한 알이 영화 한 편이에요</p>
          <p className={styles.leadText}>
            {total}편이 {total}알로 모였어요
          </p>
        </div>

        <div className={styles.bucketArea}>
          <span className={styles.bucketOuter} aria-hidden="true" />
          <span className={styles.bucketInner} aria-hidden="true" />
          <span className={styles.bucketRim} aria-hidden="true" />

          <svg
            className={styles.kernels}
            width={gridSize(COLUMNS)}
            height={gridSize(rows)}
            viewBox={`0 0 ${gridSize(COLUMNS)} ${gridSize(rows)}`}
            role="img"
            aria-label={`팝콘 ${total}알`}
            data-testid="popcorn-kernels"
          >
            {colors.map((color, index) => (
              <circle
                key={index}
                cx={(index % COLUMNS) * (KERNEL_SIZE + KERNEL_GAP) + KERNEL_SIZE / 2}
                cy={Math.floor(index / COLUMNS) * (KERNEL_SIZE + KERNEL_GAP) + KERNEL_SIZE / 2}
                r={KERNEL_SIZE / 2}
                fill={color}
              />
            ))}
          </svg>
        </div>

        <ul className={styles.legend}>
          {bucket.flavors.map((flavor) => (
            <li key={flavor.flavorCode} className={styles.legendItem}>
              <span
                className={styles.swatch}
                style={{ background: flavor.color }}
                aria-hidden="true"
              />
              <span className={styles.legendText}>
                {flavor.displayName} {flavor.popcornCount}알 · {percent(flavor.popcornCount, total)}
                %{/* 맛 선호도는 감상량과 구분해 표시한다 (FR-06) */}
                {flavor.averageScore === null ? null : (
                  <span className={styles.legendScore}> · ★ {flavor.averageScore.toFixed(1)}</span>
                )}
              </span>
            </li>
          ))}
        </ul>

        <p className={styles.note}>
          8가지 팝콘 맛 · 한 알 = 영화 한 편 · 총 {total}알 = 평가한 영화 {total}편 · 데이터에 따라
          색과 비율이 달라져요
        </p>

        {/* 맛 이름이 API-044 값이라 조사를 받침에 맞춰 고른다 */}
        <p className={styles.summary}>
          {summary} 내 영화의 {topShare}%예요.
        </p>

        {/* 와이어프레임에 없는 요소다. 5.9 진입점으로 추가했다 (팀 결정 2026-09-07) */}
        <Button
          variant="secondary"
          onClick={() => void navigate(path.partyPopcornMix(FALLBACK_PARTY_ID))}
        >
          파티 팝콘 섞기 보기
        </Button>
      </div>
    </>
  );
}
