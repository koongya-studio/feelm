/*
 * EXT-03 OTT 추천 결과
 *
 * 이동표
 * - EXT-02 → EXT-03 분석 완료 · EXT-04 ⇢ EXT-03 다른 OTT와 비교하기
 * - EXT-03 → EXT-04 OTT 상세 보기 · EXT-03 ⇢ 6.1 구독 OTT 비교 · OTT 관리로 이동
 *
 * 요소는 와이어프레임 그대로다 — `{닉네임} 님에게 가장 잘 맞는 OTT예요`, `감상 기록 N편과
 * 평가 N개를 분석했어요`, 1위 카드(일치도 · 한 줄 · 좋아할 만한 작품 N편 · 상세 보기),
 * 2~6위 줄, `콘텐츠 취향 일치도 기준 · 가격과 무관해요`, `현재 구독 중인 OTT와 비교하기`,
 * `추천 기준 알아보기 | OTT 관리로 이동`.
 *
 * 순위는 계약대로 `matchCount`(좋아할 만한 미감상 영화 수) 순이다(API-068). 일치도 %는
 * 계약에 없지만 와이어프레임대로 보여준다(팀 결정 2026-09-13).
 *
 * - 어두운 블록은 1위 카드 하나다
 * - 닉네임은 API-014에서 읽는다(실제 호출). 못 읽으면 `나에게`로 쓴다
 *
 * `[?]` `현재 구독 중인 OTT와 비교하기`가 무엇을 하는지 적혀 있지 않다. 이동표의 `EXT-03 ⇢ 6.1`은
 *       `OTT 관리로 이동`이 맡으므로, 이 버튼은 **구독 중인 OTT만 남겨 보는 전환**으로 두었다
 * `[?]` `추천 기준 알아보기`가 가는 화면이 없다. 기준 문장을 펼쳐 보여준다
 * `[?]` `감상 기록 100편과 평가 96개`는 100 기준으로 맞췄다(mocks/ottRecommendations.ts)
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import Button from '../../../components/Button';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getOttAnalysisBasis, getOttRecommendations } from '../../../api/ottRecommendations';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './OttResultPage.module.css';
import type { OttAnalysisBasis, OttRecommendationView } from '../../../types/unspecified';

export default function OttResultPage() {
  const [result, setResult] = useState<OttRecommendationView | null>(null);
  const [basis, setBasis] = useState<OttAnalysisBasis | null>(null);
  const [nickname, setNickname] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [subscribedOnly, setSubscribedOnly] = useState(false);
  const [criteriaOpen, setCriteriaOpen] = useState(false);

  useEffect(() => {
    let active = true;

    Promise.all([getOttRecommendations(), getOttAnalysisBasis()])
      .then(([value, facts]) => {
        if (!active) return;

        setResult(value);
        setBasis(facts);
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    // 인사말에만 쓰인다. 실패해도 결과는 보여준다
    getMyProfile()
      .then((profile) => {
        if (active) setNickname(profile.nickname);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [reloadCount]);

  const header = <ScreenHeader title="OTT 추천 결과" showBack />;

  if (failed) {
    return (
      <>
        {header}
        <StatusView title="추천 결과를 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
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

  if (!result || !basis) {
    return header;
  }

  if (result.items.length === 0) {
    return (
      <>
        {header}
        <StatusView
          variant="empty"
          title="아직 추천할 OTT가 없어요"
          description="영화를 더 평가하면 분석할 수 있어요"
        />
      </>
    );
  }

  const [top, ...rest] = result.items;
  const others = subscribedOnly ? rest.filter((item) => item.subscribed) : rest;
  const who = nickname ? `${nickname} 님` : '나';

  return (
    <>
      {header}

      <div className={styles.page}>
        <div className={styles.intro}>
          <h1 className={styles.title}>{who}에게 가장 잘 맞는 OTT예요</h1>
          <p className={styles.lead}>
            감상 기록 {basis.watchedCount}편과 평가 {basis.ratingCount}개를 분석했어요
          </p>
        </div>

        {/* 1위. 화면에서 유일한 어두운 블록 */}
        <section className={styles.top} aria-label={`1위 ${top.ott.name}`}>
          <div className={styles.topHead}>
            <OttBadge
              code={top.ott.code}
              name={top.ott.name}
              size={44}
              radius={12}
              labelled={false}
            />
            <div className={styles.topName}>
              <p className={styles.topTitle}>{top.ott.name}</p>
              <p className={styles.topMeta}>1위 · {top.subscribed ? '구독 중' : '미구독'}</p>
            </div>
            <p className={styles.topRate}>{top.matchRate}%</p>
          </div>

          <p className={styles.topHeadline}>{top.headline}</p>
          <p className={styles.topCount}>
            {who}이 좋아할 만한 작품 {top.matchCount}편
          </p>

          <Link to={path.ottResultDetail(top.ott.ottId)} className={styles.topLink}>
            상세 보기
          </Link>
        </section>

        <ol className={styles.list}>
          {others.map((item) => (
            <li key={item.ott.ottId}>
              <Link to={path.ottResultDetail(item.ott.ottId)} className={styles.row}>
                <span className={styles.rank}>{result.items.indexOf(item) + 1}</span>
                <OttBadge
                  code={item.ott.code}
                  name={item.ott.name}
                  size={36}
                  radius={10}
                  labelled={false}
                />
                <span className={styles.rowText}>
                  <span className={styles.rowName}>
                    {item.ott.name}
                    <span className={styles.rowRate}>{item.matchRate}%</span>
                  </span>
                  <span className={styles.rowMeta}>
                    {item.genreSummary} | 추천 {item.matchCount}편
                  </span>
                </span>
                <span className={styles.subscription} data-subscribed={item.subscribed}>
                  {item.subscribed ? '구독 중' : '미구독'}
                </span>
              </Link>
            </li>
          ))}
        </ol>

        {subscribedOnly && others.length === 0 ? (
          <p className={styles.caption}>1위 말고 구독 중인 OTT가 없어요</p>
        ) : null}

        <p className={styles.caption}>콘텐츠 취향 일치도 기준 · 가격과 무관해요</p>

        <Button
          variant="secondary"
          aria-pressed={subscribedOnly}
          onClick={() => setSubscribedOnly((value) => !value)}
        >
          {subscribedOnly ? '모든 OTT 보기' : '현재 구독 중인 OTT와 비교하기'}
        </Button>

        <div className={styles.links}>
          <button
            type="button"
            className={styles.textLink}
            aria-expanded={criteriaOpen}
            onClick={() => setCriteriaOpen((value) => !value)}
          >
            추천 기준 알아보기
          </button>
          <span aria-hidden="true">|</span>
          <Link to={path.ottManage()} className={styles.textLink}>
            OTT 관리로 이동
          </Link>
        </div>

        {criteriaOpen ? (
          <p className={styles.criteria}>
            평가한 영화와 비슷한 작품 중 아직 보지 않은 영화가 OTT마다 몇 편인지 세어 순위를 매겨요.
            OTT의 전체 작품 수나 요금은 반영하지 않아요.
          </p>
        ) : null}
      </div>
    </>
  );
}
