/*
 * 5.3 파티 만들기 및 친구 초대
 *
 * 이동표: 5.2 → 5.3 인원 및 친구 설정 완료 · 5.3 → 5.4 파티 시작하기
 *
 * `파티 시작하기`를 누르면 API-049로 파티를 만들고 5.4로 간다. 파티를 만들기 전에는
 * `partyId`가 없어 초대(API-051)를 보낼 수 없으므로, 이 화면의 초대는 파티를 만든 뒤에
 * 이어진다.
 *
 * `[?]` **초대 링크 복사와 QR 초대는 계약에 없다.** API-051은 닉네임 검색(API-019)으로
 * 찾은 `userId`를 받는다. 링크나 QR로 참여하는 경로는 계약에 정의되지 않았다. 와이어프레임의
 * 버튼은 자리만 두고 눌러도 동작하지 않게 했다 — 되는 것처럼 보이면 더 나쁘다.
 *
 * 같은 이유로 **안내 문구도 바꿨다.** 와이어프레임은 `초대받은 친구는 링크로 바로 참여해요`
 * 라고 적었지만, 버튼이 잠긴 채 이 문구만 두면 기능이 있는 것처럼 읽힌다. 계약이 생겨
 * 버튼이 열릴 때 와이어프레임 문구로 되돌린다.
 *
 * `[!]` `1/6명 · 최대 6명까지`는 계약의 정원(4)과 다르다. 계약을 따라 4로 표시한다.
 */
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { createParty, PARTY_MAX_MEMBERS, PARTY_MIN_MEMBERS } from '../../../api/parties';
import { mockMyProfile } from '../../../mocks/user';
import { path } from '../../../routes/paths';
import styles from './PartySetupPage.module.css';

/** API-049 `name` 필수, 20자 이하 */
const NAME_MAX_LENGTH = 20;

export default function PartySetupPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const size = (location.state as { size?: number } | null)?.size ?? PARTY_MIN_MEMBERS;

  const [name, setName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>();

  const waitingSlots = size - 1;

  const start = () => {
    if (!name.trim()) {
      setError('파티 이름을 입력해 주세요.');

      return;
    }

    setSubmitting(true);

    createParty(name)
      .then((party) => {
        void navigate(path.partyAnalyzing(party.partyId), { replace: true });
      })
      .catch(() => {
        setSubmitting(false);
        setError('파티를 만들지 못했어요. 잠시 후 다시 시도해 주세요.');
      });
  };

  return (
    <>
      <ScreenHeader title="파티 만들기" showBack />

      <div className={styles.page}>
        <TextField
          label="파티 이름"
          placeholder="예) 금요 심야 상영회"
          maxLength={NAME_MAX_LENGTH}
          value={name}
          error={error}
          onChange={(event) => {
            setName(event.target.value);
            setError(undefined);
          }}
        />

        <section className={styles.members}>
          <h2 className={styles.label}>현재 참여자</h2>
          <p className={styles.note}>
            1/{PARTY_MAX_MEMBERS}명 · 최대 {PARTY_MAX_MEMBERS}명까지
          </p>

          <ul className={styles.slots}>
            <li className={styles.slot} data-mine="true">
              {mockMyProfile.nickname} (나)
            </li>

            {Array.from({ length: waitingSlots }, (_, index) => (
              <li key={index} className={styles.slot}>
                대기 중
              </li>
            ))}
          </ul>
        </section>

        <section className={styles.invite}>
          <h2 className={styles.label}>친구 초대</h2>

          <div className={styles.inviteButtons}>
            {/* 계약에 없는 경로다. 자리만 두고 동작시키지 않는다 */}
            <Button variant="secondary" disabled>
              초대 링크 복사
            </Button>
            <Button variant="secondary" disabled>
              QR로 초대하기
            </Button>
          </div>

          <p className={styles.note}>
            초대 기능은 준비 중이에요 · {PARTY_MIN_MEMBERS}명부터 시작 가능
          </p>
        </section>

        <div className={styles.actions}>
          <Button disabled={submitting} onClick={start}>
            파티 시작하기
          </Button>
        </div>
      </div>
    </>
  );
}
