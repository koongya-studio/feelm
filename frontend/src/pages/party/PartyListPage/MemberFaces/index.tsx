/*
 * 파티원 얼굴 줄 — 데스크톱 (업무 S15P21E106-785).
 *
 * 사진이 있으면 동그라미에 사진을, 없으면 닉네임 첫 글자를 담는다(업무 -762). 412px 이하의
 * `Initials`와 같은 규칙이라 같은 화면에서 좁을 때와 넓을 때 서로 다른 얼굴이 나오지 않는다.
 * 사진은 파티 응답의 `owner` · `members[]`에만 온다(업무 -811).
 *
 * 모바일의 것을 그대로 쓰지 않고 따로 둔 것은 **크기와 줄 구성이 다르기 때문**이다. 시안의
 * 우측 패널은 56px 얼굴 아래에 이름과 역할 배지를 세우고, 카드 안의 줄은 32px 얼굴 사이에
 * `×`를 끼운다. 모바일 것은 겹쳐 놓는 무리다.
 */
import styles from './MemberFaces.module.css';

export type Face = {
  key: string;
  nickname: string;
  /** 프로필 사진. 없으면 첫 글자를 담는다 */
  avatarUrl?: string | null;
  /** 얼굴 아래 배지. 우측 패널에서 `방장` · `멤버`를 적는다 */
  badge?: string;
};

type Props = {
  faces: Face[];
  /**
   * `panel` 우측 패널 — 56px 얼굴 · 이름 · 배지
   * `row`   카드 안의 줄 — 32px 얼굴 · 이름, 사이에 `×`
   */
  variant: 'panel' | 'row';
  /** 어두운 면 위에 놓일 때. 카드 하나가 검정이다 */
  tone?: 'dark';
};

export default function MemberFaces({ faces, variant, tone }: Props) {
  return (
    <ul className={styles.faces} data-variant={variant} data-tone={tone ?? null}>
      {faces.map((face, index) => (
        <li key={face.key} className={styles.face}>
          {/*
           * `×`는 장식이다. 시안이 얼굴 사이에 둔 것이며 읽기 도구에는 닉네임만 남는다.
           * 첫 얼굴 앞에는 두지 않는다.
           */}
          {variant === 'row' && index > 0 ? (
            <span className={styles.cross} aria-hidden="true">
              ×
            </span>
          ) : null}

          <span className={styles.person}>
            <span className={styles.mark} aria-hidden="true">
              {face.avatarUrl ? (
                <img className={styles.photo} src={face.avatarUrl} alt="" loading="lazy" />
              ) : (
                ([...face.nickname][0] ?? '?')
              )}
            </span>

            <span className={styles.name}>{face.nickname}</span>

            {face.badge ? <span className={styles.badge}>{face.badge}</span> : null}
          </span>
        </li>
      ))}
    </ul>
  );
}
