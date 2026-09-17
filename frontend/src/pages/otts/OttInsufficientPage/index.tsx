/*
 * EXT-05 OTT 분석 데이터 부족
 *
 * 이동표: EXT-01 ⇢ EXT-05 평가 데이터가 부족한 경우 · EXT-05 ⇢ 7.7 영화 평가하러 가기 ·
 * EXT-05 ⇢ 3.1 취향 영화 추가하기
 *
 * 요소는 와이어프레임 그대로다 — `조금만 더 알아가면 OTT를 추천할 수 있어요`,
 * `영화 10편 이상을 평가하면 더 정확하게 분석할 수 있어요`, `현재 진행도 6/10`, 버튼 둘.
 *
 * API-068이 `insufficientData: true`를 주는 경우다. 기준 평가 수는 계약 미정 사항이라
 * 와이어프레임의 10을 쓴다(`mocks/ottRecommendations.ts`).
 *
 * - 진행 막대는 빨강이다. `진행`은 빨강을 쓰는 자리다(`design-concept.md` 2.2)
 * - `영화 평가하러 가기`가 주 동작이다. 평가해야 분석이 열린다
 *
 * `[?]` `취향 영화 추가하기`가 3.1로 간다(이동표). 3.1에서 영화를 더하는 동작은 없다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import { getOttAnalysisBasis } from '../../../api/ottRecommendations';
import { path } from '../../../routes/paths';
import styles from './OttInsufficientPage.module.css';
import type { CSSProperties } from 'react';
import type { OttAnalysisBasis } from '../../../types/unspecified';

export default function OttInsufficientPage() {
  const navigate = useNavigate();
  const [basis, setBasis] = useState<OttAnalysisBasis | null>(null);

  useEffect(() => {
    let active = true;

    getOttAnalysisBasis()
      .then((value) => {
        if (active) setBasis(value);
      })
      // 진행도만 못 그린다. 안내와 버튼은 그대로 쓸 수 있다
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  const required = basis?.requiredRatingCount ?? 10;
  const rated = Math.min(basis?.ratingCount ?? 0, required);

  return (
    <>
      <ScreenHeader title="나에게 맞는 OTT 찾기" showBack fallbackTo={path.settings()} />

      <div className={styles.page}>
        <div className={styles.stage}>
          <h1 className={styles.title}>
            조금만 더 알아가면
            <br />
            OTT를 추천할 수 있어요
          </h1>
          <p className={styles.lead}>
            영화 {required}편 이상을 평가하면
            <br />더 정확하게 분석할 수 있어요
          </p>

          {basis ? (
            <div className={styles.progress}>
              <div className={styles.progressHead}>
                <span>현재 진행도</span>
                <span className={styles.count}>
                  {rated}
                  <span className={styles.total}>/{required}</span>
                </span>
              </div>
              <span
                className={styles.track}
                role="progressbar"
                aria-label="평가 진행도"
                aria-valuenow={rated}
                aria-valuemin={0}
                aria-valuemax={required}
                style={{ '--progress': `${(rated / required) * 100}%` } as CSSProperties}
              >
                <span className={styles.fill} />
              </span>
            </div>
          ) : null}
        </div>

        <div className={styles.actions}>
          <Button onClick={() => void navigate(path.myUnrated())}>영화 평가하러 가기</Button>
          <Button variant="secondary" onClick={() => void navigate(path.film())}>
            취향 영화 추가하기
          </Button>
        </div>
      </div>
    </>
  );
}
