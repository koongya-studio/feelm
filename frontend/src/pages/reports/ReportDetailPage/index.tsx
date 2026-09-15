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
 * 계약(API-060)에 없는 값 — 국가 · 시대 비율, 키워드 근거, 비교 % — 도 와이어프레임대로
 * 그린다(팀 결정 2026-09-13). `types/unspecified.ts` `ReportDetailView`.
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
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import Button from '../../../components/Button';
import Chip from '../../../components/Chip';
import IconButton from '../../../components/IconButton';
import MeterRow from '../../../components/MeterRow';
import ReportFourCut from '../../../components/ReportFourCut';
import ScreenHeader from '../../../components/ScreenHeader';
import Section from '../../../components/Section';
import StatusView from '../../../components/StatusView';
import { ShareIcon } from '../../../components/Icon';
import { getReportDetail } from '../../../api/reports';
import { getMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import { formatDelta, formatHalf } from '../../../utils/report';
import ReportShareSheet from './ReportShareSheet';
import styles from './ReportDetailPage.module.css';
import type { ReportDetailView, ReportShare } from '../../../types/unspecified';

type Props = {
  /** `film` 3.4(2.1 · 3.3에서 옴) · `my` 7.4(7.3에서 옴) */
  from: 'film' | 'my';
};

export default function ReportDetailPage({ from }: Props) {
  const navigate = useNavigate();
  const { reportId } = useParams();
  const id = Number(reportId);

  const [report, setReport] = useState<ReportDetailView | null>(null);
  const [nickname, setNickname] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [sharing, setSharing] = useState(false);

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
      void navigate(-1);
    } else {
      void navigate(path.film());
    }
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="취향 리포트" showBack />
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
    return <ScreenHeader title="취향 리포트" showBack />;
  }

  const title = `${report.period.year} ${formatHalf(report.period)} 취향 리포트`;

  return (
    <>
      <ScreenHeader
        title={title}
        showBack
        action={
          <IconButton label="공유하기" icon={<ShareIcon />} onClick={() => setSharing(true)} />
        }
      />

      <div className={styles.page}>
        {/* 4컷. 인화지가 화면에서 유일한 어두운 블록이다 */}
        <div className={styles.fourCut}>
          <ReportFourCut cuts={report.fourCut} owner={nickname} createdAt={report.createdAt} />
        </div>

        <Section label="상세 리포트">
          <div className={styles.card}>
            <ShareGroup label="장르" shares={report.genreShares} />
            <ShareGroup label="국가" shares={report.countryShares} />
            <ShareGroup label="시대" shares={report.eraShares} />

            <PeopleGroup label="감독" people={report.topDirectors} />
            <PeopleGroup label="배우" people={report.topActors} />

            <div className={styles.group}>
              <h3 className={styles.groupLabel}>캐릭터</h3>
              <p className={styles.paragraph}>{report.characterCard.description}</p>
            </div>
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
                  <span>{item.label}</span>
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
          onClose={() => setSharing(false)}
        />
      ) : null}
    </>
  );
}

function ShareGroup({ label, shares }: { label: string; shares: ReportShare[] }) {
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
  return (
    <div className={styles.group}>
      <h3 className={styles.groupLabel}>{label}</h3>
      <ul className={styles.people}>
        {people.map((person) => (
          <li key={person.name} className={styles.person}>
            <span>{person.name}</span>
            <span className={styles.count}>{person.count}편 감상</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
