# ADR-0010: 영화 검색은 PostgreSQL full-text와 pg_trgm으로 구현한다

| 항목 | 값 |
|---|---|
| 상태 | 채택 |
| 결정일 | 2026-08-31 |
| 결정자 | 박시현(BE·인프라 공유 예정) |
| 관련 | A-10, ERD D-11, FR-14, FR-15, API-028 |

## 맥락

영화명·배우·감독 키워드 검색과 장르·국가·연도·OTT 필터가 필요하다. 대상은 영화 약 8.7만 편과 인물이며, 인프라는 EC2 2대(ADR-0006)다.

## 결정

PostgreSQL full-text search + `pg_trgm`(부분 일치)으로 구현하고 Elasticsearch는 도입하지 않는다. 검색 계층은 인터페이스로 분리해 확장 단계에서 엔진 교체가 가능하게 한다.

### 검색 대소문자 정규화 (2026-09-11, API-028 리뷰 반영)

- 검색어는 Java `Locale.ROOT` 소문자 정규화 후 SQL과 cursor 조건 식별에 같은 값을 사용한다.
- 제목·원제·인물명의 trigram/부분 검색은 `lower(column COLLATE "und-x-icu")`로 비교한다.
  DB 기본 libc 변환은 Greek final sigma나 dotted I에서 Java와 달라 정확 검색과 cursor
  연속성이 어긋날 수 있으므로, 명시적인 ICU root 규칙을 사용한다.
- GIN 인덱스도 같은 식으로 생성한다. 기존 migration을 수정하지 않고 후속 migration에서
  해당 trigram 인덱스 3개를 교체한다. full-text 인덱스는 그대로 사용한다.
- PostgreSQL은 ICU 지원과 `und-x-icu` collation이 필요하다. 지정된 pgvector 이미지의
  전체 migration·정확 검색·인덱스 실행 계획 테스트로 검증한다. Java/ICU 변경 시에도
  비ASCII 정확 검색과 cursor 회귀를 확인하고, 정규화 의미가 바뀌면 cursor version을 올린다.

### 부분 문자열·full-text 정규화 보완 (2026-09-13)

- `ΟΣΚΑΡΑ`에서 `ΟΣ`를 부분 검색하면 소문자 변환의 단어 끝 시그마(`ς`)와 단어 중간
  시그마(`σ`)가 달라 누락된다. 검색어와 DB 문자열은 root 소문자 변환 후 `ς`를 `σ`로 통일한다.
- DB 함수 `movie_search_normalize(text)`를 LIKE·trigram·similarity와 full-text의 열·검색어에
  공통 적용한다. full-text에도 적용해야 `ος ... alpha`의 비연속 단어 검색을 보존한다.
  Java Criteria에서 같은 입력 정규화를 마친 값을 cursor key와 SQL에 전달한다.
- 후속 migration `V202609140120`에서 trigram 3개와 full-text 2개의 GIN 인덱스를 해당 함수식으로
  재생성한다. 앞 절의 raw full-text 유지 결정은 이 보완으로 대체한다. 기존 migration 원문은 유지한다.
- 함수는 NULL을 보존하며 저장된 제목·이름과 응답 문자열은 수정하지 않는다. 함수 의미나 ICU 버전을
  바꾸면 인덱스 재생성이 필요하고 cursor 조건 version도 갱신한다. 이번 version은 3이다.
- 단어 끝 시그마 대응은 [Unicode CaseFolding 자료](https://www.unicode.org/Public/UCD/latest/ucd/CaseFolding.txt)의
  U+03C2→U+03C3 매핑에 따른다. 전체 Unicode case folding이나 악센트 제거를 도입한 것은 아니다.

## 고려한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| PostgreSQL full-text (선택) | 추가 인프라 없음, 규모에 충분 | 한국어 형태소 분석 약함, 랭킹 품질 한계 |
| Elasticsearch | 검색 품질·속도 | EC2 자원 소모, 운영·동기화 부담 |

## 결과

- **얻는 것**: 인프라 단순
- **감수하는 것**: 한국어 검색 품질 한계 — 확장 단계에서 교체 가능하도록 검색 계층을 인터페이스로 분리
- **후속 작업**: API-028 미정 사항 정리 완료(S15P21E106-91). 인덱스 설계는 물리 스키마 작성 시
