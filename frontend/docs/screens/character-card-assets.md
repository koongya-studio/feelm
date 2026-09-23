# 캐릭터 카드 16종 이미지 제작 (업무 S15P21E106-781)

취향 4컷의 **04 칸**에 들어갈 그림 16장을 만드는 절차다. 카드 16종과 축 판정은
`docs/api/report-trend.md` API-060에서 확정됐고(업무 -618), 화면이 코드로 그림을 찾는 구조는
`src/components/ReportFourCut/characterCards.ts`에 있다(업무 -780).

이 문서는 **프롬프트와 검수 기준만** 정한다. 생성은 담당자가 직접 한다.

## 1. 왜 실사인가

01 장르 · 02 감독 · 03 배우 칸은 실제 영화 스틸과 실존 인물 사진이다. **04만 그림이면 질감이
깨진다.** 게다가 04는 4컷 중 유일한 컬러 칸이라 가장 먼저 눈에 띈다. `src/assets/popcorn/`
계열의 파스텔 3D 렌더는 팝콘 버킷 톤이라 여기에 맞지 않는다.

퍼블릭 도메인 원형에는 사진이 존재하지 않으므로 실사풍을 **생성해야** 한다. 이때 일러스트에는
없던 문제가 둘 생기고, 3장과 5장이 각각 그것을 막는다.

## 2. 규격

`ReportFourCut.module.css`의 시안 실측값 기준이다.

| 항목 | 값 |
| --- | --- |
| 칸 비율 | 355 : 246 (약 1.44 : 1) **가로형** |
| 최소 크기 | @3x 기준 **690 × 480** 이상 |
| 포맷 | WebP (기존 에셋 관례) |
| 색 | 컬러. 01 ~ 03이 흑백이라 04만 컬러다 |
| 파일 경로 | `src/assets/reports/character/<code>.webp` (예: `RDMV.webp`) |
| 누끼 | **만들지 않는다.** 화면은 누끼를 쓰지 않는다 |

두 가지 크기로 쓰이는 점이 구도를 정한다.

- 상세(3.4 · 7.4)에서 4컷 폭이 248px이라 칸은 약 **229 × 159 CSS px**이다
- 목록(7.3)에서 4컷 폭이 88px이라 칸은 약 **57 × 39 px**이다.
  **얼굴 클로즈업 구도가 아니면 이 크기에서 식별되지 않는다**
- 칸 아래 약 **38%** 를 검정 그라데이션이 덮는다. 얼굴과 눈은 **위쪽 60% 안**에 두고,
  그 아래에는 중요한 것을 두지 않는다

## 3. 프롬프트 규칙

### 3.1 이름을 넣지 않는다

**캐릭터 이름을 프롬프트에 쓰면 영상화 판권이 살아 있는 디자인이 그대로 나온다.** 원작은
퍼블릭 도메인이어도 영화 · 게임 · 광고가 만든 디자인은 현행 권리가 있다. 그래서 아래 프롬프트는
전부 **장면 묘사**뿐이고 인물명 · 작품명 · 배우명이 없다.

카드 이름도 보통명사다. 퍼블릭 도메인 원형은 설명문 안에서 비유로만 쓴다(업무 -618).

### 3.2 고정 접미사

16장을 한 세트로 보이게 하려면 아래를 **모든 프롬프트 끝에 똑같이** 붙이고, 스타일 고정
장치(seed 등)를 16장 내내 같은 값으로 둔다.

```text
photographic portrait, 35mm film still, shallow depth of field, single subject
facing camera, head and shoulders filling the upper two thirds of a horizontal
frame, muted desaturated color with one warm accent, soft directional key light
from the left, plain dark neutral background, no text, no logo, no watermark,
no border, 3:2 landscape
```

- `head and shoulders filling the upper two thirds` 가 2장의 두 요구(목록 크기 식별 ·
  하단 38% 회피)를 동시에 맞춘다
- `muted desaturated color with one warm accent` 로 16장의 색을 묶는다.
  생성 후 **같은 색보정을 일괄 적용**한다
- `no text` 가 없으면 생성기가 글자를 넣고, 그 글자가 인화지 서체와 싸운다

### 3.3 영상화 디자인 참조 금지

원작은 전부 퍼블릭 도메인이지만 아래는 현행 권리가 살아 있다. 결과물이 이 모습이면
**다시 생성한다.**

| 코드 | 금지 대상 | 걸리는 표식 |
| --- | --- | --- |
| `ELHS` | 디즈니 앨리스 | 하늘색 원피스 · 흰 앞치마 · 검정 머리띠 |
| `RLHS` | 디즈니 신데렐라 | 하늘색 볼가운 · 특정 머리 모양 |
| `RLHV` | 애니메이션 · 드라마판 빨간 머리 앤 | 특정 배우의 얼굴 · 애니메이션 특유의 눈 비례 |
| `EDMV` | 유니버설 프랑켄슈타인 | 목 볼트 · 평평한 머리 · 초록 피부 |
| `RDHV` | 휴 잭맨 영화판 · 게임 반 헬싱 | 넓은 검정 모자 + 긴 가죽 코트 조합 |
| `RDMV` | BBC · 가이 리치 영화 디자인 | 특정 배우의 얼굴 · 디어스토커 + 인버네스 세트 |

원작 **초판 삽화**는 삽화가도 퍼블릭 도메인이라 레퍼런스로 쓸 수 있다(테니얼의 앨리스,
도레의 판화 등). 다만 프롬프트에는 삽화가 이름도 넣지 않고 장면 묘사로만 옮긴다.

## 4. 프롬프트 16장

각 줄 앞에 3.2의 고정 접미사를 붙인다. 축은 `새로움·익숙함 / 밝음·어두움 / 후함·박함 /
많음·적음` 순이다(업무 -814에서 이름을 바꿨고 코드 네 글자는 그대로다).

| 코드 | 카드 이름 | 장면 묘사 (이름 없음) |
| --- | --- | --- |
| `ELHV` | 길 위의 낙천가 | a gaunt elderly man in dented improvised armor, thin white beard, wide delighted eyes fixed on something far off, dust and open plain light behind him |
| `ELHS` | 토끼굴에 빠진 아이 | a young girl mid-fall with hair lifting upward, startled curious eyes, dark vertical shaft of light around her, small brass key glinting |
| `ELMV` | 세계 일주하는 수집가 | a weathered merchant traveler in layered eastern and western textiles, calm appraising gaze, rolled charts and a small ledger at his shoulder |
| `ELMS` | 풀숲의 관찰자 | an old naturalist in a dark field coat crouched low, magnifying lens raised, patient absorbed expression, tall summer grass blurred close to the lens |
| `EDHV` | 복수자 | a sharp-featured man in a dark nineteenth century coat, high collar, cold steady eyes, one gloved hand resting on a folded list of names |
| `EDHS` | 돌아오지 못한 자 | a young musician in draped antique cloth holding a small lyre, head turned back over his shoulder, grief opening on his face, darkness behind |
| `EDMV` | 금기를 여는 자 | a young scientist in a stained laboratory apron, lamp lit from below, exhausted brilliant eyes, glass apparatus catching light at the frame edge |
| `EDMS` | 심연을 들여다본 학자 | an aging scholar in a heavy dark robe in a candlelit study, deep-set eyes reading something he regrets, towering shelves falling into shadow |
| `RLHV` | 이야기 많은 아이 | a freckled girl with long braided red hair resting her chin on one hand, bright daydreaming eyes turned upward, farmhouse field and low cloud behind |
| `RLHS` | 무도회를 기다린 사람 | a young woman in a plain grey work dress by a shuttered window, hair tied back, hopeful patient eyes turned toward the light, hearth ash on her sleeve |
| `RLMV` | 동네 꾀돌이 | a barefoot boy in a straw hat and suspenders leaning on a whitewashed fence, sideways scheming grin, riverbank summer haze behind |
| `RLMS` | 등대지기 | a bearded castaway in hand-stitched goat hide, wide-brimmed shaggy hat, steady measuring eyes, notched tally stick and distant grey sea |
| `RDHV` | 잠 못 드는 파수꾼 | an older bearded man in a plain dark waistcoat and rolled shirtsleeves, lantern held low, alert sleepless eyes, no hat and no long coat |
| `RDHS` | 비극의 주인공 | a young prince in black mourning velvet, unresolved troubled eyes, hand half-raised as if about to speak, cold stone hall behind |
| `RDMV` | 안락의자 탐정 | a lean sharp-eyed man in a plain dark dressing gown seated in a deep armchair, fingertips pressed together, pipe smoke drifting, no cap and no cape |
| `RDMS` | 금고지기 | a thin miserly old man in a threadbare nightshirt and cap, single candle, guarded suspicious eyes, ledger and coin box in the near dark |

금지 표식을 프롬프트에서 미리 밀어낸 곳이 둘이다. `RDHV`는 `no hat and no long coat`,
`RDMV`는 `no cap and no cape`다. 나머지는 4장의 장면 묘사 자체가 금지 디자인과 다른
옷 · 소도구를 지정한다. `RLHV`는 영상화판이 실사 · 애니메이션 양쪽에 있어 표식을 한 마디로
못 박기 어렵다 — 검수 때 얼굴을 직접 대조한다.

## 5. 검수 체크리스트

16장 전부에 대해 하나씩 확인하고 기록을 남긴다. 한 장이라도 걸리면 그 코드만 다시 생성한다.

- [ ] **초상권** — 결과물이 실존 배우나 유명인과 닮지 않았다. 실사풍은 일러스트에 없던
      초상권 문제를 만든다. 얼굴이 낯익으면 무조건 다시 생성한다
- [ ] **영상화 디자인** — 3.3 표의 금지 표식이 없다
- [ ] **이름** — 사용한 프롬프트에 인물명 · 작품명 · 배우명 · 삽화가명이 없다
      (프롬프트 원문을 함께 보관한다)
- [ ] **구도** — 얼굴과 눈이 위쪽 60% 안에 있다. 아래 38%에 중요한 것이 없다
- [ ] **목록 크기** — 57 × 39px로 줄여도 누구인지 구별된다
- [ ] **규격** — 355 : 246 가로형이고 @3x 690 × 480 이상, WebP다
- [ ] **글자** — 그림 안에 글자 · 로고 · 워터마크 · 테두리가 없다
- [ ] **세트** — 16장을 나란히 놓았을 때 한 세트로 보인다. 같은 색보정이 적용됐다
- [ ] **연결** — `characterCards.ts`의 `CARD_IMAGE` 16칸이 모두 채워지고 화면에서 16종이
      전부 그려진다

## 6. 넣는 순서

1. `src/assets/reports/character/<code>.webp` 16장을 넣는다
2. `src/components/ReportFourCut/characterCards.ts`의 import와 `CARD_IMAGE`를 채운다
3. `characterCards.test.tsx`의 "그림이 없는 코드와 모르는 코드는 모두 null이다"가
   16종 전부 null을 전제하므로, 그림이 찬 뒤의 전제로 바꾼다
4. 목록(7.3)과 상세(3.4)에서 실제 크기로 눈으로 확인한다
5. API-061 저장 PNG는 이 `ReportFourCut` DOM 자체를 캡처하므로 Backend에 에셋을 복사하지
   않는다. 16장의 정본은 이 프론트 디렉터리 하나다
