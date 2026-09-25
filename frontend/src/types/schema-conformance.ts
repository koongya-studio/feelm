/*
 * 손으로 쓴 응답 타입이 백엔드 OpenAPI 스키마와 어긋나지 않는지 컴파일 시점에 본다
 * (업무 S15P21E106-777).
 *
 * ─────────────────────────── 왜 생성 타입으로 갈아끼우지 않는가
 *
 * 생성 타입을 그대로 쓰면 **타입이 약해진다.** springdoc은 Java record에서 문서를 만들고
 * record에는 필수·null 정보가 없어, 생성 스키마에는 `required`가 거의 없고 `null` 표시도
 * 없다. 그대로 별칭을 걸면 `title?: string`이 되어 화면 코드 전체가 `?.`로 덮인다.
 * 무엇이 반드시 오고 무엇이 `null`인지는 **계약 문서**(`docs/api/*.md`)가 정하고, 그 지식이
 * `src/types/*`에 들어 있다.
 *
 * 그래서 손으로 쓴 타입을 그대로 두고 **생성 스키마를 기준으로 검사만 한다.** 이 파일이
 * 잡는 것은 업무 -777이 막으려는 바로 그 사고다 — 파티 추천 `reason`(업무 -705), OTT 추천
 * `matchRate` · `similarity`(업무 -563), 관심없음 `replacement`처럼 **서버에 없는 필드를
 * 프론트 타입이 들고 있는 것.** 이름이 바뀌거나 타입이 달라지는 것도 함께 걸린다.
 *
 * 검사는 `tsc -b`가 하므로 `npm run build`와 프론트 MR Job에서 그대로 돈다.
 *
 * **중첩 객체는 따로 단정한다.** 없는 필드 검사는 한 단계에서만 하므로, 응답 안에 들어 있는
 * 객체(영화 요약 등)는 아래에 자기 줄을 하나씩 갖는다.
 *
 * ─────────────────────────── 어긋났을 때
 *
 * `Expect<Conforms<...>>` 자리에서 오류가 나고, 메시지에 문제가 된 **필드 이름**이 찍힌다.
 * 서버에 없는 필드를 지우거나, 계약을 먼저 고치고 스냅샷을 갱신한다
 * (`FEELM_OPENAPI_SNAPSHOT_WRITE=1 ./gradlew test --tests '*OpenApiSnapshotTest'`).
 */
import type { components } from '../generated/api-schema';
import type { OttRecommendation } from './ott';
import type { PartyRecommendationItem, PartyRecommendationSet } from './party';
import type { RecommendationItem, RecommendationSet } from './recommendation';

type Schemas = components['schemas'];

/**
 * 손으로 쓴 쪽. 필수 여부와 `null` 허용을 벗긴다.
 *
 * 무엇이 반드시 오고 무엇이 `null`인지는 계약 문서가 정하는 것이지 생성 스키마가 아는 것이
 * 아니다(위 머리말). 남는 차이가 **필드 이름과 값의 종류**뿐이 되게 한다.
 */
type LooseLocal<T> = T extends readonly (infer Element)[]
  ? LooseLocal<Element>[]
  : T extends object
    ? { [K in keyof T]-?: LooseLocal<NonNullable<T[K]>> }
    : T;

/**
 * 서버 쪽. 같은 방식으로 벗기되 **필드를 모두 선택으로 둔다.**
 *
 * 프론트가 서버가 주는 필드를 다 적을 필요는 없다 — 5.5의 추천 카드는 영화 요약에서 네
 * 필드만 쓴다. 서버 쪽을 필수로 두면 그런 부분집합이 전부 어긋난 것으로 잡힌다.
 */
type LooseServer<T> = T extends readonly (infer Element)[]
  ? LooseServer<Element>[]
  : T extends object
    ? { [K in keyof T]?: LooseServer<NonNullable<T[K]>> }
    : T;

/** 서버 스키마에 없는 필드 이름. 없으면 `never`다 */
type UnknownFields<Local, Server> = Exclude<keyof Local, keyof Server>;

/**
 * 서버 값과 종류가 다른 필드 이름. 없으면 `never`다.
 *
 * 필드 자신의 `null`·`undefined`는 여기서 벗긴다. `LooseLocal`·`LooseServer`는 조건부
 * 타입이라 `string | null` 같은 합집합에 나눠 적용되어 `null`이 그대로 남는다.
 */
type MismatchedFields<Local, Server> = {
  [K in keyof Local & keyof Server]: LooseLocal<NonNullable<Local[K]>> extends LooseServer<
    NonNullable<Server[K]>
  >
    ? never
    : K;
}[keyof Local & keyof Server];

/** 두 목록이 모두 비면 `true`, 아니면 문제가 된 필드 이름을 담은 튜플 */
type Conforms<Local, Server> = [
  UnknownFields<Local, Server>,
  MismatchedFields<Local, Server>,
] extends [never, never]
  ? true
  : [
      '서버 스키마에 없거나 모양이 다른 필드',
      UnknownFields<Local, Server> | MismatchedFields<Local, Server>,
    ];

/**
 * `true`가 아니면 여기서 컴파일이 멈춘다.
 *
 * 오류 메시지가 `Conforms`의 튜플을 그대로 보여 주므로 **문제가 된 필드 이름이 그대로
 * 찍힌다.** 별칭만 두고 검사하지 않으면 타입이 계산될 뿐 아무도 멈추지 않는다.
 *
 * `Expect<Conforms<...>>`를 다시 한 제네릭 별칭으로 묶지 않는다. 묶으면 타입 인자가
 * 정해지지 않은 정의 자리에서 TypeScript가 `true`임을 증명하지 못해 **선언 자체가** 오류가
 * 된다. 아래처럼 쓰는 자리마다 풀어 쓴다.
 */
type Expect<Result extends true> = Result;

/* ─────────────────────────── API-045 기본 추천 조회 */

/**
 * `items`는 뺀다 — 요소 타입은 바로 아래 줄이 따로 본다.
 *
 * 요소 안의 `reason`이 서버 쪽에서 `JsonNode`라(아래 설명) 여기서 함께 보면 `items`
 * 하나만 어긋난 것으로 뭉뚱그려진다.
 */
export type RecommendationSetConformance = Expect<
  Conforms<Omit<RecommendationSet, 'items'>, Schemas['RecommendationResponse']>
>;

/**
 * `reason`은 뺀다.
 *
 * 백엔드가 이 필드를 Jackson `JsonNode`로 들고 있어 springdoc이 `JsonNode`의 getter를
 * 스키마로 만든다(`array` · `bigDecimal` · `empty` …). 계약(`docs/api/recommendation.md`
 * API-045)이 정한 `summary` · `factors[]`는 생성 문서에 없으므로 여기서 비교할 대상이
 * 없다. 백엔드가 응답 DTO에 실제 타입을 두어야 검사에 들어온다(후속 과제).
 */
export type RecommendationItemConformance = Expect<
  Conforms<Omit<RecommendationItem, 'reason'>, Schemas['RecommendationItemResponse']>
>;

export type RecommendedMovieConformance = Expect<
  Conforms<RecommendationItem['movie'], Schemas['RecommendationMovieResponse']>
>;

/* ─────────────────────────── API-058 파티 영화 추천 */

export type PartyRecommendationSetConformance = Expect<
  Conforms<PartyRecommendationSet, Schemas['PartyRecommendationResponse']>
>;

export type PartyRecommendationItemConformance = Expect<
  Conforms<PartyRecommendationItem, Schemas['PartyRecommendationItemResponse']>
>;

/** 5.5가 네 필드만 쓰는 영화 요약. 서버가 더 주는 것은 어긋남이 아니다 */
export type PartyRecommendationMovieConformance = Expect<
  Conforms<PartyRecommendationItem['movie'], Schemas['RecommendationMovieResponse']>
>;

/* ─────────────────────────── API-068 OTT 구독 추천 */

/**
 * `items`는 뺀다.
 *
 * `OttRecommendationResponse.items`가 가리키는 스키마 이름이 `Item`이고, 그 이름을 다른
 * 중첩 record(취향 입력의 `movieId` · `preference`)가 이미 쓰고 있어 **두 모양이 한 스키마로
 * 뭉개졌다.** 생성 문서가 잘못 말하고 있는 것이라 프론트 타입을 거기 맞출 수 없다.
 * 백엔드에서 스키마 이름을 갈라야(`@Schema(name = ...)`) 검사에 들어온다(후속 과제).
 */
export type OttRecommendationConformance = Expect<
  Conforms<Omit<OttRecommendation, 'items'>, Schemas['OttRecommendationResponse']>
>;
