/*
 * 5.3 파티 만들기 및 친구 초대
 *
 * 이동표: 5.2 → 5.3 인원 및 친구 설정 완료 · 5.3 → 5.9 파티 시작하기
 *
 * `파티 시작하기`를 누르면 API-049로 파티를 만들고, 고른 친구에게 API-051 초대를 보낸 뒤
 * 5.9로 간다. **초대는 파티를 만든 다음에만 보낼 수 있다** — API-051이 `partyId`를 경로로
 * 받기 때문이다. 그래서 이 화면은 사람을 고르기만 하고 보내는 것은 마지막 한 번이다.
 *
 * 초대할 사람은 **닉네임 검색(API-019)**으로 고른다. 검색 자리는 5.8도 쓰게 되어
 * `components/PartyInviteSearch`로 옮겼다(업무 -462). 업무 -304는 검색 화면이 없어 초대
 * 자체를 미뤘고 와이어프레임의 `초대 링크 복사` · `QR로 초대하기`는 계약에 없어 잠가
 * 두었다. 업무 -393에서 그 자리를 검색으로 바꿨다. 링크 · QR 경로는 여전히 계약에 없다.
 *
 * `[!]` `1/6명 · 최대 6명까지`는 계약의 정원(4)과 다르다. 5.2에서 고른 인원을 상한으로
 * 보여준다(업무 -610) — 2인을 골랐는데 `1/4명`이 나오던 것을 고쳤다. 고른 정원을
 * API-049에 보내므로 생성된 파티도 같은 정원을 가진다.
 *
 * `[?]` **일부 초대만 실패할 수 있다.** 파티는 이미 만들어진 뒤라 되돌릴 계약이 없고
 * (API-056 종료뿐이다) 다시 누르면 같은 이름의 파티가 하나 더 생긴다. 그래서 실패한
 * 사람만 알리고 버튼을 `파티로 이동`으로 바꾼다. 파티 안에서 다시 초대할 수 있다(5.8).
 */
import { useEffect, useId, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import PartyInviteSearch from '../../../components/PartyInviteSearch';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import {
  createParty,
  inviteToParty,
  PARTY_MAX_MEMBERS,
  PARTY_MIN_MEMBERS,
} from '../../../api/parties';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './PartySetupPage.module.css';
import type { SearchedUser } from '../../../types/user';

/** API-049 `name` 필수, 20자 이하 */
const NAME_MAX_LENGTH = 20;

export default function PartySetupPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const size = (location.state as { size?: number } | null)?.size ?? PARTY_MIN_MEMBERS;

  const nameLabelId = useId();
  const searchId = useId();
  const searchRef = useRef<HTMLElement>(null);
  const [searchOpen, setSearchOpen] = useState(false);

  const openSearch = () => {
    setSearchOpen(true);
    searchRef.current?.querySelector('input')?.focus();
  };

  const [myNickname, setMyNickname] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [invitees, setInvitees] = useState<SearchedUser[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>();
  /** 만들어졌지만 초대 일부가 실패한 파티. 다시 만들지 않으려고 번호를 붙잡아 둔다 */
  const [createdPartyId, setCreatedPartyId] = useState<number | null>(null);
  const [failedInvites, setFailedInvites] = useState<string[]>([]);

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((profile) => {
        if (active) setMyNickname(profile.nickname);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  /** 5.2에서 고른 인원에서 내 자리를 뺀 수. 허용 최대 정원은 4명이다 */
  const inviteLimit = Math.min(size, PARTY_MAX_MEMBERS) - 1;
  const waitingSlots = Math.max(0, inviteLimit - invitees.length);
  const locked = createdPartyId !== null;

  const toggle = (user: SearchedUser) => {
    setError(undefined);

    setInvitees((current) => {
      if (current.some((item) => item.userId === user.userId)) {
        return current.filter((item) => item.userId !== user.userId);
      }

      if (current.length >= inviteLimit) {
        setError(`초대는 ${inviteLimit}명까지 고를 수 있어요.`);

        return current;
      }

      return [...current, user];
    });
  };

  /*
   * 파티는 나까지 2명부터다(`PARTY_MIN_MEMBERS`, 5.2의 안내와 같은 값). 초대 없이 시작하면
   * 혼자인 파티가 만들어져 5.9 이후가 할 일이 없다. 버튼을 잠그고 눌러도 막는다(업무 -610).
   */
  const needsInvitee = !locked && invitees.length + 1 < PARTY_MIN_MEMBERS;

  const start = () => {
    // 두 번 눌러도 파티가 둘 생기지 않는다
    if (submitting) {
      return;
    }

    // 이미 만든 파티가 있다. 다시 만들면 같은 이름의 파티가 하나 더 생긴다
    if (createdPartyId !== null) {
      void navigate(path.partyPopcornMix(createdPartyId), { replace: true });

      return;
    }

    if (!name.trim()) {
      setError('파티 이름을 입력해 주세요.');

      return;
    }

    if (needsInvitee) {
      setError(`함께 볼 친구를 ${PARTY_MIN_MEMBERS - 1}명 이상 초대해 주세요.`);

      return;
    }

    setSubmitting(true);
    setError(undefined);

    createParty(name, size)
      .then(async (party) => {
        const failed: string[] = [];

        // 서버가 사용자 단위로 직렬화하므로(API-051 처리 규칙) 한 사람씩 보낸다
        for (const invitee of invitees) {
          try {
            await inviteToParty(party.partyId, invitee.userId);
          } catch {
            failed.push(invitee.nickname);
          }
        }

        if (failed.length > 0) {
          setSubmitting(false);
          setCreatedPartyId(party.partyId);
          setFailedInvites(failed);

          return;
        }

        void navigate(path.partyPopcornMix(party.partyId), { replace: true });
      })
      .catch((cause: unknown) => {
        setSubmitting(false);
        setError(
          cause instanceof ApiError
            ? cause.message
            : '파티를 만들지 못했어요. 잠시 후 다시 시도해 주세요.',
        );
      });
  };

  return (
    <>
      <ScreenHeader title="파티 만들기" showBack fallbackTo={path.party()} />

      <div className={styles.page}>
        <section className={styles.members}>
          <h2 id={nameLabelId} className={styles.label}>
            파티 이름
          </h2>
          <TextField
            label="파티 이름"
            aria-labelledby={nameLabelId}
            placeholder="예) 금요 심야 상영회"
            maxLength={NAME_MAX_LENGTH}
            value={name}
            error={error}
            disabled={locked || submitting}
            onChange={(event) => {
              setName(event.target.value);
              setError(undefined);
            }}
          />
        </section>

        <section className={styles.members} aria-label="함께할 친구">
          <div className={styles.memberHeading}>
            <h2 className={styles.label}>함께할 친구</h2>
            <span className={styles.memberCount} aria-live="polite">
              {invitees.length + 1}/{size}명
            </span>
          </div>

          <ul className={styles.slots}>
            <li className={styles.slot} data-mine="true">
              <span className={styles.slotName}>
                {myNickname ?? '나'}
                <span className={styles.slotMeta}>나 · 파티장</span>
              </span>
            </li>

            {invitees.map((invitee) => (
              <li key={invitee.userId} className={styles.slot} data-picked="true">
                <span className={styles.slotName}>{invitee.nickname}</span>
                <button
                  type="button"
                  className={styles.slotRemove}
                  aria-label={`${invitee.nickname} 선택 해제`}
                  disabled={submitting || locked}
                  onClick={() => toggle(invitee)}
                >
                  <span aria-hidden="true">×</span>
                </button>
              </li>
            ))}

            {Array.from({ length: waitingSlots }, (_, index) => (
              <li key={`empty-${index}`}>
                <button
                  type="button"
                  className={styles.slotAdd}
                  disabled={submitting || locked}
                  aria-expanded={searchOpen}
                  aria-controls={searchId}
                  onClick={openSearch}
                >
                  <span aria-hidden="true">＋</span> 친구 추가
                </button>
              </li>
            ))}
          </ul>
          <p className={styles.note}>
            나를 포함해 최대 {size}명 · 초대는 파티를 만들면 전달돼요.
            {!searchOpen ? ' 빈자리를 눌러 친구를 골라 주세요.' : ''}
          </p>

          <section
            id={searchId}
            ref={searchRef}
            className={styles.invite}
            aria-label="초대할 친구 찾기"
            hidden={!searchOpen}
          >
            {searchOpen ? (
              <>
                <h2 className={styles.label}>초대할 친구 찾기</h2>
                <PartyInviteSearch
                  autoFocus
                  disabled={locked || submitting}
                  isPicked={(user) => invitees.some((item) => item.userId === user.userId)}
                  isPickDisabled={(user) =>
                    invitees.length >= inviteLimit &&
                    !invitees.some((item) => item.userId === user.userId)
                  }
                  pickLabel={(user) =>
                    invitees.some((item) => item.userId === user.userId) ? '✓ 선택됨' : '+ 추가'
                  }
                  onPick={toggle}
                />
                <p className={styles.note} role="status">
                  {invitees.length >= inviteLimit
                    ? '모든 자리를 채웠어요. 선택한 친구를 다시 누르면 해제돼요.'
                    : '선택 즉시 반영돼요. 다른 친구도 계속 검색할 수 있어요.'}
                </p>
              </>
            ) : null}
          </section>
          <p className={styles.note}>{PARTY_MIN_MEMBERS - 1}명 이상 초대해야 시작할 수 있어요.</p>
        </section>

        {failedInvites.length > 0 ? (
          <p className={styles.note} role="alert">
            파티는 만들었지만 {failedInvites.join(' · ')} 님에게는 초대를 보내지 못했어요. 파티
            안에서 다시 초대할 수 있어요.
          </p>
        ) : null}

        <div className={styles.actions}>
          <Button variant="brand" disabled={submitting || needsInvitee} onClick={start}>
            {locked
              ? '파티로 이동'
              : needsInvitee
                ? '친구를 1명 이상 선택해 주세요'
                : `친구 ${invitees.length}명과 파티 만들기`}
          </Button>
        </div>
      </div>
    </>
  );
}
