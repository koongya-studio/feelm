# API 명세 — 리포트·트렌드

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-059](#api-059-내-리포트-목록) | 내 리포트 목록 | GET | `/users/me/reports` | 2차 MVP | 회원 |
| [API-060](#api-060-리포트-상세-조회) | 리포트 상세 조회 | GET | `/reports/{reportId}` | 2차 MVP | 회원 |
| [API-061](#api-061-리포트-이미지-카드-생성) | 리포트 이미지 카드 생성 | POST | `/reports/{reportId}/image` | 2차 MVP | 회원 |
| [API-064](#api-064-급상승-영화) | 급상승 영화 | GET | `/movies/trending` | 2차 MVP | 공개 |

## API-059 내 리포트 목록

`GET` `/api/v1/users/me/reports`

| 항목 | 값 |
|---|---|
| 단계 | 2차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-23 |
| 화면 | ⑦ 리포트 발급, ⑪-1 취향 리포트 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

반기별 취향 리포트 목록을 조회한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "reportId": 301,
      "period": { "year": 2026, "half": "H1" },
      "status": "READY",
      "title": "2026 상반기 취향 리포트",
      "createdAt": "2026-07-01T00:00:00Z"
    }
  ]
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 반기당 리포트는 하나다
- 리포트는 반기 종료 후 배치로 생성한다(가정). 생성 중이면 `status: GENERATING`
- 해당 반기 평가가 기준 미만이면 리포트를 생성하지 않는다(기준 미정)

### 상태값

- ReportStatus: `GENERATING`, `READY`
- `half`: `H1`, `H2`

### 미정 사항

- [ ] 생성 시점(반기 종료 직후 배치 vs 사용자 요청 시)
- [ ] 최소 평가 수

## API-060 리포트 상세 조회

`GET` `/api/v1/reports/{reportId}`

| 항목 | 값 |
|---|---|
| 단계 | 2차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-23 |
| 화면 | ⑦-1 리포트 상세, ⑪-1-a 리포트 상세 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

장르·배우·감독·나와 맞는 캐릭터 카드 등 리포트 상세를 조회한다.

### 인증 및 권한

- 회원. 본인 리포트만

### Path Parameters

- `reportId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "reportId": 301,
  "period": { "year": 2026, "half": "H1" },
  "summary": { "ratingCount": 38, "newFlavorCount": 2, "averageScore": 4.1 },
  "topGenres": [{ "code": "THRILLER", "name": "스릴러", "count": 12 }],
  "topActors": [{ "personId": 287, "name": "브래드 피트", "count": 3 }],
  "topDirectors": [{ "personId": 7467, "name": "데이비드 핀처", "count": 3 }],
  "characterCard": { "name": "타일러 더든", "movieTitle": "파이트 클럽", "description": "...", "imageUrl": "https://..." },
  "flavorChanges": [{ "flavorCode": "FLAVOR_A", "before": 5, "after": 12 }],
  "createdAt": "2026-07-01T00:00:00Z"
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `409 REPORT_GENERATING`

### 처리 규칙

- 리포트는 생성 시점 스냅샷이며 이후 평가 변경을 반영하지 않는다

### 상태값

- ReportStatus

### 미정 사항

- [ ] 캐릭터 카드 선정 로직과 이미지 출처(TMDb 인물 이미지 라이선스)
- [ ] 포함 섹션 확정

## API-061 리포트 이미지 카드 생성

`POST` `/api/v1/reports/{reportId}/image`

| 항목 | 값 |
|---|---|
| 단계 | 2차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-24, NFR-07 |
| 화면 | ⑦-1 리포트 상세 |
| 미정 사항 | 있음 |
| 비고 | 다운로드 URL 유효시간 미정 |

### 설명

리포트를 PNG 이미지 카드로 생성하고 짧은 유효기간의 다운로드 URL을 반환한다.

### 인증 및 권한

- 회원. 본인 리포트만

### Path Parameters

- `reportId`

### Query Parameters

없음

### Request Body

없음

### Response

`201 Created`

```json
{
  "imageId": 811,
  "format": "PNG",
  "downloadUrl": "https://storage.example.com/...?X-Amz-Expires=600&...",
  "expiresAt": "2026-08-30T05:31:00Z"
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `409 REPORT_GENERATING`
- `429 RATE_LIMITED` 재생성 간격 제한(가정)

### 처리 규칙

- 파일 형식은 PNG만. PDF는 제공하지 않는다
- 저장된 PNG는 비공개 버킷에 두고 서명된 단기 URL로만 접근한다. URL 없이는 접근 불가(NFR-07)
- 이미 생성된 이미지가 있으면 재렌더링하지 않고 새 서명 URL만 발급한다(가정)
- 공유는 클라이언트가 다운로드한 이미지를 OS 공유 시트로 전달하는 방식이며 서버 측 SNS 연동은 없다

### 상태값

없음

### 미정 사항

- [ ] 다운로드 URL 유효시간(운영 보안 정책)
- [ ] 스토리지: S3 vs MinIO
- [ ] 이미지 렌더링 방식(서버 headless 렌더 vs 클라이언트 canvas 후 업로드)

## API-064 급상승 영화

`GET` `/api/v1/movies/trending`

| 항목 | 값 |
|---|---|
| 단계 | 2차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-27 |
| 화면 | 검색 홈 4.1(화면 연동은 후속 업무) |
| 미정 사항 | 구현 세부값은 실측 후 조정 |
| 비고 | 실시간 조회 이벤트 집계(ADR-0017) |

### 설명

최근 로그인 사용자의 영화 상세 조회 추이를 기반으로 급상승 영화를 제공한다. 비회원 상세 조회는 `VIEW` 발행 대상이 아니다([스트리밍 계약](./streaming-contract.md#43-이벤트별-payload)). 순위는 Spark Structured Streaming이 사전 집계하며 API 요청 시 다시 계산하지 않는다(ADR-0017).

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

- `size` (integer, 기본 10, 허용 1~30). 범위를 벗어나거나 정수가 아니면 `400 VALIDATION_FAILED`

### Request Body

없음

### Response

`200 OK`

```json
{
  "period": { "from": "2026-09-18T11:00:00Z", "to": "2026-09-18T12:00:00Z" },
  "updatedAt": "2026-09-18T12:00:00Z",
  "stale": false,
  "items": [
    {
      "rank": 1,
      "rankDelta": null,
      "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." },
      "ratingCount": 120,
      "averageScore": 4.3,
      "trendScore": 1.0
    }
  ]
}
```

### 오류 응답

- `400 VALIDATION_FAILED`: `size`가 정수가 아니거나 1~30 범위를 벗어남
- `503 TREND_UNAVAILABLE`: 최신 집계 결과와 마지막 정상 결과를 모두 읽을 수 없음(첫 집계 전·결과 유실·Redis 조회 장애 등)
- 그 외 공통 오류 적용

### 처리 규칙

- 여러 사용자의 `VIEW` 이벤트는 Spark Structured Streaming이 사전 집계하고 Spring Boot는 Redis 결과를 조회한다
- `to`는 UTC 5분 경계다. 현재 구간 `[to-60분, to)`의 영화별 고유 조회 회원 수 `C`와 직전 구간 `[to-120분, to-60분)`의 고유 조회 회원 수 `P`를 비교한다. 구간 시작은 포함하고 끝은 제외한다.
- 같은 `eventId`의 재전달은 스트리밍 계약의 watermark 기반 중복 판정 창에서 제거한다. 서로 다른 `eventId`여도 같은 회원이 같은 영화를 한 구간에서 여러 번 조회했다면 고유 조회자 1명으로 센다. `dwellMs`는 순위에 사용하지 않는다.
- `C >= 3`이고 증가분 `D = C - P > 0`인 영화만 후보로 삼는다. `D` 내림차순, `C` 내림차순, `movieId` 오름차순으로 정렬해 최대 30편을 게시한다. 후보가 없으면 정상적인 빈 `items` 스냅샷을 게시한다.
- API는 게시된 목록의 정렬 순서에서 앞 `size`편만 반환한다. `rank`와 `rankDelta`는 최대 30편 전체 스냅샷을 기준으로 계산하며 `size`에 따라 다시 계산하지 않는다.
- 후보 중 최대 증가분을 `Dmax`라 할 때 각 영화의 `trendScore = D / Dmax`다. 후보가 있을 때 값의 범위는 `(0, 1]`이며 평점이 아니다.
- `rankDelta`는 직전 게시 스냅샷의 순위에서 현재 순위를 뺀 값이다. 직전 목록에 없으면 신규 진입으로 `null`, 순위가 같으면 `0`이다. 현재 목록에서 이탈한 영화는 응답하지 않는다.
- 급상승 결과는 입력 이벤트가 없는 때에도 UTC 5분 경계마다 새로 게시한다. Spark의 이벤트 소비·마이크로배치 실행 주기는 별도로 정한다. 정상 도착한 `VIEW`의 발생부터 결과 게시까지 10분 이내를 초기 운영 목표로 삼고 실측한다.
- API 요청 시 인기 순위를 다시 계산하지 않는다
- 최신 집계 결과(`feelm:trending:current`, TTL 15분)를 읽으면 `stale=false`로 `200 OK`를 반환한다. 최신 결과를 읽을 수 없고 마지막 정상 결과(`feelm:trending:last-good`, TTL 2시간)를 읽을 수 있으면 그 스냅샷의 `period`·`updatedAt`을 유지하고 `stale=true`로 `200 OK`를 반환한다. 빈 `items`도 정상 스냅샷이다.
- 마지막 정상 결과도 읽을 수 없으면 `503 TREND_UNAVAILABLE`을 반환한다. Redis 조회 장애로 두 결과 모두 읽을 수 없는 경우도 같다.

### 상태값

없음

### 미정 사항

- [x] 60분 전후 비교·고유 조회자 3명·증가분 순위·5분 게시·10분 watermark
- [x] `trendScore`·`rankDelta`·`stale`·마지막 정상 결과·오류 기준
- [ ] 실제 조회량에 따른 최소 조회자 수·구간 길이 보정과 반영 지연 실측
- [ ] 검색 홈의 실제 API 연동(후속 FE 업무)
