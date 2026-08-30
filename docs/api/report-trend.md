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
  "flavorChanges": [{ "flavorCode": "FLAVOR_X", "before": 5, "after": 12 }],
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
| 화면 | - |
| 미정 사항 | 있음 |
| 비고 | 집계 기간·갱신 주기 미정 |

### 설명

최근 전체 사용자 평가 데이터를 기반으로 급상승 영화를 제공한다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

- `size` (integer, 기본 10, 최대 30)

### Request Body

없음

### Response

`200 OK`

```json
{
  "period": { "from": "2026-08-23T00:00:00Z", "to": "2026-08-30T00:00:00Z" },
  "updatedAt": "2026-08-30T00:00:00Z",
  "items": [
    {
      "rank": 1,
      "rankDelta": 4,
      "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." },
      "ratingCount": 120,
      "averageScore": 4.3,
      "trendScore": 0.87
    }
  ]
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 배치로 집계해 캐시하며 요청 시 계산하지 않는다

### 상태값

없음

### 미정 사항

- [ ] 집계 기간(7일 가정)과 갱신 주기(일 1회 가정)
- [ ] `trendScore` 산출식과 최소 평가 수
- [ ] 노출 화면
