/*
 * 1.2 구독 OTT 초기 설정
 *
 * 요소와 문구는 와이어프레임 1.2 그대로다(screen-spec.md 4장 1.2).
 * OTT 6개는 API-025로 조회한다. 지원 목록 확정은 API-025 미정 사항이다.
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
import { getOtts, setMyOtts } from '../../../api/otts';
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
    await setMyOtts(ottIds);
    completeOtt(ottIds);
    void navigate(path.onboardingComplete());
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
