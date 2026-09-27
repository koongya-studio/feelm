import { useEffect, useId, useState } from 'react';
import Avatar from '../Avatar';
import PopcornBucket from '../PopcornBucket';
import { kernelCounts } from '../PopcornBucket/kernels';
import { ChevronDownIcon, ChevronUpIcon } from '../Icon';
import { getPopcornFlavors } from '../../api/taste';
import type { PopcornFlavor } from '../../types/taste';
import type { PartyPopcornSnapshot } from '../../types/party';
import styles from './PartyMixCard.module.css';

export const PARTY_POPULARITY_NOTICE =
  '아직 파티원들의 취향 정보가 없어, 인기도를 기준으로 영화를 골랐어요.';

export default function PartyMixCard({ mix }: { mix: PartyPopcornSnapshot }) {
  const [expanded, setExpanded] = useState(false);
  const [flavors, setFlavors] = useState<PopcornFlavor[]>([]);
  const region = useId();
  useEffect(() => {
    let active = true;
    getPopcornFlavors()
      .then((value) => {
        if (active) setFlavors(value);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, []);
  const name = (code: string) => flavors.find((f) => f.code === code)?.displayName ?? code;
  const summary = mix.flavors
    .filter((f) => f.popcornCount > 0)
    .map((f) => name(f.flavorCode))
    .join(' · ');
  const head = (
    <>
      <PopcornBucket width={64} flavors={kernelCounts(mix.flavors)} label="당시 섞인 팝콘" />
      <span className={styles.body}>
        <strong>함께 섞은 팝콘</strong>
        <span>
          {mix.members.length}명 · {mix.totalCount}알
        </span>
        <span>{summary || '아직 담긴 팝콘이 없어요'}</span>
      </span>
    </>
  );
  return (
    <section className={styles.card}>
      {mix.totalCount > 0 ? (
        <button
          type="button"
          className={styles.head}
          aria-expanded={expanded}
          aria-controls={region}
          onClick={() => setExpanded((v) => !v)}
        >
          {head}
          <span aria-hidden="true">{expanded ? <ChevronUpIcon /> : <ChevronDownIcon />}</span>
        </button>
      ) : (
        <div className={styles.head}>{head}</div>
      )}
      {mix.members.length > 0 ? (
        <div className={styles.participants}>
          <p className={styles.participantLabel}>당시 참여 멤버</p>
          <ul className={styles.participantList} aria-label="당시 참여 멤버">
            {mix.members.map((member) => (
              <li key={member.user.userId} className={styles.participant}>
                <Avatar size={28} src={member.user.avatarUrl} nickname={member.user.nickname} />
                <span>{member.user.nickname}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {mix.recommendationMode === 'POPULARITY' ? (
        <p className={styles.notice}>{PARTY_POPULARITY_NOTICE}</p>
      ) : null}
      {expanded && mix.totalCount > 0 ? (
        <div id={region} className={styles.detail}>
          <PopcornBucket
            width={196}
            flavors={kernelCounts(mix.flavors)}
            label={`섞인 팝콘 ${mix.totalCount}알`}
          />
          <ul>
            {mix.flavors.map((f) => (
              <li key={f.flavorCode}>
                {name(f.flavorCode)} <strong>{f.popcornCount}알</strong>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
