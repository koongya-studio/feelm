/*
 * EXT-05 OTT 분석 데이터 부족
 *
 * 이동표: EXT-02 ⇢ EXT-05 평가 데이터가 부족한 경우 · EXT-05 ⇢ MY 영화 평가하러 가기 ·
 * EXT-05 ⇢ 3.1 취향 영화 추가하기
 *
 * 요소는 와이어프레임에서 **진행도 막대가 빠졌다**(업무 -563 · 지시서 CR-28). `현재 진행도
 * 6/10`의 분모가 될 기준 평가 수가 계약에 없다. 남는 것은 안내 문구와 버튼 둘이다.
 *
 * **언제 보이는 화면인지도 바뀌었다.** 전에는 EXT-01이 평가 수를 보고 미리 갈랐지만, 이제는
 * API-068이 `insufficientData: true`를 주거나 `items`가 빈 경우에 EXT-02가 보낸다. 서버를
 * 부르지 않는 화면이라 주소로 바로 열어도 같은 안내가 나온다.
 *
 * - `영화 평가하러 가기`가 주 동작이다. 평가해야 추천 후보가 만들어진다
 *
 * `[?]` `취향 영화 추가하기`가 3.1로 간다(이동표). 3.1에서 영화를 더하는 동작은 없다.
 */
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import { path } from '../../../routes/paths';
import styles from './OttInsufficientPage.module.css';

export default function OttInsufficientPage() {
  const navigate = useNavigate();

  return (
    <>
      <ScreenHeader title="나에게 맞는 OTT 찾기" showBack fallbackTo={path.settings()} />

      <div className={styles.page}>
        <div className={styles.stage}>
          <h1 className={styles.title}>
            조금만 더 알아가면
            <br />
            OTT를 추천할 수 있어요
          </h1>
          <p className={styles.lead}>
            영화를 더 평가하면
            <br />더 정확하게 분석할 수 있어요
          </p>
        </div>

        <div className={styles.actions}>
          <Button onClick={() => void navigate(path.myUnrated())}>영화 평가하러 가기</Button>
          <Button variant="secondary" onClick={() => void navigate(path.film())}>
            취향 영화 추가하기
          </Button>
        </div>
      </div>
    </>
  );
}
