# Pipeline 근거와 인계 자료

Jira: [S15P21E106-295](https://ssafy.atlassian.net/browse/S15P21E106-295)

추천·데이터 저장소 내부이므로 `docs/recommendation/`을 중첩하지 않고 여기서 관리한다.
루트 `docs/`는 기존 API·DB·아키텍처·협업 문서 위치를 유지한다.

1. [ALS 기준선](als-baseline.md): 왜 원별점 ALS를 비교 기준으로 유지했는가.
   후속 근거: [모델 5종·학습량 비교](model-comparison.md) — 입력·영화 지원량별 결과와 평가 자료의 한계.
2. [구조 콘텐츠](structured-content.md): TMDB 정보에서 개인화 신호가 있었는가.
3. [평가 기준](evaluation.md): 무엇을 채점했고 무엇을 아직 모르는가.
4. [재현·EC2 인계](reproduction.md): 지금 실행할 수 있는 것과 필요한 외부 산출물.
5. [발표 요약](presentation/README.md): 팀원이 설명할 핵심 결론과 표.
6. [출처 목록](assets/provenance.json): 원본 경로·해시, 복사한 소형 집계·감사 결과.
7. [이번 인계 검증](verification.md): 실행한 테스트·독립 검토와 미검증 범위.

이 자료는 **확정된 연구 근거의 인계**다. 공식 API·모델 계약과 서비스 정책을
새로 확정하지 않는다. 팝콘 외부 표현 8개와 실제 별점 0.5 단위는 유지한다.
군집 알고리즘·최종 입력 K·취향/발견 정책·온보딩 편의성은 여기서 채택하지 않는다.

후속 완료 연구: [평점 이력 미지원 영화의 콘텐츠 예측](cold-item-content.md).
대중성 반응의 제한적 개선 근거와 ALS 비교의 한계를 함께 정리했다.
