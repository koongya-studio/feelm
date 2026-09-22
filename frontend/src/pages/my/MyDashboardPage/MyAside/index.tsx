/*
 * 7.1 MY의 우측 보조 패널 (업무 S15P21E106-786).
 *
 * 2026-09-21 PC 시안의 오른쪽 단이다. 카드 셋이다.
 *
 * - **나의 취향 키워드** — 넓은 화면에서는 프로필 카드에서 빼 이 자리로 옮긴다(시안)
 * - **팝콘 분포도** — 3.1 취향 필름과 같은 표다(`components/PopcornFlavorPanel`, 업무 -783)
 * - **최근 활동** — 아래 참고
 *
 * **시안의 `좋아하는 장르 TOP 5`는 두지 않는다**(사용자 결정 2026-09-22). 시안은 위 카드에
 * 상위 다섯의 막대를, 아래 카드에 여덟 줄의 표를 그리는데 **둘이 같은 값**이다 — 이 앱에서
 * `액션 · 모험` 같은 이름은 장르가 아니라 팝콘 맛이고(API-041 · API-044), 백분율도 같은
 * 팝콘 알 수의 비율이다. 같은 수치를 두 번 그리지 않고 표 하나로 합쳤다.
 *
 * **`최근 활동`은 활동 로그 계약이 없다.** 그런 API가 아예 없으므로 **이미 받아 둔 기록으로
 * 조립한다**(사용자 결정 2026-09-22) — 평가(API-039)와 취향 리포트(API-059)다. 새로 부르는
 * 것은 없고, 두 목록을 시각순으로 섞어 앞에서부터 몇 줄만 쓴다. 시안의 `취향 팝콘이
 * 업데이트되었어요`는 그 시각을 주는 값이 없어(API-041에 갱신 시각이 없다) 넣지 않는다.
 *
 * `전체 보기`는 `내 기록` 피드가 이미 그 일을 하므로 두지 않는다 — 같은 화면 안에서 위를
 * 가리키는 링크가 된다.
 */
import { Link } from 'react-router';
import AsidePanel from '../../../../components/AsidePanel';
import Chip from '../../../../components/Chip';
import PopcornFlavorPanel from '../../../../components/PopcornFlavorPanel';
import { path } from '../../../../routes/paths';
import { formatDate } from '../../../../utils/date';
import styles from './MyAside.module.css';
import type { PopcornBucket } from '../../../../types/taste';
import type { ProfileView } from '../../../../types/unspecified';
import type { ReportSummary } from '../../../../types/report';
import type { MyRatedItem } from '../../../../types/watch';

/** 최근 활동에 세우는 줄 수. 시안이 넷이다 */
const ACTIVITY_COUNT = 4;

/** 대표 키워드로 앞에 채워 넣는 칩 수. 시안의 첫 줄이다 */
const SOLID_KEYWORD_COUNT = 3;

type Activity = {
  key: string;
  /** 무슨 일이 있었는지 */
  label: string;
  /** 그 일의 대상. 영화 제목처럼 한 줄 아래에 붙는다 */
  detail?: string;
  at: string;
  to: string;
};

type Props = {
  profile: ProfileView;
  /** API-041 팝콘 버킷. 못 받았으면 그 카드만 없다 */
  bucket: PopcornBucket | null;
  /** 이미 받아 둔 평가 기록(API-039). 최근 활동이 쓴다 */
  ratings: MyRatedItem[];
  /** 이미 받아 둔 취향 리포트 목록(API-059). 최근 활동이 쓴다 */
  reports: ReportSummary[];
};

/** 두 기록을 시각순으로 섞어 앞에서부터 몇 줄을 만든다 */
function activitiesOf(ratings: MyRatedItem[], reports: ReportSummary[]): Activity[] {
  const rows: Activity[] = [
    ...ratings.map((rating) => ({
      key: `rating:${rating.ratingId}`,
      label: '영화 평가를 남겼어요',
      detail: rating.movie.title,
      at: rating.createdAt,
      to: path.movieDetail(rating.movie.movieId),
    })),
    // 아직 인화 중인 리포트는 `생성되었어요`가 아니므로 뺀다
    ...reports
      .filter((report) => report.status === 'READY')
      .map((report) => ({
        key: `report:${report.reportId}`,
        label: '취향 리포트가 만들어졌어요',
        at: report.createdAt,
        to: path.myReport(report.reportId),
      })),
  ];

  return rows
    .sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime())
    .slice(0, ACTIVITY_COUNT);
}

export default function MyAside({ profile, bucket, ratings, reports }: Props) {
  /* 공개로 둔 키워드만 보여준다. 프로필 카드가 좁은 화면에서 쓰는 규칙과 같다 */
  const keywords = profile.keywords.filter((keyword) => keyword.isPublic);
  const activities = activitiesOf(ratings, reports);

  return (
    <>
      {keywords.length > 0 ? (
        <AsidePanel title="나의 취향 키워드">
          <ul className={styles.keywords}>
            {keywords.map((keyword, index) => (
              <li key={keyword.code}>
                <Chip variant={index < SOLID_KEYWORD_COUNT ? 'solid' : 'outline'}>
                  {keyword.label}
                </Chip>
              </li>
            ))}
          </ul>
        </AsidePanel>
      ) : null}

      {bucket ? (
        <PopcornFlavorPanel
          title="팝콘 분포도"
          bucket={bucket}
          to={path.popcornBucket()}
          linkLabel="팝콘 분포도 자세히 보기"
        />
      ) : null}

      {activities.length > 0 ? (
        <AsidePanel title="최근 활동">
          <ul className={styles.activities}>
            {activities.map((activity) => (
              <li key={activity.key} className={styles.activity}>
                <Link to={activity.to} className={styles.activityLink}>
                  <span className={styles.activityBody}>
                    <span className={styles.activityLabel}>{activity.label}</span>
                    {activity.detail ? (
                      <span className={styles.activityDetail}>{activity.detail}</span>
                    ) : null}
                  </span>

                  <span className={styles.activityDate}>{formatDate(activity.at)}</span>
                </Link>
              </li>
            ))}
          </ul>
        </AsidePanel>
      ) : null}
    </>
  );
}
