/*
 * 3.2 100알의 취향
 *
 * 구성은 확정 시안을 따른다(`docs/screens/design-concept.md`). 시안은 이 화면이
 * 한 가지만 말하게 한다. **내 취향이 어느 맛으로 기울었는가.**
 *
 * 팝콘 통 · 기울기 문장 · 맛 범례 · 파티 팝콘 진입
 *
 * 연동 API는 API-041(팝콘 버킷)이다. 맛 이름·색상은 응답이 함께 주는 `displayName`·
 * `color`(기준정보 API-044와 같은 값)를 쓰고 장르명을 하드코딩하지 않는다
 * (screen-spec.md 8.1).
 *
 * 팀 결정 2026-09-09 (업무 -101)
 * - **와이어프레임의 22px 원 100개 격자를 걷어냈다.** 격자는 와이어프레임 표기였을
 *   뿐이고, 취향을 한눈에 보여주는 자리는 3D 팝콘 통이다
 * - 안내 문구(`한 알이 영화 한 편이에요`)와 설명 문단(`8가지 팝콘 맛 · …`)을 뺐다.
 *   시안에 없고, 요약 문장이 큰 제목으로 올라가면서 같은 말을 세 번 하게 된다
 * - 요약 문장을 본문 제목으로 올렸다. 화면이 말하는 한 문장이다
 * - 범례에서 비율(%)을 뺐다. 계약에 없는 값이고 알 수가 이미 같은 말을 한다.
 *   평균 평점은 FR-06 인수 조건이라 남긴다
 *
 * 팝콘 통은 디자인이 만든 3D 통을 구운 스프라이트다. **아이스 화이트 몸통 · 세로 필름 무늬 ·
 * 빨간 로고**다(사용자 결정 2026-09-15, 업무 -322). 블랙 통과 이 화면에 넣어 비교해 골랐다.
 * 벽과 같은 톤이라 화면에 어두운 덩어리를 더하지 않는다. 가로 무늬는 로고가 통 옆면에서 잘려
 * `Feelm Fee…`로 읽혀 쓰지 않는다(팀 결정 2026-09-10).
 * `[?]` 화면 문구가 100알을 전제로 쓰여 있다. 합이 100이 아닐 때의 표현은 미확정이라
 * 수치를 그대로 넣어 문장을 만든다.
 * `[!]` 기준정보(`popcorn_flavors`)가 배포 DB에 아직 없어 실제 응답의 `flavors`가 빈
 * 배열로 온다. 계약은 8개 맛을 항상 준다고 정하므로 화면을 빈 응답에 맞추지 않고,
 * 알이 0개인 기존 빈 상태를 그대로 보여준다(S15P21E106-392).
 * `[?]` 평균 평점 표기 형식은 FR-06이 정하지 않아 `★ 4.4`로 둔다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import { ChevronLeftIcon } from '../../../components/Icon';
import IconButton from '../../../components/IconButton';
import PopcornBucket from '../../../components/PopcornBucket';
import { kernelFlavors } from '../../../components/PopcornBucket/kernels';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { getPopcornBucket } from '../../../api/taste';
import { path } from '../../../routes/paths';
import styles from './PopcornBucketPage.module.css';
import type { PopcornBucket as PopcornBucketData } from '../../../types/taste';

/** 목업 파티. 파티 목록(5.1)이 범위 밖이라 파티를 고르는 화면이 없다 */
const FALLBACK_PARTY_ID = 9001;

/** 시안의 뒤로 가기 원 지름(px). 300px 폭 시안의 31px을 375px 기준으로 환산했다 */
const BACK_BUTTON_SIZE = 39;

/**
 * 통이 그려지는 폭(px). 시안은 232px이지만 8종 범례가 한 칸으로 내려오면
 * 본문이 길어져 CTA가 하단바 뒤로 밀린다. 통을 줄여 한 화면에 맞춘다.
 */
const BUCKET_WIDTH = 196;

export default function PopcornBucketPage() {
  const navigate = useNavigate();

  const [bucket, setBucket] = useState<PopcornBucketData | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getPopcornBucket()
      .then((value) => {
        if (active) {
          setBucket(value);
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
  }, [reloadCount]);

  if (failed) {
    return (
      <>
        <ScreenHeader title="100알의 취향" showBack />

        <StatusView title="팝콘 버킷을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
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

  // 평가가 하나도 없으면 알이 없다. 와이어프레임에 그 상태가 없다
  if (bucket && bucket.totalCount === 0) {
    return (
      <>
        <ScreenHeader title="100알의 취향" showBack />

        <StatusView
          variant="empty"
          title="아직 담긴 팝콘이 없어요"
          description="영화를 평가하면 그 영화의 맛이 한 알씩 담겨요"
        >
          <Button onClick={() => void navigate(path.recommendations())}>추천 영화 보기</Button>
        </StatusView>
      </>
    );
  }

  if (!bucket) {
    return <ScreenHeader title="100알의 취향" showBack />;
  }

  const total = bucket.totalCount;

  // 가장 많이 담긴 맛이 기울기를 말한다. 0알인 맛은 아직 발견하지 않은 맛이라 뺀다
  const found = bucket.flavors.filter((flavor) => flavor.popcornCount > 0);
  const ranked = [...found].sort((a, b) => b.popcornCount - a.popcornCount);
  const leaning = ranked[0] ?? null;

  return (
    <>
      {/*
       * 시안의 헤더는 뒤로 가기 원 하나뿐이다. 제목은 화면에 두지 않지만 h1은
       * 있어야 하므로 숨겨서 남긴다.
       */}
      <VisuallyHidden>
        <h1>100알의 취향</h1>
      </VisuallyHidden>

      <div className={styles.page}>
        <div className={styles.top}>
          <IconButton
            label="뒤로 가기"
            icon={<ChevronLeftIcon />}
            size={BACK_BUTTON_SIZE}
            variant="translucent"
            onClick={() => navigate(-1)}
          />
        </div>

        {/* 팝콘 통. 맛 분포대로 알이 쌓인다 */}
        <div className={styles.bucketArea}>
          <PopcornBucket
            kernelFlavors={kernelFlavors(bucket.flavors)}
            width={BUCKET_WIDTH}
            label={`팝콘 ${total}알`}
          />
        </div>

        <div className={styles.content}>
          <p className={styles.eyebrow}>{total} · KERNELS</p>

          {leaning ? (
            <h2 className={styles.headline}>
              {leaning.displayName} 쪽으로
              <br />
              기울었어요
            </h2>
          ) : null}

          {/*
           * 맛 범례. 색 · 이름 · 알 수 · 평균 평점으로 말한다. 팝콘 8색은 밝은 면에서
           * 전원 1.09~2.17:1이라 면만으로는 안 보인다. 알에 테두리를 둘러 윤곽을
           * 살린다(`.kernel` 참고).
           */}
          <ul className={styles.legend}>
            {bucket.flavors.map((flavor) => (
              <li key={flavor.flavorCode} className={styles.legendItem}>
                <span
                  className={styles.kernel}
                  style={{ background: flavor.color }}
                  aria-hidden="true"
                />
                <span className={styles.legendName}>{flavor.displayName}</span>
                <span className={styles.legendCount}>{flavor.popcornCount}</span>
                {/* 맛 선호도는 감상량과 구분해 표시한다 (FR-06) */}
                {flavor.averageScore === null ? null : (
                  <span className={styles.legendScore}>★ {flavor.averageScore.toFixed(1)}</span>
                )}
              </li>
            ))}
          </ul>

          {/*
           * 이 화면의 주 동작. 검정 알약은 한 화면에 하나뿐이고 그 하나가 여기다
           * (`docs/screens/design-concept.md` 2.4).
           *
           * 와이어프레임에 없는 요소다. 5.9 진입점으로 추가했다 (팀 결정 2026-09-07).
           */}
          <Button onClick={() => void navigate(path.partyPopcornMix(FALLBACK_PARTY_ID))}>
            파티 팝콘 섞기 보기
          </Button>
        </div>
      </div>
    </>
  );
}
