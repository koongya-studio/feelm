/*
 * 5.2 새 파티 인원 설정
 *
 * 이동표: 5.1 → 5.2 새 파티 만들기 · 5.2 → 5.3 인원 및 친구 설정 완료
 *
 * `[!]` **와이어프레임은 2~6인을 고르게 하지만 계약상 최대 정원은 4명이다.**
 * API-049에서 2·3·4명 중 고른 정원을 저장하고 API-051은 그 정원에서 `PARTY_FULL`을 준다.
 * 계약을 기준으로 2~4인만 고르게 했다
 * (`screen-spec.md` 2장 계약 우선 원칙). 5인·6인을 살리려면 계약을 먼저 바꿔야 한다.
 *
 * 고른 인원은 5.3의 초대 슬롯 수와 API-049의 정원에 함께 사용한다.
 *
 * `[?]` 와이어프레임의 `친구 1` · `친구 2` 자리는 친구를 미리 고르는 것처럼 보이지만,
 * 계약의 초대는 파티를 만든 **뒤에** 하는 일이다(API-051은 `partyId`가 필요하다).
 * 여기서는 자리만 보여주고 실제 초대는 5.3에서 한다.
 *
 * **그래서 이 화면은 친구를 받지 않는다고 분명히 말한다**(업무 -697). 한때 `친구들을 등록해
 * 주세요 (0/N)`이라고 적고 빈 자리를 입력 칸처럼 그려, 여기서 등록해야 하는 것처럼 읽혔다.
 * 지금은 `다음 화면에서 친구 N명을 초대해요`라고 적고, 자리는 누를 수 없는 동그라미 미리보기다.
 * 인원 선택 → 안내 → 자리 미리보기 → 다음이 위에서 아래로 이어지고, 버튼을 화면 바닥으로
 * 밀지 않아 가운데가 비지 않는다.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import { CheckIcon, PersonIcon } from '../../../components/Icon';
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
        fallbackTo={path.party()}
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
              {size === option ? (
                <span className={styles.check}>
                  <CheckIcon />
                </span>
              ) : null}
              {option}인
            </button>
          ))}
        </div>

        <p className={styles.note}>
          최대 {PARTY_MAX_MEMBERS}명까지 · {PARTY_MIN_MEMBERS}명부터 파티를 시작할 수 있어요
        </p>

        <section className={styles.preview} aria-labelledby="party-size-invite">
          {/* 인원을 바꾸면 곧바로 바뀐다. 읽기 도구에도 바뀐 수를 알린다 */}
          <p id="party-size-invite" className={styles.invite} aria-live="polite">
            다음 화면에서 친구 {friendSlots}명을 초대해요
          </p>

          <ul className={styles.slots}>
            <li className={styles.slot} data-mine="true">
              <span className={styles.slotMark} aria-hidden="true">
                나
              </span>
              <span className={styles.slotLabel}>내 필름</span>
            </li>

            {Array.from({ length: friendSlots }, (_, index) => (
              <li key={index} className={styles.slot}>
                <span className={styles.slotMark} aria-hidden="true">
                  <PersonIcon />
                </span>
                <span className={styles.slotLabel}>친구 {index + 1}</span>
              </li>
            ))}
          </ul>
        </section>

        <div className={styles.actions}>
          <Button
            variant="brand"
            onClick={() => void navigate(path.partySetup(), { state: { size } })}
          >
            다음
          </Button>
        </div>
      </div>
    </>
  );
}
