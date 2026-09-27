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
 * K=0이면 API-014의 평가 수로 판정해 현재 후보의 단순 OTT별 작품 수 집계라고 표시한다.
 * 첫 평가 뒤에는 기존 개인 추천 문구로 전환한다. API-068 응답 계약은 바꾸지 않는다.
 *
 * - 어두운 블록은 1위 카드 하나다
 * - 닉네임과 평가 수는 API-014 한 번으로 함께 읽는다. 못 읽으면 인사말은 `나에게`가 되고
 *   분석 근거 줄은 빠진다 — 결과 자체는 API-068이 주므로 화면을 막지 않는다
 * - 구독 여부는 응답의 `subscribed`다. 6.1에서 바꾼 값을 서버가 그대로 안다
 *
 * **추천이 0편인 OTT는 계약이 목록에서 뺀다**(API-068 처리 규칙). EXT-01은 `OTT 서비스 N개를
 * 분석해요`라고 전부를 세는데 결과는 그보다 적게 나와, 한때 한두 곳이 말없이 사라졌다(업무 -771,
 * 2026-09-21 배포본 점검). 같은 기준정보(API-025)와 맞대어 빠진 OTT를 목록 아래에 적는다.
 * 기준정보를 못 받으면 빠진 것을 알 수 없으므로 적지 않는다.
 *
 * `[?]` `현재 구독 중인 OTT와 비교하기`가 무엇을 하는지 적혀 있지 않다. 이동표의 `EXT-03 ⇢ 6.1`은
 *       `OTT 관리로 이동`이 맡으므로, 이 버튼은 **구독 중인 OTT만 남겨 보는 전환**으로 두었다
 * `[?]` `추천 기준 알아보기`가 가는 화면이 없다. 기준 문장을 펼쳐 보여준다
 *       펼친 문장은 화면 맨 아래에 붙어 버튼만 보이는 높이에선 화면 밖에 생긴다. 눌러도 반응이
 *       없어 보였으므로(업무 -966) 펼칠 때 그 문장이 보이게 스크롤한다
 */
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import Button from '../../../components/Button';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getOttRecommendations } from '../../../api/ottRecommendations';
import { getOtts } from '../../../api/otts';
import { getMyProfile } from '../../../api/users';
import { ottFailureView } from '../failure';
import { path } from '../../../routes/paths';
import styles from './OttResultPage.module.css';
import type { MyProfile } from '../../../types/user';
import type { Ott, OttRecommendation } from '../../../types/ott';

export default function OttResultPage() {
  const [result, setResult] = useState<OttRecommendation | null>(null);
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [subscribedOnly, setSubscribedOnly] = useState(false);
  const [criteriaOpen, setCriteriaOpen] = useState(false);
  const criteriaRef = useRef<HTMLParagraphElement>(null);
  /** EXT-01이 센 분석 대상. 결과에서 빠진 OTT를 가려내는 데만 쓴다 */
  const [catalog, setCatalog] = useState<Ott[]>([]);

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

    // 빠진 OTT를 적는 데만 쓰인다. 실패하면 그 줄만 없다
    getOtts()
      .then((list) => {
        if (active) setCatalog(list);
      })
      .catch(() => undefined);

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

  // 펼칠 때만 따라간다. 이미 보이면 `nearest`라 움직이지 않는다
  useEffect(() => {
    if (criteriaOpen)
      criteriaRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
  }, [criteriaOpen]);

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
  const personalized = (profile?.stats.ratingCount ?? 0) > 0;
  const kZero = profile?.stats.ratingCount === 0;
  const omitted = catalog.filter(
    (ott) => !result.items.some((item) => item.ott.ottId === ott.ottId),
  );

  return (
    <>
      {header}

      <div className={styles.page}>
        <div className={styles.intro}>
          <h1 className={styles.title}>
            {personalized
              ? `${who}에게 가장 잘 맞는 OTT예요`
              : kZero
                ? '아직 취향을 알아가는 중이에요'
                : 'OTT마다 볼 수 있는 작품을 모았어요'}
          </h1>
          {profile ? (
            <p className={styles.lead}>
              {personalized
                ? `평가 ${profile.stats.ratingCount}개를 분석했어요`
                : '지금 볼 수 있는 작품 수로 OTT를 정리했어요'}
            </p>
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

          <p className={styles.topCount}>
            {personalized
              ? `${who}이 좋아할 만한 작품이 가장 많아요`
              : '지금 볼 수 있는 작품이 가장 많아요'}
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
                  <span className={styles.rowName}>{item.ott.name}</span>
                  <span className={styles.rowMeta}>
                    {personalized ? '추천' : '볼 수 있는 작품'} {item.matchCount}편
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

        {omitted.length > 0 ? (
          <p className={styles.caption}>
            {omitted.map((ott) => ott.name).join(' · ')}에는 아직 추천할 영화가 없어 순위에서 뺐어요
          </p>
        ) : null}

        <p className={styles.caption}>
          {personalized
            ? '콘텐츠 취향 기준 · 가격과 무관해요'
            : kZero
              ? '볼 수 있는 작품 수 기준 · 인기순이나 가격과 무관해요'
              : '볼 수 있는 작품 수 기준 · 취향 일치 확률이나 인기순이 아니에요'}
        </p>

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
          <p ref={criteriaRef} className={styles.criteria}>
            {personalized
              ? '평가한 영화와 비슷한 작품 중 아직 보지 않은 영화가 OTT마다 몇 편인지 세어 순위를 매겨요.'
              : kZero
                ? '아직 평가한 영화가 없어 지금 볼 수 있는 작품 수로 OTT를 정리했어요. 영화를 평가하면 내 취향에 맞춘 결과로 바뀌어요.'
                : '현재 후보 중 볼 수 있는 영화가 OTT마다 몇 편인지 세어 순위를 매겨요.'}{' '}
            OTT의 전체 작품 수나 요금은 반영하지 않아요.
          </p>
        ) : null}
      </div>
    </>
  );
}
