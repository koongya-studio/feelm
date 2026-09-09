/*
 * Feelm 워드마크.
 *
 * 확정 로고는 **Gluten Bold 700 · 자간 -0.03em**이다
 * (`docs/screens/design-concept.md` 2.6). 이전에는 Kaushan Script 글리프를 path로
 * 박아 둔 SVG였으나, 그 서체는 폐기됐다.
 *
 * SVG가 아니라 텍스트로 그린다. 색은 `currentColor`를 따르므로 밝은 면과 어두운
 * 면 어디에나 올릴 수 있고, 크기를 바꿔도 선명하다. 아웃라인 SVG로 뽑으면 서체
 * 로딩과 무관해지지만, 지금은 Gluten을 번들에 포함하고 있어 텍스트로 충분하다.
 *
 * `aria-label`을 붙여 스크린 리더가 한 단어로 읽게 한다. 자간을 좁혀 두어 글자
 * 단위로 끊어 읽힐 여지를 없앤다.
 */
import styles from './Wordmark.module.css';

type Props = {
  /**
   * 글자 크기(px). 헤더 기본값은 22px이다.
   *
   * 확정 시안이 300px 폭 목업에서 18px을 썼다. 375px 기준으로 환산하면 22.5px이다.
   * 이전 값 27px은 `--font-size-title`을 그대로 다러 쓴 것이었고 시안보다 컸다.
   */
  size?: number;
  /**
   * 색 처리. 기본은 브랜드 빨강이다. `inherit`는 부모 색을 따르며, 어두운 면이나
   * 사진 위처럼 빨강이 안 읽히는 자리에 쓴다.
   */
  tone?: 'brand' | 'inherit';
};

export default function Wordmark({ size = 22, tone = 'brand' }: Props) {
  return (
    <span
      className={styles.wordmark}
      style={{ fontSize: size }}
      data-tone={tone}
      aria-label="Feelm"
    >
      Feelm
    </span>
  );
}
