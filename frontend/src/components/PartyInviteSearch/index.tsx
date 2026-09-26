/*
 * 닉네임으로 친구를 찾아 초대하는 자리.
 *
 * 5.3 파티 만들기와 5.8 파티원 팝콘이 함께 쓴다 — 5.3은 파티를 만들기 전에 초대할
 * 사람을 골라 두고, 5.8은 이미 있는 파티에 그 자리에서 초대를 보낸다. 5.8의
 * `친구 초대하기`는 사용자 시안 2026-09-17(업무 -462)이 넣은 버튼이다.
 *
 * **고른 사람을 어떻게 다룰지는 화면이 정한다.** 이 컴포넌트는 검색(API-019)과 결과
 * 목록까지만 맡고, 고른 뒤에 무엇을 하는지는 `onPick`이 받는다.
 *
 * 검색 결과에는 **`PRIVATE` 사용자도 나온다.** 가입 기본값이 `PRIVATE`라 빼면 닉네임
 * 초대가 사실상 막힌다(API-019 처리 규칙). `visibility`로 노출 수위를 정하라는 계약에
 * 따라 `비공개 프로필`만 덧붙이고 다른 정보는 보여주지 않는다.
 *
 * **Enter로도 검색한다**(업무 -771). 한때 `검색` 버튼만 먹었다. 폼으로 감싸지 않는 것은 5.3이
 * 파티 만들기 전체를 담는 화면이라 제출이 섞일 수 있어서다 — 입력칸의 키만 받는다. 한글 조합을
 * 끝내는 Enter(`isComposing`)는 검색이 아니다. 조합 중인 글자가 빠진 채 나간다.
 */
import { useState } from 'react';
import type { ReactNode } from 'react';
import TextField from '../TextField';
import { ApiError } from '../../api/apiError';
import { isSearchableNickname, searchUsers } from '../../api/users';
import styles from './PartyInviteSearch.module.css';
import type { SearchedUser } from '../../types/user';

/** API-019 검색어 상한. 닉네임 형식 상한과 같다 */
const KEYWORD_MAX_LENGTH = 10;

type Props = {
  /** 검색과 선택을 막는다. 보내는 중이거나 더 고를 수 없을 때다 */
  disabled?: boolean;
  /** 이미 고르거나 초대 완료한 사람인지 */
  isPicked?: (user: SearchedUser) => boolean;
  /** 초대를 이미 보낸 결과처럼 다시 선택할 수 없는 사람 */
  isPickDisabled?: (user: SearchedUser) => boolean;
  /** 결과 줄 오른쪽 문구. 고른 상태에 따라 다른 말을 쓸 수 있다 */
  pickLabel: (user: SearchedUser) => string;
  onPick: (user: SearchedUser) => void;
  /** 목록 아래 안내 문구 */
  note?: ReactNode;
};

export default function PartyInviteSearch({
  disabled = false,
  isPicked,
  isPickDisabled,
  pickLabel,
  onPick,
  note,
}: Props) {
  const [keyword, setKeyword] = useState('');
  const [results, setResults] = useState<SearchedUser[] | null>(null);
  const [error, setError] = useState<string | undefined>();

  const search = () => {
    if (!isSearchableNickname(keyword)) {
      setError('닉네임을 2자 이상 입력해 주세요.');

      return;
    }

    setError(undefined);

    searchUsers(keyword.trim())
      .then((page) => {
        setResults(page.items);
      })
      .catch((cause: unknown) => {
        setResults(null);
        setError(
          cause instanceof ApiError
            ? cause.message
            : '검색하지 못했어요. 잠시 후 다시 시도해 주세요.',
        );
      });
  };

  return (
    <>
      <TextField
        label="닉네임으로 친구 찾기"
        placeholder="닉네임을 입력해 주세요"
        maxLength={KEYWORD_MAX_LENGTH}
        value={keyword}
        error={error}
        disabled={disabled}
        action={{ label: '검색', onClick: search, disabled }}
        enterKeyHint="search"
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
            event.preventDefault();
            search();
          }
        }}
        onChange={(event) => {
          setKeyword(event.target.value);
          setError(undefined);
        }}
      />

      {results && results.length === 0 ? (
        <p className={styles.note}>그 닉네임으로 시작하는 친구를 찾지 못했어요.</p>
      ) : null}

      {results && results.length > 0 ? (
        <ul className={styles.results}>
          {results.map((user) => {
            const picked = isPicked?.(user) ?? false;

            return (
              <li key={user.userId}>
                <button
                  type="button"
                  className={styles.result}
                  data-picked={picked}
                  aria-pressed={isPicked ? picked : undefined}
                  disabled={disabled || isPickDisabled?.(user)}
                  onClick={() => onPick(user)}
                >
                  <span className={styles.resultName}>{user.nickname}</span>

                  {user.visibility === 'PRIVATE' ? (
                    <span className={styles.resultBadge}>비공개 프로필</span>
                  ) : null}

                  <span className={styles.resultPick}>{pickLabel(user)}</span>
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}

      {note ? <p className={styles.note}>{note}</p> : null}
    </>
  );
}
