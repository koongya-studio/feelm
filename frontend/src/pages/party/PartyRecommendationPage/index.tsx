/*
 * 5.5 파티 공통 영화 추천 결과
 *
 * 이동표
 * 5.4 → 5.5 공통 취향 분석 완료
 * 5.5 ⇢ 2.4 추천 영화 선택
 * 5.5 ⇢ 5.4 다시 추천받기
 *
 * `다시 추천받기`가 5.4로 되돌아가는 것은 이동표가 정한 것이다. 결과만 갈아 끼우지 않고
 * 분석 장면을 다시 보여준다.
 *
 * `[?]` 상단의 공통 키워드(`스릴러` · `빠른 전개` · `반전` · `범죄`)에 대응하는 필드가
 * API-058에 없다. 5.9 팝콘 섞기가 쓰는 `getPartyMix`(계약 없음, CR-17)에 같은 성격의
 * 값이 있어 그것을 쓴다. 계약이 정해지면 API-058로 합친다.
 */
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import Poster from '../../../components/Poster';
import ScreenHeader from '../../../components/ScreenHeader';
import Section from '../../../components/Section';
import StatusView from '../../../components/StatusView';
import {
  getMyParties,
  getPartyMix,
  getPartyRecommendationReasons,
  getPartyRecommendations,
} from '../../../api/parties';
import { path } from '../../../routes/paths';
import { fitPercent } from './fit';
import type { PartyRecommendationItem } from '../../../types/party';
import styles from './PartyRecommendationPage.module.css';

/** 와이어프레임 5.5의 포스터 크기 */
const POSTER_WIDTH = 56;
const POSTER_HEIGHT = 80;

export default function PartyRecommendationPage() {
  const navigate = useNavigate();
  const { partyId } = useParams();
  const id = Number(partyId);

  const [items, setItems] = useState<PartyRecommendationItem[] | null>(null);
  const [members, setMembers] = useState<string[]>([]);
  const [keywords, setKeywords] = useState<string[]>([]);
  const [reasons, setReasons] = useState<string[]>([]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getPartyRecommendations(id)
      .then((set) => {
        if (!active) return;

        setItems(set.items);
        setFailed(false);
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    getMyParties()
      .then((parties) => {
        const party = parties.find((item) => item.partyId === id);

        if (active && party) setMembers(party.members.map((member) => member.nickname));
      })
      .catch(() => {
        // 참여자 이름이 없어도 추천 목록은 보여준다
      });

    getPartyMix(id)
      .then((mix) => {
        if (active) setKeywords(mix.tags);
      })
      .catch(() => {
        // 공통 키워드는 계약 밖 값이다. 없으면 그 줄만 비운다
      });

    getPartyRecommendationReasons(id)
      .then((value) => {
        if (active) setReasons(value);
      })
      .catch(() => {
        // 같은 이유로 그 섹션만 비운다
      });

    return () => {
      active = false;
    };
  }, [id]);

  if (failed) {
    return (
      <>
        <ScreenHeader title="파티 추천" showBack />
        <div className={styles.page}>
          <StatusView title="추천을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요.">
            <Button variant="text" onClick={() => void navigate(path.partyAnalyzing(id))}>
              다시 추천받기
            </Button>
          </StatusView>
        </div>
      </>
    );
  }

  if (!items) return null;

  return (
    <>
      <ScreenHeader title="우리의 취향 필름이 완성됐어요" showBack />

      <div className={styles.page}>
        {members.length > 0 ? (
          <section className={styles.people}>
            <div className={styles.avatars} aria-hidden="true">
              {members.map((nickname) => (
                <span key={nickname} className={styles.avatar}>
                  {nickname.slice(0, 1)}
                </span>
              ))}
            </div>

            <p className={styles.names}>{members.join(' · ')}</p>
          </section>
        ) : null}

        {keywords.length > 0 ? (
          <div className={styles.keywords}>
            {keywords.map((keyword) => (
              <Chip key={keyword}>{keyword}</Chip>
            ))}
          </div>
        ) : null}

        <section className={styles.results}>
          <h2 className={styles.label}>함께 보기 1위</h2>

          <ul className={styles.list}>
            {items.map((item) => {
              const percent = fitPercent(item.reason.memberFit);

              return (
                <li key={item.recommendationItemId}>
                  <button
                    type="button"
                    className={styles.row}
                    onClick={() => void navigate(path.movieDetail(item.movie.movieId))}
                  >
                    <Poster
                      width={POSTER_WIDTH}
                      height={POSTER_HEIGHT}
                      title={item.movie.title}
                      seed={item.movie.movieId}
                      src={item.movie.posterUrl ?? undefined}
                    />

                    <span className={styles.body}>
                      <span className={styles.title}>{item.movie.title}</span>
                      <span className={styles.summary}>{item.reason.summary}</span>
                    </span>

                    {percent === null ? null : <span className={styles.fit}>일치 {percent}%</span>}
                  </button>
                </li>
              );
            })}
          </ul>
        </section>

        {reasons.length > 0 ? (
          <Section label="왜 같이 추천됐나요?">
            <ul className={styles.reasons}>
              {reasons.map((reason) => (
                <li key={reason} className={styles.reason}>
                  {reason}
                </li>
              ))}
            </ul>
          </Section>
        ) : null}

        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => void navigate(path.partyAnalyzing(id))}>
            다시 추천받기
          </Button>
        </div>
      </div>
    </>
  );
}
