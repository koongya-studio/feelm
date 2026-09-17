/*
 * 7.2-A 내 공개 프로필 미리보기
 *
 * 와이어프레임에서 가져온 것 — 이름, 공개된 취향 키워드 · 필름 · 팝콘 버킷.
 *
 * **2.4 영화 상세와 같은 화면이다**(업무 -605, 사용자 지시 2026-09-18). 화면은 `ProfileBoard`가
 * 그린다. 7.2-B와 같은 화면을 쓰되 다른 점은 셋이다.
 * - ⋯ 메뉴(차단 · 신고)가 없다
 * - `공개 한줄평` 구역이 없다. 본인 미리보기의 `reviews`는 항상 빈 배열이다(`types/unspecified.ts`)
 * - 필름을 눌러 프레임 상세(3.1-A)로 갈 수 있다. 내 기록이기 때문이다
 *
 * 이동표: `7.2 ⇢ 7.2-A`로 들어오고 **뒤로 가기로** 편집으로 돌아간다. 이름 위의 한 줄
 * `미리보기 · 다른 사용자에게 이렇게 보여요`가 이 화면이 무엇인지 말한다.
 *
 * **뺀 것**(업무 -459) — 한 줄 소개는 계약에 자리가 없고, `프로필 편집` 버튼은 7.2에서
 * 들어온 화면이라 뒤로 가기와 같은 곳으로 가는 중복이었다. 공개 범위 안내도 그 설정
 * (7.8-B)이 이 화면에 반영되지 않아 설명만 하는 문장이었다.
 */
import { useEffect, useState } from 'react';
import Button from '../../../components/Button';
import ProfileBoard from '../../../components/ProfileBoard';
import StatusView from '../../../components/StatusView';
import { getMyPublicProfile } from '../../../api/users';
import type { PublicProfileView } from '../../../types/unspecified';

export default function ProfilePreviewPage() {
  const [profile, setProfile] = useState<PublicProfileView | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getMyPublicProfile()
      .then((value) => {
        if (active) {
          setProfile(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  if (failed) {
    return (
      <StatusView title="프로필을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
        <Button
          onClick={() => {
            setFailed(false);
            setReloadCount((count) => count + 1);
          }}
        >
          다시 시도
        </Button>
      </StatusView>
    );
  }

  // 데이터가 오기 전에는 아무것도 그리지 않는다(design-concept.md — 로딩 표시를 만들지 않는다)
  if (!profile) {
    return null;
  }

  return (
    <ProfileBoard profile={profile} eyebrow="미리보기 · 다른 사용자에게 이렇게 보여요" frameLinks />
  );
}
