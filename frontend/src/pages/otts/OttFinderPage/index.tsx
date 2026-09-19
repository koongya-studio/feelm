/*
 * EXT-01 OTT 취향 분석 시작
 *
 * 이동표
 * - 7.8 → EXT-01 OTT 추천받기. 홈을 없애면서 배너가 7.8로 왔다(업무 -461) — 유일한 입구다
 * - EXT-01 → EXT-02 내 OTT 취향 분석하기
 *
 * 요소는 와이어프레임에서 **하나 줄었다.** 요약 넷(본 영화 · 평가 · 선호 장르 · 자주 본 배우)
 * 가운데 `평가`만 남는다(업무 -563 · 지시서 CR-28). 나머지 셋은 대응 API가 없고, `본 영화`는
 * API-014 `stats`의 `frameCount`가 `ratingCount`와 1:1이라 같은 수를 두 번 적는 셈이 된다.
 *
 * `지금까지 감상한 영화와 별점을 바탕으로 분석해요`, OTT 배지와 `OTT 서비스 N개를 분석해요`,
 * `내 OTT 취향 분석하기`, `구독 여부가 아니라 콘텐츠 취향을 기준으로 분석해요`는 그대로다.
 *
 * **평가 수로 EXT-05를 미리 가르지 않는다.** 기준 평가 수가 계약에 없다. 분석 가능 여부는
 * API-068의 응답이 정하므로 EXT-02가 결과를 받고 나눈다(업무 -563).
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { failureDescription } from '../../../components/StatusView/failure';
import { getOtts } from '../../../api/otts';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './OttFinderPage.module.css';
import type { Ott } from '../../../types/ott';

export default function OttFinderPage() {
  const navigate = useNavigate();

  const [ratingCount, setRatingCount] = useState<number | null>(null);
  const [otts, setOtts] = useState<Ott[]>([]);
  const [failure, setFailure] = useState<unknown>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    Promise.all([getMyProfile(), getOtts()])
      .then(([profile, list]) => {
        if (!active) return;

        setRatingCount(profile.stats.ratingCount);
        setOtts(list);
        setFailed(false);
      })
      .catch((error: unknown) => {
        if (!active) return;

        setFailure(error);
        setFailed(true);
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
        <StatusView title="분석 정보를 불러오지 못했어요" description={failureDescription(failure)}>
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

  if (ratingCount === null) {
    return header;
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
            <dt>평가</dt>
            <dd>{ratingCount}개</dd>
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
          <Button onClick={() => void navigate(path.ottAnalyzing())}>내 OTT 취향 분석하기</Button>
          <p className={styles.caption}>구독 여부가 아니라 콘텐츠 취향을 기준으로 분석해요</p>
        </div>
      </div>
    </>
  );
}
