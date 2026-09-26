/*
 * 3.4 취향 리포트 상세 · 7.4 취향 리포트 상세 — MY 경로
 *
 * 이동표
 * - 3.3 → 3.4 · 7.3 → 7.4
 *
 * 두 경로는 같은 상세 화면을 사용한다. 요소는 4컷(한 칸에 장르 · 감독 · 배우 · 캐릭터
 * 하나씩)과 `저장` · `공유하기`다. 두 동작은 상세 화면에서 바로 실행한다(업무 -861).
 *
 * **설명은 4컷 아래가 아니라 칸 뒷면에 있다**(사용자 요청 2026-09-23, 업무 -812). 와이어프레임은
 * `상세 리포트` · `취향 키워드` · `지난 반기와 비교`를 4컷 아래에 이어 붙였는데, 설명이 화면을
 * 길게 채워 4컷을 읽는 흐름이 끊겼다. 같은 내용을 그 값이 나온 칸의 뒷면으로 옮긴다.
 *
 * | 칸 | 뒷면 |
 * | --- | --- |
 * | 01 장르 | 장르 · 국가 · 시대 비율, 취향 키워드와 근거 |
 * | 02 감독 | 감독별 `N편 감상` |
 * | 03 배우 | 배우별 `N편 감상` |
 * | 04 캐릭터 | 캐릭터 카드, 지난 반기와 비교 |
 *
 * 어느 칸에도 속하지 않는 취향 키워드는 근거가 장르 · 인물 편수라 01에, 지난 반기 비교는 맛
 * 변화라 캐릭터 칸에 붙였다(사용자 결정 2026-09-23).
 *
 * - 뒷면 내용은 이 화면이 만들고 서식도 이 화면의 CSS가 정한다. 인화지는 뒤집는 틀과 어두운
 *   바탕만 맡는다. 어두운 바탕에 맞춰 `.back`이 색 토큰만 다시 정의하므로 `MeterRow` · `Chip`
 *   같은 공용 컴포넌트를 그대로 쓴다
 * - 그릴 값이 없는 뒷면은 아예 주지 않는다. 그 칸은 뒤집히지 않는 그림 칸으로 남는다
 * - 인화지를 그림으로 저장할 때는 뒤집힌 칸을 먼저 되돌린다.
 *   저장되는 것은 앞면 넷이다
 * - **`공유하기`는 앞면과 뒷면 두 장을 보낸다**(업무 -849). 앞면을 찍은 뒤 뒷면이 있는 칸을
 *   모두 뒤집어 한 장 더 찍고, 사용자가 뒤집어 두었던 모양으로 되돌린다. 받은 사람은 리포트
 *   주소를 열 수 없으므로(API-060 타인 404) 설명도 그림에 담는다
 * - **인물 검색으로 가는 길은 뒷면 목록의 이름뿐이다.** 업무 -557은 감독 · 배우 칸 전체를 검색
 *   링크로 덮었는데 그 자리를 뒤집기가 쓰게 되어 한 칸이 두 동작을 가질 수 없다. 누르면
 *   뒤집히는 쪽으로 통일하고(사용자 결정 2026-09-23) `ReportFourCut`의 `linkPeople`을 지웠다.
 *   1위 한 명이 아니라 목록의 세 명 모두로 갈 수 있어 오히려 길이 넓어진다
 *
 * 국가 · 시대 비율, 지난 반기 비교 %, 4컷은 2026-09-21 확정된 API-060 계약에서 온다(업무 -558).
 * 취향 키워드는 계약이 빈 배열을 주는 후속 범위라 값이 있을 때만 그린다. **캐릭터 설명도
 * 마찬가지다** — 선정 기준이 업무 -618에서 정해질 때까지 `characterCard`가 null로 오므로
 * 그 칸을 숨긴다(백엔드 답 2026-09-21).
 *
 * - 화면은 4컷 인화지 한 장과 `저장` · `공유하기` 버튼이다
 * - `지난 반기와 비교`의 빼기는 흐리게 쓴다. 색으로 늘고 준 것을 가르지 않는다
 *   (`design-concept.md` 2.2). 와이어프레임도 줄어든 값만 흐렸다
 * - 비교할 지난 반기가 없으면(첫 리포트) 그 칸을 그리지 않는다
 *
 * `저장`은 API-061로 앞면 PNG를 내려받고 화면에 머문다. 공유 메뉴를 쓸 수 없으면
 * 파일을 내려받지 않고 지원하지 않는 브라우저임을 알린다(업무 -861).
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import { ShareIcon } from '../../../components/Icon';
import MeterRow from '../../../components/MeterRow';
import ReportFourCut from '../../../components/ReportFourCut';
import type { ReportCutKind } from '../../../components/ReportFourCut';
import { CHARACTER_AXIS_POLES } from '../../../components/ReportFourCut/characterCards';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getReportDetail } from '../../../api/reports';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import { formatComparisonLabel, formatDelta, formatHalf } from '../../../utils/report';
import { captureReportBackImage, captureReportImage } from '../../../utils/reportImage';
import { canUseFileShare, shareFiles } from '../../../utils/share';
import { reportImageFailure, saveReportImage } from './saveReportImage';
import styles from './ReportDetailPage.module.css';
import type { ReactNode } from 'react';
import type {
  ReportCharacterAxis,
  ReportCharacterCard,
  ReportComparison,
  ReportDetail,
  ReportKeyword,
  ReportShare,
} from '../../../types/report';

/** 인화지의 520ms 회전이 끝나고 캡처할 수 있도록 여유를 둔다. */
const FLIP_BACK_MS = 560;
const PROFILE_TIMEOUT_MS = 5_000;
const SAVE_TIMEOUT_MS = 75_000;
const INTRO_DELAY_MS = 800;
const INTRO_READ_MS = 1_000;
const INTRO_GAP_MS = FLIP_BACK_MS + 180;
const INTRO_SCROLL_MS = 450;
const INTRO_STORAGE_PREFIX = 'feelm-report-flip-intro-v4:';
const CUT_LABELS: Record<ReportCutKind, string> = {
  genre: '장르',
  director: '감독',
  actor: '배우',
  character: '캐릭터',
};
const UNSUPPORTED_SHARE_NOTICE =
  '이 브라우저에서는 이미지 공유를 지원하지 않아요. 공유 메뉴를 지원하는 기기에서 시도해 주세요';

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(
      () => reject(new Error('Report image operation timed out')),
      ms,
    );
    promise.then(
      (value) => {
        window.clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        window.clearTimeout(timer);
        reject(error);
      },
    );
  });
}

function hasSeenFlipIntro(reportId: number): boolean {
  try {
    return window.localStorage.getItem(`${INTRO_STORAGE_PREFIX}${reportId}`) === '1';
  } catch {
    return false;
  }
}

function rememberFlipIntro(reportId: number): void {
  try {
    window.localStorage.setItem(`${INTRO_STORAGE_PREFIX}${reportId}`, '1');
  } catch {
    // 저장소 접근이 제한되어도 이번 화면의 안내 동작은 계속할 수 있다.
  }
}

function nextFrameOrFallback(): Promise<void> {
  return new Promise((resolve) => {
    let completed = false;
    const finish = () => {
      if (completed) return;
      completed = true;
      window.clearTimeout(fallback);
      resolve();
    };
    const fallback = window.setTimeout(finish, 1_000);
    requestAnimationFrame(finish);
  });
}

export default function ReportDetailPage() {
  const { reportId } = useParams();
  const location = useLocation();
  const id = Number(reportId);
  const replayIntro = new URLSearchParams(location.search).get('preview') === '1';

  const [report, setReport] = useState<ReportDetail | null>(null);
  const [nickname, setNickname] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [sharing, setSharing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState('');
  const [flipped, setFlipped] = useState<ReportCutKind[]>([]);
  const [introStep, setIntroStep] = useState<{
    kind: ReportCutKind;
    index: number;
    total: number;
  } | null>(null);
  const fourCutRef = useRef<HTMLDivElement>(null);
  const introTimers = useRef<number[]>([]);
  const introKind = useRef<ReportCutKind | null>(null);
  const introDismissed = useRef(false);
  const introCancelled = useRef(false);
  const introReportId = useRef<number | null>(null);

  const stopFlipIntro = useCallback((): ReportCutKind | null => {
    introDismissed.current = true;
    introCancelled.current = true;
    if (!replayIntro) rememberFlipIntro(id);
    introTimers.current.forEach((timer) => window.clearTimeout(timer));
    introTimers.current = [];
    const demoKind = introKind.current;
    introKind.current = null;
    setIntroStep(null);
    if (demoKind) setFlipped((list) => list.filter((kind) => kind !== demoKind));
    return demoKind;
  }, [id, replayIntro]);

  useEffect(() => {
    if (!report || report.reportId !== id) return;
    if (introReportId.current !== id) {
      introReportId.current = id;
      introDismissed.current = false;
      introCancelled.current = false;
    }
    const backKinds = (Object.entries(cutBacks(report)) as [ReportCutKind, ReactNode][])
      .filter(([, back]) => Boolean(back))
      .map(([kind]) => kind);
    if (backKinds.length === 0 || introDismissed.current || (!replayIntro && hasSeenFlipIntro(id)))
      return;

    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      introDismissed.current = true;
      if (!replayIntro) rememberFlipIntro(id);
      return;
    }

    const showNext = (index: number) => {
      const kind = backKinds[index];
      const cut = fourCutRef.current?.querySelector(`[data-cut="${kind}"]`)?.closest('li');
      const rect = cut?.getBoundingClientRect();
      const offscreen = Boolean(
        cut &&
        typeof cut.scrollIntoView === 'function' &&
        rect &&
        (rect.top < 16 || rect.bottom > window.innerHeight - 16),
      );
      if (offscreen) cut?.scrollIntoView({ behavior: 'smooth', block: 'center' });

      const reveal = window.setTimeout(
        () => {
          if (introCancelled.current) return;
          introKind.current = kind;
          setIntroStep({ kind, index, total: backKinds.length });
          setFlipped([kind]);

          const close = window.setTimeout(() => {
            if (introKind.current !== kind) return;
            introKind.current = null;
            setFlipped([]);
            if (index + 1 < backKinds.length) {
              const next = window.setTimeout(() => showNext(index + 1), INTRO_GAP_MS);
              introTimers.current.push(next);
            } else {
              const finish = window.setTimeout(() => {
                if (introCancelled.current) return;
                setIntroStep(null);
                if (window.scrollY > 0) window.scrollTo({ top: 0, behavior: 'smooth' });
              }, FLIP_BACK_MS);
              introTimers.current.push(finish);
            }
          }, FLIP_BACK_MS + INTRO_READ_MS);
          introTimers.current.push(close);
        },
        offscreen ? INTRO_SCROLL_MS : 0,
      );
      introTimers.current.push(reveal);
    };

    const open = window.setTimeout(() => {
      if (introCancelled.current) return;
      introDismissed.current = true;
      if (!replayIntro) rememberFlipIntro(id);
      showNext(0);
    }, INTRO_DELAY_MS);
    introTimers.current.push(open);

    const cancelOnScrollIntent = () => {
      if (!introCancelled.current) stopFlipIntro();
    };
    const cancelOnScrollKey = (event: KeyboardEvent) => {
      if (['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes(event.key)) {
        cancelOnScrollIntent();
      }
    };
    window.addEventListener('wheel', cancelOnScrollIntent, { passive: true });
    window.addEventListener('touchstart', cancelOnScrollIntent, { passive: true });
    window.addEventListener('keydown', cancelOnScrollKey);

    return () => {
      introTimers.current.forEach((timer) => window.clearTimeout(timer));
      introTimers.current = [];
      window.removeEventListener('wheel', cancelOnScrollIntent);
      window.removeEventListener('touchstart', cancelOnScrollIntent);
      window.removeEventListener('keydown', cancelOnScrollKey);
    };
  }, [report, id, replayIntro, stopFlipIntro]);

  useEffect(() => {
    let active = true;

    getReportDetail(id)
      .then((value) => {
        if (active) {
          setReport(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [id, reloadCount]);

  // 4컷 머리의 `{닉네임}의 취향 4컷`. 못 불러오면 `나의 취향 4컷`으로 둔다
  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((profile) => {
        if (active) setNickname(profile.nickname);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  function toggleFlip(kind: ReportCutKind) {
    const demoKind = stopFlipIntro();
    setFlipped((list) => {
      const current = demoKind ? list.filter((item) => item !== demoKind) : list;
      return current.includes(kind) ? current.filter((item) => item !== kind) : [...current, kind];
    });
  }

  async function prepareImage() {
    // 저장되는 것은 앞면 넷이다. 뒤집힌 칸이 있으면 되돌아가는 것을 기다린 뒤 찍는다
    if (flipped.length > 0) {
      setFlipped([]);
      await wait(FLIP_BACK_MS);
    }

    try {
      const profile = await withTimeout(getMyProfile(), PROFILE_TIMEOUT_MS);
      if (profile.nickname !== nickname) {
        setNickname(profile.nickname);
        await nextFrameOrFallback();
      }
    } catch (error) {
      // 화면에 이미 닉네임이 있으면 현재 보이는 DOM을 그대로 저장할 수 있다.
      if (!nickname) throw error;
    }
  }

  async function saveImage(onStage?: (stage: 'capture' | 'upload' | 'download') => void) {
    await prepareImage();
    onStage?.('capture');
    return saveReportImage(id, fourCutNode(), onStage);
  }

  async function save() {
    stopFlipIntro();
    setSaving(true);
    setNotice('이미지를 준비하고 있어요…');
    try {
      await withTimeout(
        saveImage((next) => {
          if (next === 'upload') setNotice('이미지를 서버에 저장하고 있어요…');
          if (next === 'download') setNotice('파일을 내려받고 있어요…');
        }),
        SAVE_TIMEOUT_MS,
      );
      setNotice('다운로드를 시작했어요. 파일 앱에서 확인해 주세요');
    } catch (error) {
      setNotice(reportImageFailure(error));
    } finally {
      setSaving(false);
    }
  }

  async function share() {
    setNotice('');
    if (!canUseFileShare()) {
      stopFlipIntro();
      setNotice(UNSUPPORTED_SHARE_NOTICE);
      return;
    }
    setSharing(true);
    try {
      const files = await prepareShareImages();
      if (!report) return;
      const title = `${report.period.year} ${formatHalf(report.period)} 취향 리포트`;
      const result = await shareFiles({ title, files });
      if (result === 'shared' || result === 'cancelled') return;
      setNotice(
        result === 'blocked'
          ? '공유 메뉴를 열지 못했어요. 다시 시도해 주세요'
          : UNSUPPORTED_SHARE_NOTICE,
      );
    } catch (error) {
      setNotice(reportImageFailure(error));
    } finally {
      setSharing(false);
    }
  }

  /** 공유할 두 장. 앞면을 찍고, 뒷면이 있는 칸을 모두 뒤집어 한 장 더 찍는다 */
  async function prepareShareImages(): Promise<File[]> {
    const before = introKind.current
      ? flipped.filter((kind) => kind !== introKind.current)
      : flipped;
    stopFlipIntro();
    const backKinds = report
      ? (Object.entries(cutBacks(report)) as [ReportCutKind, ReactNode][])
          .filter(([, back]) => Boolean(back))
          .map(([kind]) => kind)
      : [];

    try {
      await prepareImage();
      const files = [imageFile(await captureReportImage(requireFourCut()), 'front')];

      if (backKinds.length > 0) {
        setFlipped(backKinds);
        await wait(FLIP_BACK_MS);
        files.push(imageFile(await captureReportBackImage(requireFourCut()), 'back'));
      }

      return files;
    } finally {
      setFlipped(before);
    }
  }

  function fourCutNode(): HTMLElement | null {
    return fourCutRef.current?.firstElementChild as HTMLElement | null;
  }

  function requireFourCut(): HTMLElement {
    const node = fourCutNode();
    if (!node) throw new Error('ReportFourCut is unavailable');

    return node;
  }

  function imageFile(png: Blob, face: 'front' | 'back'): File {
    return new File([png], `feelm-report-${id}-${face}.png`, { type: 'image/png' });
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="취향 리포트" showBack fallbackTo={path.tasteReports()} />
        <StatusView
          title="리포트를 불러오지 못했어요"
          description="아직 만드는 중이거나 잠시 문제가 생겼어요"
        >
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

  if (!report) {
    return <ScreenHeader title="취향 리포트" showBack fallbackTo={path.tasteReports()} />;
  }

  const title = `${report.period.year} ${formatHalf(report.period)} 취향 리포트`;

  return (
    <>
      <ScreenHeader title={title} showBack fallbackTo={path.tasteReports()} />

      <div className={styles.page}>
        {Object.values(cutBacks(report)).some(Boolean) ? (
          <p className={styles.flipGuide}>
            {introStep
              ? `${String(introStep.index + 1).padStart(2, '0')} / ${String(introStep.total).padStart(2, '0')} · ${CUT_LABELS[introStep.kind]} 뒷면 살펴보기`
              : '각 컷을 눌러 뒷면의 설명을 확인해 보세요'}
          </p>
        ) : null}
        {/* 4컷. 인화지가 화면에서 유일한 어두운 블록이고, 설명은 그 칸 뒷면에 있다 */}
        <div ref={fourCutRef} className={styles.fourCut}>
          <ReportFourCut
            cuts={report.fourCut}
            owner={nickname}
            createdAt={report.createdAt}
            characterCode={report.characterCard?.code}
            backs={cutBacks(report)}
            flipped={flipped}
            onFlip={toggleFlip}
          />
        </div>

        <div className={styles.footer}>
          {notice ? (
            <p className={styles.notice} role="status">
              {notice}
            </p>
          ) : null}
          <p className={styles.shareHint}>공유 이미지에는 닉네임과 취향 정보가 담겨요</p>
          <div className={styles.actions}>
            <Button variant="secondary" disabled={saving || sharing} onClick={() => void save()}>
              <span className={styles.actionLabel}>
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M12 3v12m-4-4 4 4 4-4M5 15v5h14v-5"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
                저장
              </span>
            </Button>
            <Button disabled={saving || sharing} onClick={() => void share()}>
              <span className={styles.actionLabel}>
                <ShareIcon />
                공유하기
              </span>
            </Button>
          </div>
        </div>
      </div>
    </>
  );
}

/*
 * 칸 넷의 뒷면. 그릴 값이 없는 칸은 주지 않는다 — 그 칸은 뒤집히지 않는 그림 칸으로 남는다.
 *
 * 뒷면 바탕이 어두우므로 색 토큰만 `.back`에서 다시 정의하고, `MeterRow` · `Chip` 같은 공용
 * 컴포넌트는 그대로 쓴다(`ReportDetailPage.module.css`).
 */
function cutBacks(report: ReportDetail): Partial<Record<ReportCutKind, ReactNode>> {
  const shareGroups = [
    { label: '장르', shares: report.genreShares },
    { label: '국가', shares: report.countryShares },
    { label: '시대', shares: report.eraShares },
  ].filter((group) => group.shares.length > 0);

  const hasCharacter = Boolean(report.characterCard) || report.comparisons.length > 0;

  return {
    genre:
      shareGroups.length > 0 || report.keywords.length > 0 ? (
        <Back>
          {shareGroups.map((group) => (
            <ShareGroup key={group.label} label={group.label} shares={group.shares} />
          ))}
          {report.keywords.length > 0 ? <KeywordGroup keywords={report.keywords} /> : null}
        </Back>
      ) : undefined,

    director:
      report.topDirectors.length > 0 ? (
        <Back>
          <PeopleGroup label="감독" people={report.topDirectors} />
        </Back>
      ) : undefined,

    actor:
      report.topActors.length > 0 ? (
        <Back>
          <PeopleGroup label="배우" people={report.topActors} />
        </Back>
      ) : undefined,

    character: hasCharacter ? (
      <Back>
        {report.characterCard ? <CharacterGroup card={report.characterCard} /> : null}
        {report.comparisons.length > 0 ? <ComparisonGroup items={report.comparisons} /> : null}
      </Back>
    ) : undefined,
  };
}

/** 뒷면 한 장. 어두운 바탕에 맞춰 색 토큰을 바꾸는 자리다 */
function Back({ children }: { children: ReactNode }) {
  return <div className={styles.back}>{children}</div>;
}

function KeywordGroup({ keywords }: { keywords: ReportKeyword[] }) {
  return (
    <div className={styles.group}>
      <h3 className={styles.groupLabel}>취향 키워드</h3>
      <ul className={styles.keywords}>
        {keywords.map((keyword) => (
          <li key={keyword.code} className={styles.keyword}>
            <Chip>{keyword.label}</Chip>
            <span className={styles.rationale}>{keyword.rationale}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ComparisonGroup({ items }: { items: ReportComparison[] }) {
  return (
    <div className={styles.group}>
      <h3 className={styles.groupLabel}>지난 반기와 비교</h3>
      <ul className={styles.compares}>
        {items.map((item) => (
          <li key={item.label} className={styles.compare}>
            <span>{formatComparisonLabel(item.label, item.isNew)}</span>
            <span className={styles.delta} data-negative={item.delta < 0}>
              {formatDelta(item.delta, item.isNew)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/*
 * 캐릭터 카드 한 장. 이름 · 한 줄 · 설명문과 네 축을 보인다(업무 -618 · -780).
 *
 * **축을 함께 보이는 이유가 있다.** 카드는 그 반기 대상자 전체의 중앙값 기준 상대 판정이라
 * 같은 감상이어도 반기마다 다른 카드가 나올 수 있다. 네 축을 펼쳐 두면 왜 이 카드인지가
 * 설명되어 그 점이 덜 뜬금없다(업무 -618 결정).
 *
 * 축은 두 값 중 하나라서 **크기가 없다.** 퍼센트 막대(`MeterRow`)를 쓰면 없는 수치를
 * 만들어 내므로, 양쪽 끝을 나란히 두고 고른 쪽만 강조한다.
 */
function CharacterGroup({ card }: { card: ReportCharacterCard }) {
  return (
    <div className={styles.group}>
      <h3 className={styles.groupLabel}>캐릭터</h3>

      <p className={styles.cardName}>
        {card.name}
        <span className={styles.cardCode}>{card.code}</span>
      </p>
      <p className={styles.cardTagline}>{card.tagline}</p>

      <ul className={styles.axes}>
        {card.axes.map((axis) => (
          <AxisRow key={axis.axis} axis={axis} />
        ))}
      </ul>

      <p className={styles.paragraph}>{card.description}</p>

      {/* 프레임 10편 미만은 표본이 적다. 카드는 그대로 주고 기준 편수를 함께 알린다 */}
      {card.basis.lowSample ? (
        <p className={styles.cardBasis}>이번 반기 {card.basis.frameCount}편을 기준으로 뽑았어요</p>
      ) : null}
    </div>
  );
}

/** 축 한 줄. 고른 쪽 이름은 계약이 준 `label`, 반대쪽은 화면이 가진 표를 쓴다 */
function AxisRow({ axis }: { axis: ReportCharacterAxis }) {
  const poles = CHARACTER_AXIS_POLES[axis.axis];
  const headPicked = poles[0].value === axis.value;
  const [head, tail] = headPicked ? [axis.label, poles[1].label] : [poles[0].label, axis.label];

  return (
    <li className={styles.axis} data-picked={headPicked ? 'head' : 'tail'}>
      <span className={styles.axisPole}>{head}</span>
      <span className={styles.axisTrack} aria-hidden="true">
        <span className={styles.axisDot} />
      </span>
      <span className={styles.axisPole}>{tail}</span>
    </li>
  );
}

function ShareGroup({ label, shares }: { label: string; shares: ReportShare[] }) {
  // 메타데이터가 없는 반기는 배열이 비어 온다. 제목만 남기지 않는다
  if (shares.length === 0) return null;

  return (
    <div className={styles.group}>
      <h3 className={styles.groupLabel}>{label}</h3>
      {shares.map((item, index) => (
        <MeterRow key={item.label} label={item.label} rate={item.share} delayIndex={index} />
      ))}
    </div>
  );
}

function PeopleGroup({
  label,
  people,
}: {
  label: string;
  people: { name: string; count: number }[];
}) {
  if (people.length === 0) return null;

  return (
    <div className={styles.group}>
      <h3 className={styles.groupLabel}>{label}</h3>
      <ul className={styles.people}>
        {people.map((person) => (
          <li key={person.name} className={styles.person}>
            <Link className={styles.personLink} to={path.searchWith(person.name)}>
              {person.name}
            </Link>
            <span className={styles.count}>{person.count}편 감상</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
