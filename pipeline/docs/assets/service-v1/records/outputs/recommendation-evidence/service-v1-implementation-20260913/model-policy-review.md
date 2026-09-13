# 서비스 v1 모델·정책 계약 검토

판정: **PASS — 문서의 모델·특징·그룹·정책 연결 범위**. 2026-09-13.
검토자: `/root/fm_gbt_service_fit`. 정확한12개 문서 버전과 근거는
[JSON 검토 기록](../../../../../../service-v1/REPRODUCTION.md#historical-records), [정적 검사37개](../../../../../../service-v1/REPRODUCTION.md#historical-records)에 있다.

새245열 GBT/FM 학습, KOBIS 수집, 특징/그룹 추론, native-serving parity, 운영 테스트나 배포를
수행한 판정이 아니다. EVALUATION의 전문 통계 설계 판정은 별도 검토자가 담당한다.

## 확인한 계약

- 기본은 GBT 단독이다. ALS/FM는 비교 adapter이며, ALS 지원 여부에 따른 자동 덮어쓰기나
  shrink100 혼합은 없다. ALS에는 평가와 영화 factor만 들어가며 외부 대중성 지표를 직접 넣지 않는다.
- 원 RH230의 이름·순서·변환 소스7개를 보존한다. 실제 선택된 기존 GBT의 TMDB평점은
  `(vR+48×6.173088067675869)/(v+48)/10`이다. 후보 순위의 별도 prior300과 바꾸지 않는다.
- KO15는 후보3열과 사용자 반응12열이다. m/H·원본 별점 평균과 기존 TMDB의 매핑 후 k
  분모를 구분하며, m0/1·분산0·후보 결측·실제 관객0의 의미가 명시돼 있다.
- 후보·이력의 외부 값은 같은 snapshot으로 갱신하고 학습 q99/prior는 고정한다. 높은 대중성
  값을 모든 사용자 예상 별점에 강제 가산하지 않는다. 유효 이력은 실제 원천 근거로 판정한다.
- 최신 원본 H≤10을 먼저 고른 뒤 매핑하고 보충하지 않는다. 학습은 원499만 행의 cap0/1/5/10/30,
  엄격한 과거 이력을 유지한다. 서비스 ID가 없다는 이유로 학습 행을 제거하지 않는다.
- B1/B2/B3는 각각230/233/245열이다. 네 view의 행 집합·마스크는 공통이고 GBT는0.25 weight,
  FM은 네 view를 균등하게 모두 넣는 unweighted 방식이다. FM weightCol의 작동을 가정하지 않는다.
- 기존 모델도 같은45명으로 다시 보정한다. 선정45/확인180의 역할과 기존에 열어본 개발
  자료라는 한계가 명시돼 있다. ALS 미지원0점·UNKNOWN을 정답으로 만들지 않는다.
- 고정 상위8 중심과 K128 하위 중심/mean의 서로 다른 원본을 allowlist로 결합한다. 실제 옛
  final bundle 전체를 불러오면 fixed16/B50/Q25와 router가 들어오는 문제를 차단했다.
- 그룹 배정은 float64 고정 축 거리와 낮은 ID 동률 규칙이다. 검색은 단위 재정규화하지 않은
  frozen mean이다. top/sub 지원0의 진단 label과 발견 eligibility를 구분한다.
- 배치의 최대500편 모두를 현재 입력으로 재정렬한다. 발견은 실제 공급 그룹 최대10개에서
  각 최대10편이며, 전체 P≤500/D≤100에서 맞춤2→발견1 순서로 조립한다. 모델과 정책을 바꾸는
  대체 동작 없이 부족·초기·미준비 상태를 구분한다.

## 검토 중 보완한 사항

| 문제 | 반영 위치·해결 |
| --- | --- |
| K128 파일만으로 전처리/상위8 복원이 불가능 | GROUPS §1·group-recipe의 원파일9개 SHA와 허용키로 구성 고정 |
| 0벡터에 붙은 임의 label이 발견/감상 분모에 들어갈 수 있음 | POLICY/GROUPS에서 group_mapped=top_supported AND sub_supported; 원본 감상/별점은 유지 |
| 원230 관계 코드 하위 의존성 pin 누락 | feature-schema에 text339_relations.py 해시 추가 |
| 원학습 데이터에 없는 service_movie_id를 mask 키로 요구 | MODELS §5~6·training-recipe에서 ML ID와 원row-lineage 복원·seal 고정 |
| 새 GBT 대조별 차원과 FM 횟수가 기계 설정에 없음 | B0/B1/B2/B3 차원·featureVersion·각 index, 최대GBT3/FM1·FM=B3 명시 |
| B1−B0를 결측만의 순수 효과로 오인 가능 | 자연1view B0와 fourview B1의 학습 절차 차이 명시; 새 학습 추가 요구 없음 |
| 유효 GBT 이력을 임의로 판정할 여지 | model_input_supported를 실제 RH230 입력행·실제 원천 근거로 정의 |
| 오차·순위·UNKNOWN·비교 분모가 구현자 해석에 의존 | EVALUATION에 관측 목표/정책 재생/shadow 분리·수식 추가; 통계 전문판정은 별도 |

GROUPS와 group-recipe는 검토 중 부모 요청으로 이 검토자가 작성했다. 이 두 파일을 스스로
독립 검토했다고 표현하지 않는다. 부모의 별도 [그룹 독립 검토](../../../../../../service-v1/REPRODUCTION.md#historical-records)가
같은 두 파일 SHA를 대상으로 PASS이며, 이 기록은 다른 문서와의 연결 및 현물 구조를 확인한다.

## 실행 준비 상태

정적 검사37/37은 JSON구조·특징 이름·학습 설정·원본 SHA/bytes·실제 pickle/NPZ 배열 구조
확인이다.237,817편을 새로 변환하거나 추천한 검사가 아니다. 현재 막히는 모델·정책 문서
불일치는 없지만 실제 서비스 준비에는 다음이 남아 있다.

1. KOBIS 진단 프레임·동일 범위 매핑·최소 평가 표본 확인과 별도 수집 검토.
2. 원row-lineage/특징 재현, fourview 준비 코드·자원 사전 검사.
3. 새245열 학습·공통 보정·고정 평가/안전 한계 검증.
4. 새 모델 native-serving parity와 고정 그룹 전처리/할당 parity.
5. 실제 배치·Consumer·API·이벤트·migration·실패 복구·환경별 성능 검증.

이 단계가 남아 있으므로 문서 PASS를 `modelReadiness=READY`, 한국 사용자 만족도 검증,
팀 계약 승인 또는 서버 배포 완료로 바꾸어 보고하지 않는다. 외부 서비스 변경·commit·push는 하지 않았다.
