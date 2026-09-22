/*
 * 팝콘 맛 분포 카드 (업무 S15P21E106-783에서 만들고 -786에서 공용으로 옮겼다).
 *
 * 2026-09-21 시안의 오른쪽 단에 **같은 표가 두 화면에 있다** — 3.1 취향 필름의 `나의 취향
 * 팝콘`과 7.1 MY의 `팝콘 분포도`다. 검은 카드 안에 맛마다 팝콘 알 그림 · 이름 · 감상량 ·
 * 선호도가 한 줄씩 선다. 두 화면이 각자 그리면 같은 표의 간격과 수치 표기가 조금씩 어긋나므로
 * 여기 한 번만 만든다(업무 -786 Jira — 이미 개발해 쓰는 것은 새로 만들지 않고 재사용한다).
 *
 * 화면마다 다른 것은 카드 제목과 화살표가 가는 곳뿐이라 그 둘만 받는다.
 *
 * **0알인 맛은 빼고 많이 나온 맛부터** 세운다. 아직 발견하지 않은 맛을 0으로 줄 세우면
 * 표의 절반이 빈 줄이 된다.
 *
 * 감상량은 팝콘 알 수의 비율, **선호도는 그 맛 영화들의 평균 별점 그대로다**(사용자 결정
 * 2026-09-22). 시안은 선호도를 백분율로 그리지만 계약이 주는 값은 5점 만점 별점이고
 * (API-041 `averageScore`), 백분율로 환산하면 같은 값이 화면마다 다른 수로 보인다.
 */
import AsidePanel from '../AsidePanel';
import { kernelSprite } from '../PopcornBucket/sprites';
import styles from './PopcornFlavorPanel.module.css';
import type { PopcornBucket } from '../../types/taste';

type Props = {
  /** 카드 제목. 3.1은 `나의 취향 팝콘`, 7.1은 `팝콘 분포도`다 */
  title: string;
  bucket: PopcornBucket;
  /** 제목 오른쪽 화살표가 가는 곳 */
  to: string;
  linkLabel: string;
};

export default function PopcornFlavorPanel({ title, bucket, to, linkLabel }: Props) {
  const flavors = [...bucket.flavors]
    .filter((flavor) => flavor.popcornCount > 0)
    .sort((a, b) => b.popcornCount - a.popcornCount);

  if (flavors.length === 0) return null;

  return (
    <AsidePanel title={title} to={to} linkLabel={linkLabel} tone="dark">
      <table className={styles.flavorTable}>
        <thead>
          <tr>
            <th scope="col" className={styles.flavorNameHead}>
              맛
            </th>
            <th scope="col">감상량</th>
            <th scope="col">선호도</th>
          </tr>
        </thead>

        <tbody>
          {flavors.map((flavor) => (
            <tr key={flavor.flavorCode}>
              <th scope="row" className={styles.flavorName}>
                <img className={styles.kernel} src={kernelSprite(flavor.flavorCode)} alt="" />
                {flavor.displayName}
              </th>

              <td className={styles.flavorShare}>
                {Math.round((flavor.popcornCount / bucket.totalCount) * 100)}%
              </td>

              <td className={styles.flavorScore}>
                {flavor.averageScore === null ? '—' : `★ ${flavor.averageScore.toFixed(1)}`}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </AsidePanel>
  );
}
