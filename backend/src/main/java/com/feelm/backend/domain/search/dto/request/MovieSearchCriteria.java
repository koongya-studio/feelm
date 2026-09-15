package com.feelm.backend.domain.search.dto.request;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record MovieSearchCriteria(
        @Parameter(description = "영화 제목·원제·배우·감독 검색어. 필터가 없으면 필수", example = "기생충")
                @Pattern(regexp = "[^\\x00]*", message = "검색어에 NUL 문자를 포함할 수 없습니다.")
                String q,
        @Parameter(description = "장르 코드. 같은 이름을 반복하면 OR로 검색", example = "DRAMA")
                List<@Pattern(regexp = "[^\\x00]*", message = "장르에 NUL 문자를 포함할 수 없습니다.") String>
                        genre,
        @Parameter(description = "ISO 3166-1 alpha-2 국가 코드. 같은 이름을 반복하면 OR로 검색", example = "KR")
                List<@Pattern(regexp = "[^\\x00]*", message = "국가에 NUL 문자를 포함할 수 없습니다.") String>
                        country,
        @Parameter(description = "개봉연도 시작값(포함)", example = "2000") Integer yearFrom,
        @Parameter(description = "개봉연도 종료값(포함)", example = "2026") Integer yearTo,
        @Parameter(description = "OTT 식별자. 같은 이름을 반복하면 OR로 검색", example = "1") List<Long> ott,
        @Parameter(description = "정렬 기준: RELEVANCE, RATING, RELEASE_DATE", example = "RELEVANCE")
                MovieSearchSort sort,
        @Parameter(description = "이전 응답의 다음 페이지 cursor") String cursor,
        @Parameter(description = "페이지 크기(1~50, 기본 20)", example = "20") @Min(1) @Max(50)
                Integer size) {

    public MovieSearchCriteria {
        // SQL movie_search_normalize와 같은 규칙. 부분 검색에서도 시그마의 단어 내 위치를 구분하지 않는다.
        q = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT).replace('ς', 'σ');
        genre = normalize(genre);
        country = normalize(country);
        ott = ott == null ? List.of() : ott.stream().filter(Objects::nonNull).distinct().toList();
        sort = sort == null ? MovieSearchSort.RELEVANCE : sort;
        size = size == null ? 20 : size;
    }

    @AssertTrue(message = "검색어 또는 필터가 필요합니다.")
    public boolean isSearchConditionPresent() {
        return q != null
                || !genre.isEmpty()
                || !country.isEmpty()
                || !ott.isEmpty()
                || yearFrom != null
                || yearTo != null;
    }

    @AssertTrue(message = "시작 연도는 종료 연도보다 클 수 없습니다.")
    public boolean isYearRangeValid() {
        return yearFrom == null || yearTo == null || yearFrom <= yearTo;
    }

    private static List<String> normalize(List<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .toList();
    }
}
