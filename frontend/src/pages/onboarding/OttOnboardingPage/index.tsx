/*
 * 1.2 구독 OTT 초기 설정
 *
 * 요소와 문구는 와이어프레임 1.2 그대로다(screen-spec.md 4장 1.2).
 * OTT 목록은 API-025로 조회한다. **지원 OTT 5종과 각 `ottId`는 서버가 정한다** —
 * 화면은 응답의 식별자를 그대로 쓰고 개수도 응답을 따른다(업무 S15P21E106-379).
 *
 * `구독 중인 OTT가 없어요`는 빈 배열로 저장한다(API-027 — 빈 배열이면 전체 해제).
 *
 * `[?]` 선택 0개일 때 주 버튼 활성 여부는 미확정이다. 와이어프레임이 `선택 완료 (2개)`와
 * `구독 중인 OTT가 없어요`를 별도 버튼으로 나눠 두었으므로, 0개면 주 버튼을 비활성으로
 * 두고 보조 버튼으로 진행하게 했다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import OttBadge from '../../../components/OttBadge';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import SelectableTile from '../../../components/SelectableTile';
import { ApiError } from '../../../api/apiError';
import { getOtts, setMyOttIds } from '../../../api/otts';
import { useOnboarding } from '../../../contexts/onboardingContext';
import { path } from '../../../routes/paths';
import { ottBadgeLetter } from '../../../utils/ott';
import styles from './OttOnboardingPage.module.css';
import type { Ott } from '../../../types/ott';

export default function OttOnboardingPage() {
  const navigate = useNavigate();
  const { tasteSubmitted, completeOtt } = useOnboarding();

  const [otts, setOtts] = useState<Ott[]>([]);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [selected, setSelected] = useState<number[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    getOtts()
      .then((value) => {
        setOtts(value);
        setFailed(false);
      })
      .catch(() => {
        setFailed(true);
      });
  }, [reloadCount]);

  // 1.1을 거치지 않고 이 화면을 직접 열면 되돌린다 (frontend-structure.md 6.3)
  useEffect(() => {
    if (!tasteSubmitted) {
      void navigate(path.onboardingTaste(), { replace: true });
    }
  }, [tasteSubmitted, navigate]);

  function toggle(ottId: number) {
    setSelected((previous) =>
      previous.includes(ottId)
        ? previous.filter((selectedId) => selectedId !== ottId)
        : [...previous, ottId],
    );
  }

  async function submit(ottIds: number[]) {
    setSubmitting(true);
    setFailure(null);

    try {
      /*
       * 서버가 저장한 결과를 다음 화면으로 넘긴다. 계약이 중복을 제거하고 돌려주므로
       * 보낸 값이 아니라 받은 값이 구독 상태다(API-027 처리 규칙).
       */
      const saved = await setMyOttIds(ottIds);

      completeOtt(saved);
      void navigate(path.onboardingComplete());
    } catch (error) {
      setFailure(
        error instanceof ApiError
          ? error.message
          : '저장하지 못했어요. 잠시 후 다시 시도해 주세요.',
      );
      setSubmitting(false);
    }
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="구독 중인 OTT가 있으신가요?" align="center" />

        <StatusView title="OTT 목록을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  return (
    <>
      <ScreenHeader
        title={
          <>
            구독 중인 OTT가
            <br />
            있으신가요?
          </>
        }
        subtitle="선택한 OTT에서 볼 수 있는 영화를 추천해 드려요"
      />

      <div className={styles.page}>
        <ul className={styles.list}>
          {otts.map((ott) => {
            const isSelected = selected.includes(ott.ottId);

            return (
              <li key={ott.ottId}>
                <SelectableTile
                  label={ott.name}
                  selected={isSelected}
                  onToggle={() => toggle(ott.ottId)}
                  leading={
                    <OttBadge
                      letter={ottBadgeLetter(ott.code)}
                      name={ott.name}
                      logoUrl={ott.logoUrl}
                      size={38}
                      fontSize={16}
                      radius={10}
                      variant={isSelected ? 'selected' : 'unselected'}
                      // 타일이 이미 OTT 이름을 읽으므로 배지는 장식으로 둔다
                      labelled={false}
                    />
                  }
                />
              </li>
            );
          })}
        </ul>

        <div className={styles.actions}>
          {failure ? (
            <p className={styles.failure} role="alert">
              {failure}
            </p>
          ) : null}

          <Button
            disabled={selected.length === 0 || submitting}
            onClick={() => void submit(selected)}
          >
            선택 완료 ({selected.length}개)
          </Button>

          <Button variant="text" disabled={submitting} onClick={() => void submit([])}>
            구독 중인 OTT가 없어요
          </Button>
        </div>
      </div>
    </>
  );
}
