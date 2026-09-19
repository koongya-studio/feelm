/*
 * EXT-03 OTT 추천 결과
 *
 * 이동표
 * - EXT-02 → EXT-03 분석 완료 · EXT-04 ⇢ EXT-03 다른 OTT와 비교하기
 * - EXT-03 → EXT-04 OTT 상세 보기 · EXT-03 ⇢ 6.1 구독 OTT 비교 · OTT 관리로 이동
 *
 * 요소는 와이어프레임에서 **일치도 %와 1위 한 줄, 장르 요약이 빠졌다**(업무 -563 · 지시서
 * CR-28). 계약이 유사도나 취향 일치 확률로 응답하지 않기로 정해 둔 값이라 화면도 보여주지
 * 않는다. 1위 카드의 큰 숫자는 그 자리에 들어간 `matchCount` — 좋아할 만한 미감상 영화
 * 편수이며, 순위를 정하는 값도 바로 이것이다(API-068).
 *
 * `{닉네임} 님에게 가장 잘 맞는 OTT예요`, 1위 카드, 2~6위 줄, `콘텐츠 취향 일치도 기준 ·
 * 가격과 무관해요`, `현재 구독 중인 OTT와 비교하기`, `추천 기준 알아보기 | OTT 관리로 이동`은
 * 그대로다.
 *
 * - 어두운 블록은 1위 카드 하나다
 * - 닉네임과 평가 수는 API-014 한 번으로 함께 읽는다. 못 읽으면 인사말은 `나에게`가 되고
 *   분석 근거 줄은 빠진다 — 결과 자체는 API-068이 주므로 화면을 막지 않는다
 * - 구독 여부는 응답의 `subscribed`다. 6.1에서 바꾼 값을 서버가 그대로 안다
 *
 * `[?]` `현재 구독 중인 OTT와 비교하기`가 무엇을 하는지 적혀 있지 않다. 이동표의 `EXT-03 ⇢ 6.1`은
 *       `OTT 관리로 이동`이 맡으므로, 이 버튼은 **구독 중인 OTT만 남겨 보는 전환**으로 두었다
 * `[?]` `추천 기준 알아보기`가 가는 화면이 없다. 기준 문장을 펼쳐 보여준다
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import Button from '../../../components/Button';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getOttRecommendations } from '../../../api/ottRecommendations';
import { getMyProfile } from '../../../api/users';
import { ottFailureView } from '../failure';
import { path } from '../../../routes/paths';
import styles from './OttResultPage.module.css';
import type { MyProfile } from '../../../types/user';
import type { OttRecommendation } from '../../../types/ott';

export default function OttResultPage() {
  const [result, setResult] = useState<OttRecommendation | null>(null);
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [subscribedOnly, setSubscribedOnly] = useState(false);
  const [criteriaOpen, setCriteriaOpen] = useState(false);

  useEffect(() => {
    let active = true;

    getOttRecommendations()
      .then((value) => {
        if (!active) return;

        setResult(value);
        setFailed(false);
      })
      .catch((error: unknown) => {
        if (!active) return;

        setFailure(error);
        setFailed(true);
      });

    // 인사말과 분석 근거에만 쓰인다. 실패해도 결과는 보여준다
    getMyProfile()
      .then((value) => {
        if (active) setProfile(value);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [reloadCount]);

  const header = <ScreenHeader title="OTT 추천 결과" showBack fallbackTo={path.settings()} />;

  if (failed) {
    const view = ottFailureView(failure, '추천 결과를 불러오지 못했어요');

    return (
      <>
        {header}
        <StatusView title={view.title} description={view.description}>
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            {view.retryLabel}
          </Button>
        </StatusView>
      </>
    );
  }

  if (!result) {
    return header;
  }

  // 주소로 바로 들어온 경우에만 닿는다. EXT-02는 빈 결과를 EXT-05로 보낸다
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
  const who = profile ? `${profile.nickname} 님` : '나';

  return (
    <>
      {header}

      <div className={styles.page}>
        <div className={styles.intro}>
          <h1 className={styles.title}>{who}에게 가장 잘 맞는 OTT예요</h1>
          {profile ? (
            <p className={styles.lead}>평가 {profile.stats.ratingCount}개를 분석했어요</p>
          ) : null}
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
            <p className={styles.topMatch}>{top.matchCount}편</p>
          </div>

          <p className={styles.topCount}>{who}이 좋아할 만한 작품이 가장 많아요</p>

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
                  <span className={styles.rowName}>{item.ott.name}</span>
                  <span className={styles.rowMeta}>추천 {item.matchCount}편</span>
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
