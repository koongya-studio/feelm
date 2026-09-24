/*
 * 3.4 취향 리포트 상세 · 7.4 취향 리포트 상세 — MY 경로
 *
 * 이동표
 * - 3.3 → 3.4 · 3.4 → 3.5 공유하기 · 3.4 ⇢ 3.1 저장 후 취향 필름으로 돌아가기
 * - 7.3 → 7.4 · 7.4 ⇢ 3.5 공유하기 · 7.4 ⇢ 7.3 저장 또는 뒤로 가기
 *
 * 와이어프레임은 두 화면을 같은 내용으로 그렸다. 차이는 **`저장`이 어디로 돌아가는지**다.
 * 필름 쪽(3.4)은 3.1로, MY 쪽(7.4)은 온 곳(7.3)으로 간다. `from`으로 가른다.
 *
 * 요소 — 4컷(한 칸에 장르 · 감독 · 배우 · 캐릭터 하나씩), `저장` · `공유하기`.
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
 * - 인화지를 그림으로 저장할 때(`저장` · 3.5 `이미지로 저장`)는 뒤집힌 칸을 먼저 되돌린다.
 *   저장되는 것은 앞면 넷이다
 * - **3.5 `공유하기`는 앞면과 뒷면 두 장을 보낸다**(업무 -849). 앞면을 찍은 뒤 뒷면이 있는 칸을
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
 * - 화면은 4컷 인화지 한 장과 버튼 두 개다
 * - `지난 반기와 비교`의 빼기는 흐리게 쓴다. 색으로 늘고 준 것을 가르지 않는다
 *   (`design-concept.md` 2.2). 와이어프레임도 줄어든 값만 흐렸다
 * - 비교할 지난 반기가 없으면(첫 리포트) 그 칸을 그리지 않는다
 *
 * `저장`은 4컷 PNG를 내려받은 뒤 위의 곳으로 돌아간다(사용자 결정 2026-09-23, 업무 -731).
 * 3.5의 `이미지로 저장`과 같은 함수다(`saveReportImage.ts`). 실패하면 나가지 않고 버튼 위에
 * 이유를 알린다 — 내려받지 못했는데 돌아가면 저장된 줄 안다.
 * `[?]` 7.4는 와이어프레임에 `공유하기` 버튼이 없지만 이동표에 `7.4 ⇢ 3.5`가 있어 두었다.
 */
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import IconButton from '../../../components/IconButton';
import MeterRow from '../../../components/MeterRow';
import ReportFourCut from '../../../components/ReportFourCut';
import type { ReportCutKind } from '../../../components/ReportFourCut';
import { CHARACTER_AXIS_POLES } from '../../../components/ReportFourCut/characterCards';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { ShareIcon } from '../../../components/Icon';
import { getReportDetail } from '../../../api/reports';
import { getMyProfile } from '../../../api/users';
import { useBack } from '../../../hooks/useBack';
import { path } from '../../../routes/paths';
import { formatComparisonLabel, formatDelta, formatHalf } from '../../../utils/report';
import { captureReportBackImage, captureReportImage } from '../../../utils/reportImage';
import ReportShareSheet from './ReportShareSheet';
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

type Props = {
  /** `film` 3.4(2.1 · 3.3에서 옴) · `my` 7.4(7.3에서 옴) */
  from: 'film' | 'my';
};

/** 칸이 뒤집히거나 앞면으로 되돌아오는 데 걸리는 시간. 인화지의 `--flip-duration`과 맞춘다 */
const FLIP_BACK_MS = 560;

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
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

export default function ReportDetailPage({ from }: Props) {
  const navigate = useNavigate();
  const back = useBack(path.tasteReports());
  const { reportId } = useParams();
  const id = Number(reportId);

  const [report, setReport] = useState<ReportDetail | null>(null);
  const [nickname, setNickname] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [sharing, setSharing] = useState(false);
  const [flipped, setFlipped] = useState<ReportCutKind[]>([]);
  const [saving, setSaving] = useState(false);
  const [saveFailure, setSaveFailure] = useState('');
  const fourCutRef = useRef<HTMLDivElement>(null);

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

  async function save() {
    setSaving(true);
    setSaveFailure('');

    try {
      await saveImage();
    } catch (error) {
      setSaveFailure(reportImageFailure(error));
      setSaving(false);

      return;
    }

    if (from === 'my') {
      back();
    } else {
      void navigate(path.film());
    }
  }

  function toggleFlip(kind: ReportCutKind) {
    setFlipped((list) => (list.includes(kind) ? list.filter((k) => k !== kind) : [...list, kind]));
  }

  async function prepareImage() {
    // 저장되는 것은 앞면 넷이다. 뒤집힌 칸이 있으면 되돌아가는 것을 기다린 뒤 찍는다
    if (flipped.length > 0) {
      setFlipped([]);
      await wait(FLIP_BACK_MS);
    }

    try {
      const profile = await getMyProfile();
      if (profile.nickname !== nickname) {
        setNickname(profile.nickname);
        await nextFrameOrFallback();
      }
    } catch (error) {
      // 화면에 이미 닉네임이 있으면 현재 보이는 DOM을 그대로 저장할 수 있다.
      if (!nickname) throw error;
    }
  }

  async function saveImage() {
    await prepareImage();
    await saveReportImage(id, fourCutNode());
  }

  /** 3.5 `공유하기`의 두 장. 앞면을 찍고, 뒷면이 있는 칸을 모두 뒤집어 한 장 더 찍는다 */
  async function prepareShareImages(): Promise<File[]> {
    const before = flipped;
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
      <ScreenHeader
        title={title}
        showBack
        fallbackTo={path.tasteReports()}
        action={
          <IconButton
            label="공유하기"
            icon={<ShareIcon />}
            disabled={saving}
            onClick={() => setSharing(true)}
          />
        }
      />

      <div className={styles.page}>
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
          {saveFailure ? (
            <p className={styles.notice} role="status">
              {saveFailure}
            </p>
          ) : null}

          <div className={styles.actions}>
            <Button variant="secondary" disabled={saving} onClick={() => void save()}>
              저장
            </Button>
            <Button disabled={saving} onClick={() => setSharing(true)}>
              공유하기
            </Button>
          </div>
        </div>
      </div>

      {sharing ? (
        <ReportShareSheet
          title={title}
          onPrepareShare={prepareShareImages}
          onSaveImage={saveImage}
          onClose={() => setSharing(false)}
        />
      ) : null}
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
