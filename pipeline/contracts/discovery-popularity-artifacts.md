# DISCOVERY 그룹 내부 대중성 산출물 계약

`discovery-popularity-artifacts-v1`은 DISCOVERY가 사용자별로 선택한 영화 그룹 안에서 후보를
고르는 공통 순서다. 이 순서는 모든 사용자에게 같으며 S15P21E106-483의 후보 부족분용
POPULAR fallback과는 별도다. 번들 생성·로컬 검증은 S15P21E106-415, HDFS 게시와 두 URI의
운영 활성화·rollback은 S15P21E106-482가 담당한다.

## 산출 방식

1. TMDB는 전체 서비스 catalog의 `raw_vote_count_number`를 사용한다. 평균별점과 시점 의존적인
   `popularity`는 그룹 내부 대중성 순서에 사용하지 않는다.
2. KOBIS는 연도별 직접 코드 영화 전체 rollup의 마지막 관측 누적 관객수를 사용한다.
3. 두 원값은 단위와 모집단 크기가 다르므로 직접 비교하지 않는다. 각 출처 전체 모집단 안에서
   동률 중간 순위를 보존한 0~1 백분위로 변환한다. 각 출처의 최하위는 0, 최상위는 1이다.
4. 영화별 공통 점수는 `max(tmdb_vote_percentile, kobis_admissions_percentile)`이다. KOBIS가
   없으면 TMDB 백분위만 사용하며 KOBIS 결측을 0명으로 해석하지 않는다.
5. 점수 내림차순, `service_movie_id` 오름차순으로 불변 순위를 만든다.

연도별 KOBIS rollup 2,176편 전체가 KOBIS 백분위의 기준 모집단이다. 다만 서비스 영화에 점수를
붙일 때는 `verification_status=VERIFIED`이고 catalog의 `service_movie_id↔tmdb_id`와 일치하며
rollup의 영화코드·관객수가 같은 연결만 사용한다. 제안 상태 연결은 비교 분석에는 사용할 수 있지만
서비스 산출물에는 승격하지 않는다.

## DISCOVERY 적용 순서

```text
사용자 이력 → 저이력·고궁합 그룹 최대 10개 선택
           → 각 그룹에서 공통 대중성 순위 상위 최대 10편 선택
           → 선택된 최대 100편만 사용자 개인 예측 점수로 최종 정렬
```

따라서 대중성은 후보 진입을 결정하고, ALS·콘텐츠 개인 점수는 진입한 후보의 사용자별 최종 순서만
결정한다. 개인 점수가 높은 영화라도 그룹 대중성 상위 10편에 들지 않으면 DISCOVERY 후보가 아니다.

## 디렉터리

```text
<root>/discovery-popularity/<popularity-version>/
├── ranking.parquet
├── metadata.json
├── manifest.json
└── _SUCCESS
```

`metadata.json`은 입력 파일 SHA-256, 전체 KOBIS 모집단 수, 검증 연결 적용 수, mapping revision과
정책을 기록한다. Consumer는 그룹 산출물과 대중성 산출물이 같은 `mapping_revision`일 때만 시작한다.
`CONSUMER_GROUP_URI`와 `CONSUMER_DISCOVERY_POPULARITY_URI`는 함께 설정하거나 함께 비워야 한다.
