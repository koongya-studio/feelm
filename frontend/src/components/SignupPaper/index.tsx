/*
 * 가입 정보를 적는 종이와 타자기.
 *
 * 타자기가 화면 아래에 붙고 입력 칸은 그 롤러에서 올라오는 종이 위에 선다
 * (팀 결정 2026-09-14, S15P21E106-319). 처음에는 첫 줄만 나와 있고 한 줄에 무엇이든
 * 입력하면 다음 줄이 밀려 나온다. 나온 줄은 입력을 지워도 다시 들어가지 않는다.
 *
 * 0.4 이메일 회원가입이 쓰던 것을 0.3-A 소셜 가입 정보 입력도 쓰게 되어 공통으로
 * 올렸다(frontend-structure.md 5.1, 업무 S15P21E106-499). 줄 번호와 무엇을 입력받을지는
 * 화면마다 다르므로 여기서는 종이·줄·타자기만 맡고 필드는 화면이 넣는다.
 *
 * **폰 키보드가 올라오면 타자기 키를 감춘다**(업무 -718, 사용자 결정 2026-09-20). 입력 칸을
 * 누르면 OS 키보드가 화면 아래를 덮어 타자기와 겹쳤다 — 키보드가 두 개로 보였다. 입력
 * 중에는 진짜 키보드가 타자기 역할을 하므로 둘 다 있을 이유가 없고, 키보드가 닫히면 다시
 * 나온다. **롤러는 남겨 종이가 나오는 연출을 잇는다** — 둘 다 치우면 종이가 허공에 뜬다.
 * 키보드가 뜨지 않는 환경(PC)에서는 아무것도 바뀌지 않는다(`hooks/useOnScreenKeyboard`).
 */
import { useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import Typewriter from './Typewriter';
import { useOnScreenKeyboard } from '../../hooks/useOnScreenKeyboard';
import { PaperContext, useSignupPaper } from './paperContext';
import styles from './SignupPaper.module.css';

/** 줄이 열리는 시간. `--motion-emphasized`와 같다 */
const FEED_MS = 420;

function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

type Props = {
  /** 제출. 기본 동작(`preventDefault`)은 여기서 막는다 */
  onSubmit: () => void;
  children: ReactNode;
};

export default function SignupPaper({ onSubmit, children }: Props) {
  const scrollerRef = useRef<HTMLDivElement>(null);
  const paperRef = useRef<HTMLFormElement>(null);
  const releaseKey = useRef<number | undefined>(undefined);

  const [fed, setFed] = useState(1);
  const [typed, setTyped] = useState<string | null>(null);
  const keyboard = useOnScreenKeyboard();

  /* 나온 줄은 다시 들어가지 않는다 */
  const feed = (line: number) => setFed((current) => Math.max(current, line));

  /* 글자 하나를 친다. 키가 눌리고 종이가 롤러에 한 번 튄다 */
  const type = (data: string | null) => {
    if (!data) {
      return;
    }

    setTyped(data[data.length - 1]);
    window.clearTimeout(releaseKey.current);
    releaseKey.current = window.setTimeout(() => setTyped(null), 110);

    if (!prefersReducedMotion()) {
      // jsdom에는 Web Animations가 없다
      paperRef.current?.animate?.(
        [{ transform: 'translateY(-3px)' }, { transform: 'translateY(0)' }],
        { duration: 140, easing: 'cubic-bezier(0.2, 0, 0, 1)' },
      );
    }
  };

  useEffect(() => () => window.clearTimeout(releaseKey.current), []);

  /*
   * 줄이 나오면 롤러가 제자리에 있도록 아래 끝을 따라간다. 종이가 화면보다 길어진 뒤에도
   * 새 줄이 올라오는 모습이 보여야 한다.
   */
  useEffect(() => {
    if (fed === 1) {
      return;
    }

    const timer = window.setTimeout(() => {
      const scroller = scrollerRef.current;

      scroller?.scrollTo?.({
        top: scroller.scrollHeight,
        behavior: prefersReducedMotion() ? 'auto' : 'smooth',
      });
    }, FEED_MS);

    return () => window.clearTimeout(timer);
  }, [fed]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };

  return (
    <PaperContext.Provider value={{ fed, feed, type }}>
      {/*
       * 키보드가 덮은 만큼 아래 자리를 줄인다(업무 -718).
       *
       * 종이는 `::before`의 남는 자리에 밀려 아래에 붙어 있다. 키보드가 올라오면 그 자리가
       * 키보드 뒤로 들어가는데, **스크롤로는 못 올린다** — 내용이 짧을 때는 넘치는 높이가
       * 없어 스크롤할 여지가 아예 없다(재 보니 `scrollHeight`와 `clientHeight`가 720으로
       * 같았다). 그래서 아래 여백으로 자리를 줄여 종이가 키보드 위에 서게 한다.
       */}
      <div
        ref={scrollerRef}
        className={styles.page}
        style={{ paddingBottom: keyboard.inset || undefined }}
      >
        <form
          ref={paperRef}
          className={styles.paper}
          onInput={(event) => type((event.nativeEvent as InputEvent).data)}
          onSubmit={submit}
        >
          {children}
        </form>

        <Typewriter typed={typed} hidden={keyboard.open} />
      </div>
    </PaperContext.Provider>
  );
}

/** 종이 제목. 아래에 이동 링크 같은 것을 함께 둘 수 있다 */
export function SignupPaperHeading({
  eyebrow,
  title,
  children,
}: {
  eyebrow: string;
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className={styles.heading}>
      <p className={styles.eyebrow}>{eyebrow}</p>
      <h2 className={styles.title}>{title}</h2>
      {children}
    </div>
  );
}

/**
 * 종이의 한 줄. 아직 나오지 않은 줄은 높이가 0이고 `inert`라 초점도 가지 않는다.
 *
 * 높이는 `grid-template-rows` 0fr → 1fr로 연다. 줄마다 높이를 재지 않아도 오류 문구가
 * 붙어 줄이 길어지면 그대로 따라간다.
 */
export function SignupPaperLine({ line, children }: { line: number; children: ReactNode }) {
  const { fed } = useSignupPaper();
  const open = fed >= line;

  return (
    <div className={styles.line} data-fed={open} inert={!open}>
      <div className={styles.lineClip}>
        <div className={styles.lineBody}>{children}</div>
      </div>
    </div>
  );
}
