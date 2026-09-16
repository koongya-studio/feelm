package com.feelm.backend.domain.recommendation.controller;

import com.feelm.backend.domain.recommendation.dto.response.SimilarMoviesResponse;
import com.feelm.backend.domain.recommendation.service.SimilarMovieService;
import jakarta.validation.constraints.Positive;
import java.beans.PropertyEditorSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
public class SimilarMovieController {

    private final SimilarMovieService similarMovieService;

    @InitBinder
    void bindDecimalNumbers(WebDataBinder binder) {
        registerDecimalEditor(binder, Long.class, Long::parseLong);
        registerDecimalEditor(binder, long.class, Long::parseLong);
        registerDecimalEditor(binder, Integer.class, Integer::parseInt);
        registerDecimalEditor(binder, int.class, Integer::parseInt);
    }

    private <T extends Number> void registerDecimalEditor(
            WebDataBinder binder, Class<T> type, java.util.function.Function<String, T> parser) {
        binder.registerCustomEditor(
                type,
                new PropertyEditorSupport() {
                    @Override
                    public void setAsText(String text) {
                        setValue(parser.apply(text));
                    }
                });
    }

    @GetMapping("/{movieId}/similar")
    SimilarMoviesResponse getSimilarMovies(
            @PathVariable @Positive long movieId, @RequestParam(required = false) Integer size) {
        return similarMovieService.getSimilarMovies(movieId, size);
    }
}
