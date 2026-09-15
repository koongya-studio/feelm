package com.feelm.backend.domain.movie.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

@Schema(description = "영화 상세. 값이 없는 선택 필드는 null, 연결 데이터가 없는 컬렉션은 빈 배열로 반환합니다.")
public record MovieDetailResponse(
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "서비스 영화 ID",
                        example = "550")
                long movieId,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "영화 제목",
                        example = "파이트 클럽")
                String title,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "원제. 없으면 null",
                        example = "Fight Club",
                        nullable = true)
                String originalTitle,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "줄거리. 없으면 null",
                        nullable = true)
                String synopsis,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "개봉일. 없으면 null",
                        example = "1999-10-15",
                        nullable = true)
                LocalDate releaseDate,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "상영 시간(분). 없으면 null",
                        example = "139",
                        nullable = true)
                Integer runtimeMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "영화에 연결된 장르. 없으면 빈 배열")
                List<GenreResponse> genres,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "제작 국가 코드와 한국어 표시명. 없으면 빈 배열")
                List<CountryResponse> countries,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "감독 전체. display_order 오름차순(null은 마지막), 동률은 personId 순")
                List<DirectorResponse> directors,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "배우 최대 10명. display_order 오름차순(null은 마지막), 동률은 personId 순")
                List<CastResponse> cast,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "포스터 URL. 없으면 null",
                        nullable = true)
                String posterUrl,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "배경 이미지 URL. 없으면 null",
                        nullable = true)
                String backdropUrl,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "대표 팝콘 맛 코드. 미배정이면 null",
                        nullable = true)
                String flavorCode,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "전체 사용자 평균 평점. 차단 필터 미적용, 평가 없으면 0",
                        example = "4.3")
                BigDecimal averageRating,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "전체 사용자 평가 수. 차단 필터 미적용",
                        example = "1280")
                int ratingCount,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "영화에 연결된 OTT. 구독 우선, 그룹 내 ottId 오름차순. 없으면 빈 배열")
                List<MovieOttProviderResponse> ottProviders,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "본인 평가. 비회원 또는 평가 없으면 null")
                MyRatingResponse myRating,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description =
                                "현재 감상 상태(LINK_CLICKED, WATCHED_UNRATED, RATED). 비회원·기록 없음·EXPIRED만 있으면 null",
                        nullable = true)
                String myWatchStatus,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "영화 일치 및 열람 권한이 확인된 추천 이유. 비회원·ID 미제공·권한 없음이면 null")
                MovieRecommendationResponse recommendation) {

    @Schema(description = "영화에 연결된 장르")
    public record GenreResponse(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "장르 코드",
                            example = "DRAMA")
                    String code,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "장르 표시명",
                            example = "드라마")
                    String name) {}

    @Schema(description = "제작 국가. 수집 원문의 역사적 국가·지역 코드를 유지합니다.")
    public record CountryResponse(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "국가 코드",
                            example = "KR")
                    String code,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "한국어 국가 표시명",
                            example = "한국")
                    String name) {}

    @Schema(description = "감독 크레딧. 인원 제한 없이 반환합니다.")
    public record DirectorResponse(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "서비스 인물 ID",
                            example = "7467")
                    long personId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "감독 이름",
                            example = "데이비드 핀처")
                    String name) {}

    @Schema(description = "TMDb display_order 기준 상위 10명의 배우 크레딧")
    public record CastResponse(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "서비스 인물 ID",
                            example = "287")
                    long personId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "배우 이름",
                            example = "브래드 피트")
                    String name,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "배역명. 없으면 null",
                            example = "Tyler Durden",
                            nullable = true)
                    String character) {}

    @Schema(description = "영화의 OTT 제공처와 현재 사용자의 구독 여부")
    public record MovieOttProviderResponse(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "서비스 OTT ID",
                            example = "1")
                    long ottId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "OTT 코드",
                            example = "NETFLIX")
                    String code,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "OTT 표시명",
                            example = "넷플릭스")
                    String name,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "시청 바로가기 URL. 없으면 null",
                            nullable = true)
                    String watchUrl,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "로그인 사용자의 구독 여부. 비회원은 false",
                            example = "true")
                    boolean subscribed) {}

    @Schema(description = "현재 로그인 사용자의 평가. 비공개 한줄평도 본인에게만 반환합니다.")
    public record MyRatingResponse(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "평가 ID",
                            example = "91")
                    long ratingId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "평점. 0.5~5.0, 0.5점 단위",
                            example = "4.5")
                    BigDecimal score,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "내 한줄평. 없으면 null",
                            nullable = true)
                    String comment,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "한줄평 공개 범위(PUBLIC, PRIVATE). 한줄평이 없으면 null 가능",
                            nullable = true)
                    String visibility) {}

    @Schema(description = "본인 개인 추천 또는 현재 party_members에 속한 파티 추천의 저장된 이유")
    public record MovieRecommendationResponse(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "추천 항목 ID",
                            example = "3301")
                    long recommendationItemId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "추천 유형",
                            allowableValues = {"TASTE", "DISCOVERY", "PARTY"})
                    String type,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "저장된 추천 이유 JSON 스냅샷. 재계산 없이 반환",
                            type = "object",
                            implementation = Map.class,
                            example =
                                    "{\"summary\":\"스릴러 장르를 높게 평가하셨어요\",\"factors\":[{\"type\":\"GENRE\",\"label\":\"스릴러\"}]}")
                    JsonNode reason) {}
}
