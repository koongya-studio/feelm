package com.feelm.backend.domain.movie.service;

import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse;
import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse.CastResponse;
import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse.CountryResponse;
import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse.DirectorResponse;
import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse.GenreResponse;
import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse.MovieRecommendationResponse;
import com.feelm.backend.domain.movie.repository.MovieDetailRepository;
import com.feelm.backend.domain.movie.repository.MovieDetailRepository.MovieAttribute;
import com.feelm.backend.domain.movie.repository.MovieDetailRepository.MovieCredit;
import com.feelm.backend.domain.movie.repository.MovieDetailRepository.MovieMetadata;
import com.feelm.backend.domain.movie.repository.MovieDetailRepository.Personalization;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class MovieDetailService {

    private static final String TMDB_ORIGINAL_PROFILE_PREFIX =
            "https://image.tmdb.org/t/p/original/";
    private static final String TMDB_THUMBNAIL_PROFILE_PREFIX = "https://image.tmdb.org/t/p/w185/";

    private final MovieDetailRepository movieDetailRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public MovieDetailResponse getDetail(long movieId, Long recommendationItemId, Long userId) {
        MovieMetadata movie =
                movieDetailRepository
                        .findMovie(movieId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<MovieAttribute> attributes = movieDetailRepository.findAttributes(movieId);
        List<MovieCredit> credits = movieDetailRepository.findCredits(movieId);
        Personalization personalization =
                userId == null
                        ? new Personalization(null, null)
                        : movieDetailRepository.findPersonalization(movieId, userId);

        return new MovieDetailResponse(
                movie.movieId(),
                movie.title(),
                movie.originalTitle(),
                movie.synopsis(),
                movie.releaseDate(),
                movie.runtimeMinutes(),
                attributes.stream()
                        .filter(attribute -> attribute.kind().equals("GENRE"))
                        .map(attribute -> new GenreResponse(attribute.code(), attribute.name()))
                        .toList(),
                attributes.stream()
                        .filter(attribute -> attribute.kind().equals("COUNTRY"))
                        .map(
                                attribute ->
                                        new CountryResponse(
                                                attribute.code(),
                                                CountryNames.getName(attribute.code())))
                        .toList(),
                credits.stream()
                        .filter(credit -> credit.role().equals("DIRECTOR"))
                        .map(
                                credit ->
                                        new DirectorResponse(
                                                credit.personId(),
                                                credit.name(),
                                                thumbnailProfileUrl(credit.profileUrl())))
                        .toList(),
                credits.stream()
                        .filter(credit -> credit.role().equals("ACTOR"))
                        .map(
                                credit ->
                                        new CastResponse(
                                                credit.personId(),
                                                credit.name(),
                                                credit.character(),
                                                thumbnailProfileUrl(credit.profileUrl())))
                        .toList(),
                movie.posterUrl(),
                movie.backdropUrl(),
                movie.flavorCode(),
                movie.averageRating(),
                movie.ratingCount(),
                movieDetailRepository.findOttProviders(movieId, userId),
                personalization.rating(),
                personalization.watchStatus(),
                getRecommendation(movieId, recommendationItemId, userId));
    }

    /** 기존 적재 URL을 그대로 활용하되 상세 화면에서는 작은 TMDb 이미지를 내려준다. */
    private String thumbnailProfileUrl(String profileUrl) {
        if (profileUrl == null || !profileUrl.startsWith(TMDB_ORIGINAL_PROFILE_PREFIX)) {
            return profileUrl;
        }
        return TMDB_THUMBNAIL_PROFILE_PREFIX
                + profileUrl.substring(TMDB_ORIGINAL_PROFILE_PREFIX.length());
    }

    private MovieRecommendationResponse getRecommendation(
            long movieId, Long recommendationItemId, Long userId) {
        if (userId == null || recommendationItemId == null) {
            return null;
        }
        // 세트 소유자는 개인 또는 파티 중 하나다. 파티 권한은 조회 시점의 멤버 행으로 판단한다.
        return movieDetailRepository
                .findRecommendation(movieId, recommendationItemId, userId)
                .filter(
                        snapshot ->
                                userId.equals(snapshot.userId()) || snapshot.currentPartyMember())
                .map(
                        snapshot ->
                                new MovieRecommendationResponse(
                                        snapshot.recommendationItemId(),
                                        snapshot.type(),
                                        objectMapper.readTree(snapshot.reason())))
                .orElse(null);
    }
}
