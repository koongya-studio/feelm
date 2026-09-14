package com.feelm.backend.domain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.Option;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.OttOption;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.YearRange;
import com.feelm.backend.domain.search.repository.MovieFilterOptionsRepository;
import com.feelm.backend.domain.search.service.MovieFilterOptionsCountryNames;
import com.feelm.backend.domain.search.service.MovieFilterOptionsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MovieFilterOptionsServiceTest {
    @Mock private MovieFilterOptionsRepository repository;
    private MovieFilterOptionsService service;

    @BeforeEach
    void setUp() {
        service = new MovieFilterOptionsService(repository, new MovieFilterOptionsCountryNames());
        when(repository.findGenres()).thenReturn(List.of(new Option("SF", "DB 장르명")));
        when(repository.findCountryCodes()).thenReturn(List.of("KR"));
        when(repository.findYearRange()).thenReturn(new YearRange(1932, 2040));
    }

    @Test
    void preservesDatabaseValuesAndUsesTheFiveConfirmedOttsInOrder() {
        when(repository.findOtts())
                .thenReturn(
                        List.of(
                                new OttOption(9L, "WATCHA", "왓챠"),
                                new OttOption(8L, "WAVVE", "DB 웨이브"),
                                new OttOption(6L, "TVING", "티빙"),
                                new OttOption(5L, "DISNEY_PLUS", "디즈니+"),
                                new OttOption(4L, "NETFLIX", "넷플릭스")));
        var response = service.getOptions();
        assertThat(response.genres()).containsExactly(new Option("SF", "DB 장르명"));
        assertThat(response.countries()).containsExactly(new Option("KR", "한국"));
        assertThat(response.yearRange()).isEqualTo(new YearRange(1932, 2040));
        assertThat(response.otts())
                .extracting(OttOption::code)
                .containsExactly("NETFLIX", "DISNEY_PLUS", "WATCHA", "WAVVE", "TVING");
        assertThat(response.otts().get(3)).isEqualTo(new OttOption(8L, "WAVVE", "DB 웨이브"));
    }

    @Test
    void emptyYearRangeAndCollectionsAreNotInvented() {
        when(repository.findGenres()).thenReturn(List.of());
        when(repository.findCountryCodes()).thenReturn(List.of());
        when(repository.findYearRange()).thenReturn(new YearRange(null, null));
        when(repository.findOtts()).thenReturn(List.of());
        var response = service.getOptions();
        assertThat(response.genres()).isEmpty();
        assertThat(response.countries()).isEmpty();
        assertThat(response.otts()).isEmpty();
        assertThat(response.yearRange()).isEqualTo(new YearRange(null, null));
    }
}
