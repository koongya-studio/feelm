/*
 * 5.3 파티 만들기 및 친구 초대
 *
 * 이동표: 5.2 → 5.3 인원 및 친구 설정 완료 · 5.3 → 5.4 파티 시작하기
 *
 * `파티 시작하기`를 누르면 API-049로 파티를 만들고, 고른 친구에게 API-051 초대를 보낸 뒤
 * 5.4로 간다. **초대는 파티를 만든 다음에만 보낼 수 있다** — API-051이 `partyId`를 경로로
 * 받기 때문이다. 그래서 이 화면은 사람을 고르기만 하고 보내는 것은 마지막 한 번이다.
 *
 * 초대할 사람은 **닉네임 검색(API-019)**으로 고른다. 업무 -304는 검색 화면이 없어 초대
 * 자체를 미뤘고 와이어프레임의 `초대 링크 복사` · `QR로 초대하기`는 계약에 없어 잠가
 * 두었다. 업무 -393에서 그 자리를 검색으로 바꿨다. 링크 · QR 경로는 여전히 계약에 없다.
 *
 * `[!]` `1/6명 · 최대 6명까지`는 계약의 정원(4)과 다르다. 계약을 따라 4로 표시한다.
 *
 * `[?]` **일부 초대만 실패할 수 있다.** 파티는 이미 만들어진 뒤라 되돌릴 계약이 없고
 * (API-056 종료뿐이다) 다시 누르면 같은 이름의 파티가 하나 더 생긴다. 그래서 실패한
 * 사람만 알리고 버튼을 `파티로 이동`으로 바꾼다. 파티 안에서 다시 초대할 수 있다(5.8).
 */
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import {
  createParty,
  inviteToParty,
  PARTY_MAX_MEMBERS,
  PARTY_MIN_MEMBERS,
} from '../../../api/parties';
import { getMyProfile, isSearchableNickname, searchUsers } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './PartySetupPage.module.css';
import type { SearchedUser } from '../../../types/user';

/** API-049 `name` 필수, 20자 이하 */
const NAME_MAX_LENGTH = 20;

/** API-019 검색어 상한. 닉네임 형식 상한과 같다 */
const KEYWORD_MAX_LENGTH = 10;

export default function PartySetupPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const size = (location.state as { size?: number } | null)?.size ?? PARTY_MIN_MEMBERS;

  const [myNickname, setMyNickname] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [keyword, setKeyword] = useState('');
  const [results, setResults] = useState<SearchedUser[] | null>(null);
  const [searchError, setSearchError] = useState<string | undefined>();
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

  /** 5.2에서 고른 인원에서 내 자리를 뺀 수. 계약이 정한 정원(4)을 넘길 수 없다 */
  const inviteLimit = Math.min(size, PARTY_MAX_MEMBERS) - 1;
  const waitingSlots = Math.max(0, inviteLimit - invitees.length);
  const locked = createdPartyId !== null;

  const search = () => {
    if (!isSearchableNickname(keyword)) {
      setSearchError('닉네임을 2자 이상 입력해 주세요.');

      return;
    }

    setSearchError(undefined);

    searchUsers(keyword.trim())
      .then((page) => {
        setResults(page.items);
      })
      .catch((cause: unknown) => {
        setResults(null);
        setSearchError(
          cause instanceof ApiError
            ? cause.message
            : '검색하지 못했어요. 잠시 후 다시 시도해 주세요.',
        );
      });
  };

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

  const start = () => {
    // 이미 만든 파티가 있다. 다시 만들면 같은 이름의 파티가 하나 더 생긴다
    if (createdPartyId !== null) {
      void navigate(path.partyAnalyzing(createdPartyId), { replace: true });

      return;
    }

    if (!name.trim()) {
      setError('파티 이름을 입력해 주세요.');

      return;
    }

    setSubmitting(true);
    setError(undefined);

    createParty(name)
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

        void navigate(path.partyAnalyzing(party.partyId), { replace: true });
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
      <ScreenHeader title="파티 만들기" showBack />

      <div className={styles.page}>
        <TextField
          label="파티 이름"
          placeholder="예) 금요 심야 상영회"
          maxLength={NAME_MAX_LENGTH}
          value={name}
          error={error}
          disabled={locked}
          onChange={(event) => {
            setName(event.target.value);
            setError(undefined);
          }}
        />

        <section className={styles.members}>
          <h2 className={styles.label}>현재 참여자</h2>
          <p className={styles.note}>
            {invitees.length + 1}/{PARTY_MAX_MEMBERS}명 · 최대 {PARTY_MAX_MEMBERS}명까지
          </p>

          <ul className={styles.slots}>
            <li className={styles.slot} data-mine="true">
              {myNickname ? `${myNickname} (나)` : '나'}
            </li>

            {invitees.map((invitee) => (
              <li key={invitee.userId} className={styles.slot} data-picked="true">
                <span className={styles.slotName}>{invitee.nickname}</span>

                <button
                  type="button"
                  className={styles.slotRemove}
                  disabled={submitting || locked}
                  onClick={() => toggle(invitee)}
                >
                  빼기
                </button>
              </li>
            ))}

            {Array.from({ length: waitingSlots }, (_, index) => (
              <li key={index} className={styles.slot}>
                대기 중
              </li>
            ))}
          </ul>
        </section>

        <section className={styles.invite}>
          <h2 className={styles.label}>친구 초대</h2>

          <TextField
            label="닉네임으로 친구 찾기"
            placeholder="닉네임을 입력해 주세요"
            maxLength={KEYWORD_MAX_LENGTH}
            value={keyword}
            error={searchError}
            disabled={locked}
            action={{ label: '검색', onClick: search, disabled: locked }}
            onChange={(event) => {
              setKeyword(event.target.value);
              setSearchError(undefined);
            }}
          />

          {results && results.length === 0 ? (
            <p className={styles.note}>그 닉네임으로 시작하는 친구를 찾지 못했어요.</p>
          ) : null}

          {results && results.length > 0 ? (
            <ul className={styles.results}>
              {results.map((user) => {
                const picked = invitees.some((item) => item.userId === user.userId);

                return (
                  <li key={user.userId}>
                    <button
                      type="button"
                      className={styles.result}
                      data-picked={picked}
                      aria-pressed={picked}
                      disabled={submitting || locked}
                      onClick={() => toggle(user)}
                    >
                      <span className={styles.resultName}>{user.nickname}</span>

                      {/*
                       * `PRIVATE`도 결과에 나온다. 가입 기본값이라 빼면 닉네임 초대가
                       * 막힌다(API-019 처리 규칙). 대신 공개 범위를 적어 둔다
                       */}
                      {user.visibility === 'PRIVATE' ? (
                        <span className={styles.resultBadge}>비공개 프로필</span>
                      ) : null}

                      <span className={styles.resultPick}>{picked ? '뺀다' : '초대'}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : null}

          <p className={styles.note}>초대는 파티를 만든 뒤에 전달돼요 · 최대 {inviteLimit}명</p>
        </section>

        {failedInvites.length > 0 ? (
          <p className={styles.note} role="alert">
            파티는 만들었지만 {failedInvites.join(' · ')} 님에게는 초대를 보내지 못했어요. 파티
            안에서 다시 초대할 수 있어요.
          </p>
        ) : null}

        <div className={styles.actions}>
          <Button disabled={submitting} onClick={start}>
            {locked ? '파티로 이동' : '파티 시작하기'}
          </Button>
        </div>
      </div>
    </>
  );
}
