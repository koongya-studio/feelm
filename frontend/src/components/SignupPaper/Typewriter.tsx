/*
 * 가입 화면 아래에 놓이는 타자기. 종이가 이 롤러에서 올라온다(`SignupPaper`).
 *
 * 장식이다. 실제 입력은 종이 위 칸이 받고, 여기 키는 누를 수 없다. 칸에 글자를 치면
 * 대응하는 키 하나가 잠깐 눌린 모양이 된다.
 *
 * 사진이 아니라 코드로 그린다(팀 결정 2026-09-14). 색은 무채색만 쓴다
 * (`design-concept.md` 3장 — UI에 유채색을 쓰지 않습니다).
 */
import styles from './Typewriter.module.css';

const ROWS = ['QWERTYUIOP', 'ASDFGHJKL', 'ZXCVBNM'];
const KEYS = ROWS.join('');
const SPACE = ' ';

/** 친 글자에 대응하는 키. 영문이 아니면(한글 · 숫자 · 기호) 글자 코드로 한 키를 고른다 */
function keyFor(char: string | null): string | null {
  if (!char) {
    return null;
  }

  const upper = char.toUpperCase();

  if (upper === SPACE) {
    return SPACE;
  }

  return KEYS.includes(upper) ? upper : KEYS[upper.charCodeAt(0) % KEYS.length];
}

type Props = {
  /** 방금 친 글자. 없으면 `null`이고 아무 키도 눌리지 않는다 */
  typed: string | null;
  /**
   * 키를 감출지(업무 -718). 폰 키보드가 올라오면 참이다.
   *
   * **롤러는 남는다.** 종이가 나오는 느낌을 만드는 것은 롤러이고, OS 키보드와 겹치는 것은
   * 키 부분이다. 롤러를 남기면 그 아래 자리에 OS 키보드가 들어서면서 타자기를 대신한다.
   */
  hidden?: boolean;
};

export default function Typewriter({ typed, hidden = false }: Props) {
  const pressed = keyFor(typed);

  return (
    <div className={styles.typewriter} data-hidden={hidden} aria-hidden="true">
      <div className={styles.roller} />

      <div className={styles.body}>
        {ROWS.map((row) => (
          <div key={row} className={styles.row}>
            {[...row].map((key) => (
              <span key={key} className={styles.key} data-pressed={pressed === key}>
                {key}
              </span>
            ))}
          </div>
        ))}

        <div className={styles.row}>
          <span className={styles.space} data-pressed={pressed === SPACE} />
        </div>
      </div>
    </div>
  );
}
