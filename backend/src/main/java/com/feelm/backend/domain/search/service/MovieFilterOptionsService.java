package com.feelm.backend.domain.search.service;

import com.feelm.backend.domain.search.MovieFilterOptionsOttPolicy;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.Option;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.OttOption;
import com.feelm.backend.domain.search.repository.MovieFilterOptionsRepository;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovieFilterOptionsService {

    private final MovieFilterOptionsRepository repository;
    private final MovieFilterOptionsCountryNames countryNames;

    public MovieFilterOptionsResponse getOptions() {
        return new MovieFilterOptionsResponse(
                repository.findGenres(),
                repository.findCountryCodes().stream()
                        .map(code -> new Option(code, countryNames.getName(code)))
                        .toList(),
                repository.findYearRange(),
                repository.findOtts().stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (OttOption ott) ->
                                                        MovieFilterOptionsOttPolicy.displayOrder(
                                                                ott.code()))
                                        .thenComparing(OttOption::code))
                        .toList());
    }
}
