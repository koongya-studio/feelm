package com.feelm.backend.domain.search.service;

import static java.util.Map.entry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * JDK 21 한국어 ISO 국가명을 사용하며 KR은 API·프론트 계약의 '한국'으로 표기한다.
 *
 * <p>역사·지역 코드는 158의 CountryNames에서 사용한 표기를 유지한다. 기준 브랜치에 공통 변환기가 없어 API-029 내부에 격리하며, 매핑이 없는 코드를
 * 국가명인 것처럼 응답하지 않는다.
 */
@Component
public class MovieFilterOptionsCountryNames {

    private static final Set<String> ISO_CODES = Set.of(Locale.getISOCountries());
    private static final Map<String, String> OVERRIDES =
            Map.ofEntries(
                    entry("KR", "한국"),
                    entry("AN", "네덜란드령 안틸레스"),
                    entry("CS", "세르비아 몬테네그로"),
                    entry("SU", "소련"),
                    entry("XC", "체코슬로바키아"),
                    entry("XG", "동독"),
                    entry("XI", "북아일랜드"),
                    entry("XK", "코소보"),
                    entry("YU", "유고슬라비아"),
                    entry("ZR", "자이르"));

    public String getName(String code) {
        String override = OVERRIDES.get(code);
        if (override != null) {
            return override;
        }
        if (!ISO_CODES.contains(code)) {
            throw new IllegalStateException("국가 표시명 매핑이 없습니다: " + code);
        }
        String name = Locale.of("", code).getDisplayCountry(Locale.KOREAN);
        if (name.isBlank() || name.equals(code)) {
            throw new IllegalStateException("한국어 국가 표시명이 없습니다: " + code);
        }
        return name;
    }
}
