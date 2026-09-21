/*
 * 파티팝콘 흐름 셸. 5.9 팝콘 모으기 · 섞기와 5.5 추천 완성이 함께 쓴다.
 *
 * 이 화면들은 사용자 시안 2026-09-17(업무 -462)에서 **한 흐름**이 됐다. 같은 제목
 * `파티팝콘` 아래 같은 파티원 줄을 두고, 아래쪽 진행 표시가 지금 어디인지 말한다.
 * 이전에는 5.4 → 5.5가 한 흐름이고 5.9는 5.8에서 따로 들어가는 화면이었다. 업무 -773에서
 * 5.4가 5.9에 합쳐져, 진행 표시의 `모으기` · `섞기`는 한 화면 안에서 차례로 켜진다.
 *
 * 두 화면이 쓰므로 화면 폴더가 아니라 `components/`에 둔다
 * (`frontend-structure.md` 5.1).
 *
 * **진행 표시는 기다리는 화면(5.9)에만 있다.** 시안이 5.5에는 두지 않았다 — 결과가
 * 나온 화면에서 단계를 다시 말할 이유가 없고, 그 자리는 추천 3편이 쓴다.
 */
import type { ReactNode } from 'react';
import Avatar from '../Avatar';
import ScreenHeader from '../ScreenHeader';
import styles from './PartyFlowShell.module.css';

/** 진행 표시의 단계. 5.5는 표시하지 않으므로 값이 없다 */
export type PartyFlowStep = 'gather' | 'mix';

const STEPS: { key: PartyFlowStep | 'recommend'; label: string }[] = [
  { key: 'gather', label: '모으기' },
  { key: 'mix', label: '섞기' },
  { key: 'recommend', label: '추천' },
];

/** 파티원 줄의 아바타 크기. 시안은 이름과 한 줄에 놓는다 */
const AVATAR_SIZE = 26;

type Props = {
  /** 파티원 닉네임. 순서는 API-057 · API-050 응답 순서를 그대로 쓴다 */
  members: string[];
  /** 헤더 우측 자리 */
  action?: ReactNode;
  title: ReactNode;
  subtitle?: ReactNode;
  /** 기다리는 동안 보여 줄 문구. `step`과 짝이다 */
  note?: ReactNode;
  /** 지금 단계. 없으면 진행 표시를 그리지 않는다(5.5) */
  step?: PartyFlowStep;
  children: ReactNode;
};

export default function PartyFlowShell({
  members,
  action,
  title,
  subtitle,
  note,
  step,
  children,
}: Props) {
  return (
    <>
      <ScreenHeader title="파티팝콘" showBack action={action} />

      <div className={styles.page}>
        {/*
         * 파티원 줄. 시안은 사진 아바타를 그렸지만 **프로필 이미지 계약이 없다**
         * (업무 -393에서 임의로 만든 `avatarUrl`을 걷어냈다). Avatar의 무채색
         * 자리표시자를 쓴다 — 닉네임에서 밝기를 뽑으므로 서로 구분된다.
         */}
        <div className={styles.members}>
          <ul className={styles.memberList}>
            {members.map((nickname) => (
              <li key={nickname} className={styles.member}>
                <Avatar size={AVATAR_SIZE} nickname={nickname} />
                <span className={styles.memberName}>{nickname}</span>
              </li>
            ))}
          </ul>

          <p className={styles.memberCount}>{members.length}명이 함께해요</p>
        </div>

        <div className={styles.titleBlock}>
          <h1 className={styles.title}>{title}</h1>
          {subtitle ? <p className={styles.subtitle}>{subtitle}</p> : null}
        </div>

        {children}

        {step ? (
          <div className={styles.progress}>
            {/*
             * 기다리는 화면이 스스로 넘어가므로 문구가 바뀌는 것이 유일한 신호다.
             * 보조기술도 같이 읽도록 라이브 영역으로 둔다.
             */}
            <p className={styles.note} role="status">
              {note}
            </p>

            <ol className={styles.steps}>
              {STEPS.map(({ key, label }) => (
                <li
                  key={key}
                  className={styles.step}
                  data-current={key === step ? '' : undefined}
                  aria-current={key === step ? 'step' : undefined}
                >
                  <span className={styles.dot} aria-hidden="true" />
                  <span className={styles.stepLabel}>{label}</span>
                </li>
              ))}
            </ol>
          </div>
        ) : null}
      </div>
    </>
  );
}
