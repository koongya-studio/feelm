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
 * 요소 — 4컷(한 칸에 장르 · 감독 · 배우 · 캐릭터 하나씩), `상세 리포트`(장르 · 국가 · 시대 비율, 감독 · 배우 `N편 감상`, 캐릭터
 * 문단), `취향 키워드`와 근거, `지난 반기와 비교`, `저장` · `공유하기`.
 *
 * 국가 · 시대 비율, 지난 반기 비교 %, 4컷은 2026-09-21 확정된 API-060 계약에서 온다(업무 -558).
 * 취향 키워드는 계약이 빈 배열을 주는 후속 범위라 값이 있을 때만 그린다. **캐릭터 설명도
 * 마찬가지다** — 선정 기준이 업무 -618에서 정해질 때까지 `characterCard`가 null로 오므로
 * 그 칸을 숨긴다(백엔드 답 2026-09-21).
 *
 * - 어두운 블록은 맨 위 4컷의 인화지다(`components/ReportFourCut`)
 * - `지난 반기와 비교`의 빼기는 흐리게 쓴다. 색으로 늘고 준 것을 가르지 않는다
 *   (`design-concept.md` 2.2). 와이어프레임도 줄어든 값만 흐렸다
 * - 비교할 지난 반기가 없으면(첫 리포트) 그 칸을 그리지 않는다
 *
 * `[?]` `저장`이 무엇을 저장하는지 적혀 있지 않다. 리포트는 이미 서버에 있으므로 확인하고
 * 나가는 동작으로 보았다. 이미지 저장은 3.5의 `이미지로 저장`이 맡는다.
 * `[?]` 7.4는 와이어프레임에 `공유하기` 버튼이 없지만 이동표에 `7.4 ⇢ 3.5`가 있어 두었다.
 */
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import IconButton from '../../../components/IconButton';
import MeterRow from '../../../components/MeterRow';
import ReportFourCut from '../../../components/ReportFourCut';
import { CHARACTER_AXIS_POLES } from '../../../components/ReportFourCut/characterCards';
import ScreenHeader from '../../../components/ScreenHeader';
import Section from '../../../components/Section';
import StatusView from '../../../components/StatusView';
import { ShareIcon } from '../../../components/Icon';
import { getReportDetail } from '../../../api/reports';
import { getMyProfile } from '../../../api/users';
import { useBack } from '../../../hooks/useBack';
import { path } from '../../../routes/paths';
import { formatComparisonLabel, formatDelta, formatHalf } from '../../../utils/report';
import ReportShareSheet from './ReportShareSheet';
import styles from './ReportDetailPage.module.css';
import type {
  ReportCharacterAxis,
  ReportCharacterCard,
  ReportDetail,
  ReportShare,
} from '../../../types/report';

type Props = {
  /** `film` 3.4(2.1 · 3.3에서 옴) · `my` 7.4(7.3에서 옴) */
  from: 'film' | 'my';
};

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

  function save() {
    if (from === 'my') {
      back();
    } else {
      void navigate(path.film());
    }
  }

  async function prepareImage() {
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
          <IconButton label="공유하기" icon={<ShareIcon />} onClick={() => setSharing(true)} />
        }
      />

      <div className={styles.page}>
        {/* 4컷. 인화지가 화면에서 유일한 어두운 블록이다 */}
        <div ref={fourCutRef} className={styles.fourCut}>
          <ReportFourCut
            cuts={report.fourCut}
            owner={nickname}
            createdAt={report.createdAt}
            characterCode={report.characterCard?.code}
            linkPeople
          />
        </div>

        <Section label="상세 리포트">
          <div className={styles.card}>
            <ShareGroup label="장르" shares={report.genreShares} />
            <ShareGroup label="국가" shares={report.countryShares} />
            <ShareGroup label="시대" shares={report.eraShares} />

            <PeopleGroup label="감독" people={report.topDirectors} />
            <PeopleGroup label="배우" people={report.topActors} />

            {report.characterCard ? <CharacterGroup card={report.characterCard} /> : null}
          </div>
        </Section>

        {report.keywords.length > 0 ? (
          <Section label="취향 키워드">
            <ul className={styles.keywords}>
              {report.keywords.map((keyword) => (
                <li key={keyword.code} className={styles.keyword}>
                  <Chip>{keyword.label}</Chip>
                  <span className={styles.rationale}>{keyword.rationale}</span>
                </li>
              ))}
            </ul>
          </Section>
        ) : null}

        {report.comparisons.length > 0 ? (
          <Section label="지난 반기와 비교">
            <ul className={styles.card}>
              {report.comparisons.map((item) => (
                <li key={item.label} className={styles.compare}>
                  <span>{formatComparisonLabel(item.label, item.isNew)}</span>
                  <span className={styles.delta} data-negative={item.delta < 0}>
                    {formatDelta(item.delta, item.isNew)}
                  </span>
                </li>
              ))}
            </ul>
          </Section>
        ) : null}

        <div className={styles.actions}>
          <Button variant="secondary" onClick={save}>
            저장
          </Button>
          <Button onClick={() => setSharing(true)}>공유하기</Button>
        </div>
      </div>

      {sharing ? (
        <ReportShareSheet
          reportId={report.reportId}
          title={title}
          getImageNode={() => fourCutRef.current?.firstElementChild as HTMLElement | null}
          prepareImage={prepareImage}
          onClose={() => setSharing(false)}
        />
      ) : null}
    </>
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
