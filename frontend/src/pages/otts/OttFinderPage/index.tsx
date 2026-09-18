/*
 * EXT-01 OTT 취향 분석 시작
 *
 * 이동표
 * - 2.1 → EXT-01 홈 배너 · OTT 추천받기 · 6.1 ⇢ EXT-01 OTT 관리에서 진입
 * - EXT-01 → EXT-02 내 OTT 취향 분석하기 · EXT-01 ⇢ EXT-05 평가 데이터가 부족한 경우
 *
 * 요소는 와이어프레임 그대로다 — `지금까지 감상한 영화와 별점을 바탕으로 분석해요`, 요약 넷
 * (본 영화 · 평가 · 선호 장르 · 자주 본 배우), OTT 배지 여섯과 `OTT 서비스 6개를 분석해요`,
 * `내 OTT 취향 분석하기`, `구독 여부가 아니라 콘텐츠 취향을 기준으로 분석해요`.
 *
 * API-068은 **확장 단계**라 목업이다. 요약은 계약에 없다(`OttAnalysisBasis`).
 * 평가가 기준보다 적으면 분석 버튼이 EXT-05로 간다. 목업에서 그 경우를 보려면
 * `?mock=ott-insufficient`를 붙인다(`api/ottRecommendations.ts`).
 */
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getOttAnalysisBasis } from '../../../api/ottRecommendations';
import { getOtts } from '../../../api/otts';
import { path } from '../../../routes/paths';
import styles from './OttFinderPage.module.css';
import type { Ott } from '../../../types/ott';
import type { OttAnalysisBasis } from '../../../types/unspecified';

export default function OttFinderPage() {
  const navigate = useNavigate();
  const { search } = useLocation();

  const [basis, setBasis] = useState<OttAnalysisBasis | null>(null);
  const [otts, setOtts] = useState<Ott[]>([]);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    Promise.all([getOttAnalysisBasis(), getOtts()])
      .then(([value, list]) => {
        if (!active) return;

        setBasis(value);
        setOtts(list);
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  const header = (
    <ScreenHeader title="나에게 맞는 OTT 찾기" showBack fallbackTo={path.settings()} />
  );

  if (failed) {
    return (
      <>
        {header}
        <StatusView title="분석 정보를 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
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

  if (!basis) {
    return header;
  }

  function start() {
    if (!basis) return;

    // 목업 확인용 질의를 다음 화면에도 넘긴다
    const next =
      basis.ratingCount < basis.requiredRatingCount ? path.ottInsufficient() : path.ottAnalyzing();

    void navigate(`${next}${search}`);
  }

  return (
    <>
      {header}

      <div className={styles.page}>
        <p className={styles.lead}>
          지금까지 감상한 영화와 별점을
          <br />
          바탕으로 분석해요
        </p>

        <dl className={styles.basis}>
          <div className={styles.fact}>
            <dt>본 영화</dt>
            <dd>{basis.watchedCount}편</dd>
          </div>
          <div className={styles.fact}>
            <dt>평가</dt>
            <dd>{basis.ratingCount}개</dd>
          </div>
          <div className={styles.fact}>
            <dt>선호 장르</dt>
            <dd>{basis.favoriteGenres}</dd>
          </div>
          <div className={styles.fact}>
            <dt>자주 본 배우</dt>
            <dd>{basis.frequentActor}</dd>
          </div>
        </dl>

        <div className={styles.otts}>
          <ul className={styles.badges}>
            {otts.map((ott) => (
              <li key={ott.ottId}>
                <OttBadge code={ott.code} name={ott.name} size={36} radius={10} />
              </li>
            ))}
          </ul>
          <p className={styles.caption}>OTT 서비스 {otts.length}개를 분석해요</p>
        </div>

        <div className={styles.actions}>
          <Button onClick={start}>내 OTT 취향 분석하기</Button>
          <p className={styles.caption}>구독 여부가 아니라 콘텐츠 취향을 기준으로 분석해요</p>
        </div>
      </div>
    </>
  );
}
