# 339·340·341: 완료한 과거 연구와 현재 v1의 관계

세 업무를 폐기하지 않고 **실제로 끝낸 로컬 실험과 채택 한계**로 Jira를 갱신했다.
외부 상태는 로컬 완료·결과 공유/리뷰 대기이며, 387의 게시와 연결한다.
당시 T0/B/P2_010을 남긴 결론이 현재 v1 기본 설정이라는 뜻은 아니다.

| 업무 | 실제 비교와 결론 | 현재 v1에 넘기는 근거 |
| --- | --- | --- |
| [339](https://ssafy.atlassian.net/browse/S15P21E106-339) | FM T0→TMDB→Wiki기본→Wiki복원, 후속 CLEAN. Top2 유효260명의 NDCG@2 0.8450/0.8368/0.8320/0.8338. CLEAN0.8313. 사전 개선 기준 미통과 | 시험한 텍스트·정제의 채택 근거 부족. 텍스트 전체가 무용하다는 결론 아님 |
| [340](https://ssafy.atlassian.net/browse/S15P21E106-340) | FM B/R/H/RH와 개인 반응 제거·GBT·ALS 보조. 총7회 학습, 당시 사전 기준 미통과로 B유지 | 근거 보정으로 투표1개·평균10점 노출342/372→0/372였지만 품질 개선과 구분. ALS 지원/미지원 비교를 분리 |
| [341](https://ssafy.atlassian.net/browse/S15P21E106-341) | 원19장르 P0/P1/P2. 입력 있는158명의 P2_010 첫3편 별점3.845992→3.841772, novelty0.517471→0.550321. 당시 자격조건 통과 | 전체 카탈로그186명·558슬롯 모두UNKNOWN, 실제 발견 제공15명. 서비스 채택 NOT_ESTABLISHED |

339/340에는 일부 런의 엄격 메모리 상한 예외와 계측 공백을 원본 기록대로 보존했다.
341은345개 수치·상태 셀 검산 불일치0의 로컬 감사 PASS다. **현재8×128·10×10 정책을 실행한 결과가 아니다.**
새 모델 학습·v1 정책 replay·API/DB·EC2·한국 사용자 만족도는 미실행이다.

근거:

- [text339](../assets/service-v1/research-history/text339/README.md), [검산](../assets/service-v1/research-history/text339/result-review.json), [CLEAN](../assets/service-v1/research-history/text339-clean/README.md)
- [foundation340](../assets/service-v1/research-history/foundation340/EXECUTION.md), [combination340](../assets/service-v1/research-history/combination340/EXECUTION.md)
- [policy341](../assets/service-v1/research-history/policy341/EXECUTION.md), [최종 정리](../assets/service-v1/research-history/final341/README.md), [검산](../assets/service-v1/research-history/final341/result-review.json)

이 자료는 선별한 역사 원문이다. 내부 경로·당시 계획·후속 지시는 현재 실행 지시가 아니며,
누락한 전체 실행 의존성과 과거 문서의 해시 의미는 [재현 안내](REPRODUCTION.md#historical-records)를 따른다.
