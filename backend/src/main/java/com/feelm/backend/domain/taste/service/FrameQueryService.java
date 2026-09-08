package com.feelm.backend.domain.taste.service;

import com.feelm.backend.domain.taste.dto.response.FrameDetailResponse;
import com.feelm.backend.domain.taste.dto.response.FrameListResponse;
import com.feelm.backend.domain.taste.repository.FrameQueryRepository;
import com.feelm.backend.domain.taste.repository.FrameQueryRepository.FrameCursor;
import com.feelm.backend.domain.taste.repository.FrameQueryRepository.FrameDetailRow;
import com.feelm.backend.domain.taste.repository.FrameQueryRepository.FrameListRow;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FrameQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private final FrameQueryRepository frameQueryRepository;

    public FrameQueryService(FrameQueryRepository frameQueryRepository) {
        this.frameQueryRepository = frameQueryRepository;
    }

    public FrameListResponse getMyFrames(
            long userId, String flavorCode, String encodedCursor, Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        FrameCursor cursor = decodeCursor(encodedCursor);
        List<FrameListRow> rows =
                frameQueryRepository.findFrames(userId, flavorCode, cursor, size + 1);
        boolean hasNext = rows.size() > size;
        List<FrameListRow> pageRows = hasNext ? rows.subList(0, size) : rows;
        List<FrameListResponse.Item> items = pageRows.stream().map(this::toItem).toList();
        String nextCursor = hasNext ? encodeCursor(pageRows.get(pageRows.size() - 1)) : null;
        long totalCount = frameQueryRepository.countFrames(userId, flavorCode);
        return new FrameListResponse(totalCount, items, nextCursor, hasNext);
    }

    public FrameDetailResponse getFrameDetail(long userId, long frameId) {
        FrameDetailRow row =
                frameQueryRepository
                        .findDetail(userId, frameId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<String> genres = frameQueryRepository.findGenres(row.movieId());
        List<String> directors = frameQueryRepository.findDirectors(row.movieId());
        return toDetail(row, genres, directors);
    }

    private FrameListResponse.Item toItem(FrameListRow row) {
        return new FrameListResponse.Item(
                row.frameId(),
                new FrameListResponse.Movie(row.movieId(), row.title(), row.posterUrl()),
                row.score(),
                row.flavorCode(),
                row.createdAt());
    }

    private FrameDetailResponse toDetail(
            FrameDetailRow row, List<String> genres, List<String> directors) {
        Integer releaseYear = row.releaseDate() == null ? null : row.releaseDate().getYear();
        FrameDetailResponse.Ott ott =
                row.ottCode() == null
                        ? null
                        : new FrameDetailResponse.Ott(row.ottCode(), row.ottName());
        return new FrameDetailResponse(
                row.frameId(),
                new FrameDetailResponse.Movie(
                        row.movieId(),
                        row.title(),
                        row.posterUrl(),
                        releaseYear,
                        genres,
                        directors),
                row.flavorCode(),
                new FrameDetailResponse.Rating(
                        row.ratingId(), row.score(), row.comment(), row.visibility()),
                new FrameDetailResponse.WatchRecord(
                        ott, row.clickedAt(), row.watchedAt(), row.ratedAt()));
    }

    private String encodeCursor(FrameListRow row) {
        String value = row.createdAt() + ":" + row.frameId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private FrameCursor decodeCursor(String encodedCursor) {
        if (encodedCursor == null) {
            return null;
        }
        try {
            String value =
                    new String(
                            Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8);
            int separator = value.lastIndexOf(':');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("cursor payload is incomplete");
            }
            Instant createdAt = Instant.parse(value.substring(0, separator));
            long frameId = Long.parseLong(value.substring(separator + 1));
            if (frameId < 1) {
                throw new IllegalArgumentException("frameId must be positive");
            }
            return new FrameCursor(createdAt, frameId);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
