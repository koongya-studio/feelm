/*
 * 팝콘 기계와 섞는 모션 (업무 -462 · -450).
 *
 * 5.9 팝콘 모으기 · 섞기가 쓴다. 파티원의 팝콘이 **통에서 기계로 쏟아져 들어가** 유리 칸 안에서
 * 휘돌고, 배출구로 흘러 아래 통에 **하나의 새 팝콘**으로 담기는 장면이다(사용자 지시
 * 2026-09-17).
 *
 * 기계는 디자인이 만든 3D 렌더 한 장이다(`src/assets/party`). 버건디 몸통 · 유리 칸 ·
 * 스테인리스 케틀이고, 받은 PNG(1145 × 1374, 1.1MB)에서 **투명 여백을 잘라내고 긴 변
 * 780px WebP로 옮겼다**(74KB). 스플래시와 같은 처리다 — 화면 최대 폭이 260px이라
 * 780px이 DPR 3까지 덮는다. 여백을 자른 것은 아래 자리 비율을 그림에 맞추기 위해서다.
 *
 * 한 장이라 알을 유리 뒤로 숨길 수 없어 **알을 기계 위에 그린다** — 유리가 투명하니
 * 앞에 놓인 알도 안에 있는 것으로 읽힌다. `PopcornBucket`이 같은 이유로 더미를 입구
 * 안에서 시작한다.
 *
 * 단계는 화면이 넘긴다. 이 컴포넌트는 지금 단계의 그림만 그린다.
 *
 * - `idle`    파티원 통이 위에서 내려와 기계 위에 모인다(모으기, 업무 -773). 아래 통은 비어 있다
 * - `pouring` 통이 기울고, 알이 통에서 케틀로 줄지어 들어간다
 * - `mixing`  유리 칸 안에서 알이 휘돌고 기계가 떤다
 * - `filling` 배출구에서 알이 흘러내리고 아래 통이 차오른다
 * - `done`    아래 통이 섞인 팝콘으로 차 있다
 *
 * **난수를 쓰지 않는다.** 알의 자리 · 경로 · 시차는 index로만 정하므로 같은 파티는
 * 언제나 같은 장면이 된다(`design-concept.md` 2.10과 같은 규칙).
 *
 * 움직임을 줄여 달라고 한 사용자에게는 모션을 걷어내고 **결과 상태**를 바로 보여준다.
 * 장면이 아니라 결과가 이 화면이 말하려는 것이다.
 */
import type { CSSProperties } from 'react';
import machineSprite from '../../assets/party/popcorn-machine.webp';
import PopcornBucket from '../PopcornBucket';
import { kernelSprite } from '../PopcornBucket/sprites';
import styles from './PopcornMachine.module.css';
import type { FlavorCount } from '../PopcornBucket/kernels';

/** 모션 단계. 순서대로 진행한다 */
export type MachinePhase = 'idle' | 'pouring' | 'mixing' | 'filling' | 'done';

/** 파티원 한 명이 붓는 알 수. 줄지어 들어가는 것으로 보일 만큼이다 */
const FEED_PER_MEMBER = 7;

/** 유리 칸 안에서 도는 알 수. 더 넣으면 알이 서로를 덮어 뭉개진다 */
const CHURN_COUNT = 14;

/** 배출구에서 흘러내리는 알 수. 줄이면 한 알씩 뚝뚝 떨어져 흐르는 것으로 안 보인다 */
const FALL_COUNT = 10;

/** 아래 통이 그려지는 폭(px) */
const BUCKET_WIDTH = 132;

/** 파티원 통이 그려지는 폭(px) */
const MEMBER_BUCKET_WIDTH = 56;

/**
 * 담기는 중일 때 통이 차 있는 정도. 완성에서 1이 된다.
 *
 * 0.45로 두었을 때는 더미가 입구 안에 묻혀 빈 통과 구분되지 않았다.
 */
const FILLING_LEVEL = 0.62;

type Member = {
  nickname: string;
  flavors: FlavorCount[];
};

type Props = {
  /** 기계에 담기는 파티원. 순서는 API-057 응답 순서다 */
  members: Member[];
  /** 섞은 결과. 아래 통에 담긴다 */
  mixed: FlavorCount[];
  phase: MachinePhase;
};

/**
 * 알이 많은 맛부터 코드를 늘어놓는다.
 *
 * 모션의 알은 **그 파티에 실제로 담긴 맛**이어야 한다. 아무 맛이나 띄우면 아래 통에
 * 담기는 결과와 색이 어긋난다.
 */
function flavorOrder(flavors: FlavorCount[]): string[] {
  return [...flavors]
    .filter((flavor) => flavor.count > 0)
    .sort((a, b) => b.count - a.count)
    .map((flavor) => flavor.code);
}

/**
 * 차례로 쓸 맛 그림. 맛이 없으면 기본 맛으로 그린다.
 *
 * 기준정보(API-044)가 비어 오거나 아직 아무 알도 없는 파티가 있어(맛 적재 전) 빈
 * 목록이 들어올 수 있다.
 */
function kernelAt(order: string[], index: number): string {
  return kernelSprite(order.length > 0 ? order[index % order.length] : 'FLAVOR_A');
}

/**
 * 유리 칸 안에서 알 하나가 도는 궤도.
 *
 * **알은 케틀을 돌아야 한다.** 제자리에서 흔들리기만 하면 떠 있는 것으로 보이고
 * 섞이는 것으로 읽히지 않는다(2026-09-17 사용자 지적). 그래서 케틀 가운데를 축으로
 * 도는 궤도에 올린다 — 궤도 껍데기가 돌고, 알은 그 위에서 반대로 돌아 자기 자신은
 * 바로 선 채로 옮겨 다닌다.
 *
 * 지름 · 높이 · 주기를 순번으로 흩어 놓는다. 같으면 열네 알이 한 줄로 돌아
 * 회전목마가 된다.
 *
 * 반지름은 **링의 크기로** 준다. `translateX(%)`는 제 몸 크기를 기준으로 해서
 * 알 크기의 몇 %가 되어 버리고, 알이 가운데로 몰린다(한 번 그렇게 나왔다).
 */
function orbitStyle(index: number, count: number): CSSProperties {
  // 유리 칸을 기준으로 한 링의 지름
  const size = 48 + (index % 3) * 26;
  // 뒤로 도는 알을 섞는다. 한 방향이면 무리가 한 몸으로 돌아 보인다
  const way = index % 4 === 0 ? -1 : 1;

  return {
    '--size': `${size}%`,
    '--lift': `${((index % 5) - 2) * 6}px`,
    '--dur': `${1150 + (index % 4) * 220}ms`,
    // 궤도 위 출발 자리. 순번대로 벌려 한쪽에 몰리지 않게 한다
    '--from': `${way * (index / count) * 360}deg`,
    '--way': `${way}`,
    '--delay': `${index * 40}ms`,
  } as CSSProperties;
}

/** 통의 이름. 세 상태가 달라야 보조기술이 같은 장면을 두 번 말하지 않는다 */
function bucketLabel(level: number): string {
  if (level >= 1) return '파티원의 팝콘을 섞은 통';
  if (level <= 0) return '아직 비어 있는 통';

  return '팝콘이 담기는 중인 통';
}

export default function PopcornMachine({ members, mixed, phase }: Props) {
  const mixedOrder = flavorOrder(mixed);
  /*
   * 붓는 동안에는 통에 팝콘을 남긴다. 기울 때 통이 이미 비어 있으면 쏟아지는 것이
   * 아니라 빈 컵이 넘어지는 것으로 보인다. 비우는 것은 기계가 섞기 시작할 때다.
   */
  const poured = phase !== 'idle' && phase !== 'pouring';
  const level = phase === 'done' ? 1 : phase === 'filling' ? FILLING_LEVEL : 0;

  return (
    <div className={styles.machine} data-phase={phase}>
      {/*
       * 파티원 통. 기계 위에 나란히 놓였다가 `pouring`에서 기울며 알을 붓는다.
       * 파티는 최대 4명이므로(FR-20) 한 줄에 다 들어간다.
       */}
      <div className={styles.feeders}>
        {members.map((member, index) => (
          <div
            key={member.nickname}
            className={styles.feeder}
            style={{ '--index': index } as CSSProperties}
          >
            <PopcornBucket
              flavors={poured ? [] : member.flavors}
              width={MEMBER_BUCKET_WIDTH}
              label={`${member.nickname}의 팝콘`}
            />
          </div>
        ))}
      </div>

      {/*
       * 붓는 알. 각 파티원의 통 자리에서 출발해 케틀로 들어간다. 출발 자리는 통이 선
       * 순서에서 나오므로(`--from`) 통마다 다른 줄이 된다.
       */}
      <div className={styles.feed} aria-hidden="true">
        {members.flatMap((member, memberIndex) =>
          Array.from({ length: FEED_PER_MEMBER }, (_, index) => (
            <span
              key={`${member.nickname}-${index}`}
              className={styles.kernel}
              style={
                {
                  '--from': `${(memberIndex - (members.length - 1) / 2) * 26}%`,
                  '--delay': `${memberIndex * 90 + index * 110}ms`,
                  '--sprite': `url(${kernelAt(flavorOrder(member.flavors), index)})`,
                } as CSSProperties
              }
            />
          )),
        )}
      </div>

      <img className={styles.body} src={machineSprite} alt="" />

      {/*
       * 유리 칸 안에서 케틀을 돌며 섞이는 알.
       *
       * 껍데기(`orbit`)가 돌고 알은 그 위에서 반대로 돌아 바로 선 채로 옮겨 다닌다.
       * 뒤쪽을 지날 때 조금 작아져 앞뒤가 생긴다.
       */}
      <div className={styles.churn} aria-hidden="true">
        {Array.from({ length: CHURN_COUNT }, (_, index) => (
          <span key={index} className={styles.orbit} style={orbitStyle(index, CHURN_COUNT)}>
            <span
              className={styles.kernel}
              style={{ '--sprite': `url(${kernelAt(mixedOrder, index)})` } as CSSProperties}
            />
          </span>
        ))}
      </div>

      {/* 배출구에서 아래 통으로 흘러내리는 알 */}
      <div className={styles.chute} aria-hidden="true">
        {Array.from({ length: FALL_COUNT }, (_, index) => (
          <span
            key={index}
            className={styles.kernel}
            style={
              {
                '--drift': `${((index % 3) - 1) * 9}px`,
                '--delay': `${index * 85}ms`,
                '--sprite': `url(${kernelAt(mixedOrder, index)})`,
              } as CSSProperties
            }
          />
        ))}
      </div>

      {/*
       * 아래 통. 담기는 동안 차오르고 완성에서 가득 찬다.
       *
       * 통의 이름도 그때그때 말이 달라야 보조기술이 같은 장면을 두 번 말하지 않는다.
       */}
      <div className={styles.result}>
        <PopcornBucket
          flavors={mixed}
          fill={level}
          width={BUCKET_WIDTH}
          label={bucketLabel(level)}
        />
      </div>
    </div>
  );
}
