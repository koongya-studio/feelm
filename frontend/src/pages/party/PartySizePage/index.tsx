/*
 * 5.2 새 파티 인원 설정
 *
 * 이동표: 5.1 → 5.2 새 파티 만들기 · 5.2 → 5.3 인원 및 친구 설정 완료
 *
 * `[!]` **와이어프레임은 2~6인을 고르게 하지만 계약의 정원은 4명이다.**
 * API-049 처리 규칙이 `파티는 파티장 포함 최대 4명`이라고 정했고 응답의 `maxMembers`도
 * 4이며, API-051은 4명에서 `PARTY_FULL`을 준다. 계약을 기준으로 2~4인만 고르게 했다
 * (`screen-spec.md` 2장 계약 우선 원칙). 5인·6인을 살리려면 계약을 먼저 바꿔야 한다.
 *
 * `[!]` 고른 인원을 **요청에 보내지 않는다.** API-049 본문은 `{ name }`뿐이고 정원은
 * 서버가 정한다. 이 화면의 선택은 5.3에서 초대 슬롯을 몇 개 그릴지에만 쓰인다.
 *
 * `[?]` 와이어프레임의 `친구 1` · `친구 2` 자리는 친구를 미리 고르는 것처럼 보이지만,
 * 계약의 초대는 파티를 만든 **뒤에** 하는 일이다(API-051은 `partyId`가 필요하다).
 * 여기서는 자리만 보여주고 실제 초대는 5.3에서 한다.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import { PARTY_MAX_MEMBERS, PARTY_MIN_MEMBERS } from '../../../api/parties';
import { path } from '../../../routes/paths';
import styles from './PartySizePage.module.css';

/** 계약이 정한 범위 안에서만 고르게 한다 */
const SIZES = Array.from(
  { length: PARTY_MAX_MEMBERS - PARTY_MIN_MEMBERS + 1 },
  (_, index) => PARTY_MIN_MEMBERS + index,
);

export default function PartySizePage() {
  const navigate = useNavigate();
  const [size, setSize] = useState(PARTY_MIN_MEMBERS);

  const friendSlots = size - 1;

  return (
    <>
      <ScreenHeader
        title="친구와 같이 볼래요?"
        subtitle="서로의 취향 필름을 합쳐 보세요"
        showBack
      />

      <div className={styles.page}>
        <div className={styles.sizes} role="radiogroup" aria-label="파티 인원">
          {SIZES.map((option) => (
            <button
              key={option}
              type="button"
              role="radio"
              className={styles.size}
              aria-checked={size === option}
              onClick={() => setSize(option)}
            >
              {option}인
            </button>
          ))}
        </div>

        <p className={styles.note}>
          최대 {PARTY_MAX_MEMBERS}명까지 · {PARTY_MIN_MEMBERS}명부터 파티를 시작할 수 있어요
        </p>

        <ul className={styles.slots}>
          <li className={styles.slot} data-mine="true">
            내 필름
          </li>

          {Array.from({ length: friendSlots }, (_, index) => (
            <li key={index} className={styles.slot}>
              친구 {index + 1}
            </li>
          ))}
        </ul>

        <p className={styles.progress}>친구들을 등록해 주세요 (0/{friendSlots})</p>

        <div className={styles.actions}>
          <Button onClick={() => void navigate(path.partySetup(), { state: { size } })}>
            다음
          </Button>
        </div>
      </div>
    </>
  );
}
