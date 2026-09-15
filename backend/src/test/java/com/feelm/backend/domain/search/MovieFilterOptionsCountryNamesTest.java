package com.feelm.backend.domain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.search.service.MovieFilterOptionsCountryNames;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MovieFilterOptionsCountryNamesTest {
    private final MovieFilterOptionsCountryNames names = new MovieFilterOptionsCountryNames();

    @ParameterizedTest
    @CsvSource({"KR,한국", "US,미국", "FR,프랑스", "JP,일본", "SU,소련", "XC,체코슬로바키아"})
    void matchesApiAndExistingCountryLabels(String code, String name) {
        assertThat(names.getName(code)).isEqualTo(name);
    }

    @Test
    void everyIsoCountryHasAKoreanNameInsteadOfTheCode() {
        for (String code : Locale.getISOCountries()) {
            assertThat(names.getName(code))
                    .isNotBlank()
                    .isNotEqualTo(code)
                    .containsPattern("[가-힣]");
        }
    }

    @Test
    void unknownCountryCannotSilentlyBecomeACodeLabel() {
        assertThatThrownBy(() -> names.getName("ZZ")).isInstanceOf(IllegalStateException.class);
    }
}
