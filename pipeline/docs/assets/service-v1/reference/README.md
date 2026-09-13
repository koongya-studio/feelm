# RH230 계산 참고 코드

생산 Spark/Consumer가 이 폴더를 자동 import하지 않는다. 원본 함수·테스트를 선별 이관했으며
새 KOBIS245 구현은 포함하지 않는다. 테스트 명령과 환경은 [재현 안내](../../../service-v1/REPRODUCTION.md)를 따른다.

| 파일 | 역할 |
| --- | --- |
| rec046_common / rec047_features / cold_item_features | 공통 통계·기존 영화 특징 |
| foundation340_features / text339_relations | 사용자 반응·근거량과 RH230 표현 |
| final344_adapter | RH230 특징 배열 생성 |
| combination340_models | ALS 직접 지원/fold-in 및 GBT native tree 계산 |
| final344_evaluate | 관측 후보 평가 수식. CLI 전체 의존성은 미포함 |
| test_final344_adapter / test_final344_evaluate | 실제 외부자료가 필요 없는 기존 합성 unittest33개 |

원본 위치·SHA256과 개행 정규화 후 SHA는 [출처](../provenance.json)에 있다.
33개 테스트는 특징 생성·평가/선정 수식을 검사하며 FM portable·GBT tree·ALS fold-in을 호출하는 추론 검증은 아니다.
함수 재사용 전에 서비스 ID·245열·원본 이력·보정·버전 계약을 맞추고 새 native parity를 검증해야 한다.
