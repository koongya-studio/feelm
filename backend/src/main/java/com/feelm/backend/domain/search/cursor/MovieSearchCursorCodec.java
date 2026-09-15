package com.feelm.backend.domain.search.cursor;

import com.feelm.backend.domain.search.dto.request.MovieSearchCriteria;
import com.feelm.backend.domain.search.dto.request.MovieSearchSort;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.EnumFeature;

@Component
public class MovieSearchCursorCodec {

    // PostgreSQL numeric/date의 표현 한계. 정상 검색 점수의 정밀도는 줄이지 않는다.
    private static final int MAX_NUMERIC_SCALE = 16383;
    private static final int MAX_NUMERIC_INTEGER_DIGITS = 131072;
    private static final LocalDate MIN_RELEASE_DATE = LocalDate.of(-4713, 11, 24);
    private static final LocalDate MAX_RELEASE_DATE = LocalDate.of(5874897, 12, 31);

    private final ObjectMapper objectMapper;
    private final ObjectReader reader;

    public MovieSearchCursorCodec(ObjectMapper objectMapper) {
        // 공유 mapper의 모듈은 유지하고 cursor에서만 타입 강제 변환을 금지한다.
        this.objectMapper =
                objectMapper
                        .rebuild()
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                        .build();
        this.reader =
                this.objectMapper
                        .readerFor(MovieSearchCursor.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                        .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public String encode(MovieSearchCursor cursor) {
        validate(cursor);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(cursor));
    }

    /** 페이지 크기·사용자 ID·현재 위치는 제외하고 검색 결과와 순서를 결정하는 조건만 식별한다. */
    public String criteriaKey(MovieSearchCriteria criteria) {
        MovieSearchSort effectiveSort =
                criteria.sort() == MovieSearchSort.RELEVANCE && criteria.q() == null
                        ? MovieSearchSort.RATING
                        : criteria.sort();
        CursorConditions conditions =
                new CursorConditions(
                        3,
                        criteria.q(),
                        criteria.genre().stream().sorted().toList(),
                        criteria.country().stream().sorted().toList(),
                        criteria.yearFrom(),
                        criteria.yearTo(),
                        criteria.ott().stream().sorted().toList(),
                        criteria.sort(),
                        effectiveSort);
        try {
            // JSON으로 값의 경계를 보존한다. 구분자를 포함한 입력이 다른 조건과 충돌하지 않는다.
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(objectMapper.writeValueAsBytes(conditions)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public MovieSearchCursor decode(String encoded) {
        try {
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalArgumentException("Missing cursor");
            }
            MovieSearchCursor cursor = reader.readValue(Base64.getUrlDecoder().decode(encoded));
            validate(cursor);
            return cursor;
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static void validate(MovieSearchCursor cursor) {
        if (cursor == null
                || cursor.sort() == null
                || cursor.movieId() <= 0
                || cursor.criteriaKey() == null
                || !cursor.criteriaKey().matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (cursor.sort() == MovieSearchSort.RELEASE_DATE) {
            if (cursor.sortValue() != null
                    || (cursor.releaseDate() != null
                            && (cursor.releaseDate().isBefore(MIN_RELEASE_DATE)
                                    || cursor.releaseDate().isAfter(MAX_RELEASE_DATE)))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } else if (cursor.sortValue() == null
                || cursor.sortValue().signum() < 0
                || cursor.sortValue().scale() > MAX_NUMERIC_SCALE
                || (long) cursor.sortValue().precision() - cursor.sortValue().scale()
                        > MAX_NUMERIC_INTEGER_DIGITS
                || cursor.releaseDate() != null
                || (cursor.sort() == MovieSearchSort.RATING
                        && cursor.sortValue().compareTo(BigDecimal.valueOf(5)) > 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** 검색 점수 공식·정규화 의미가 바뀌면 version을 올려 이전 위치의 재사용을 막는다. */
    private record CursorConditions(
            int version,
            String query,
            List<String> genres,
            List<String> countries,
            Integer yearFrom,
            Integer yearTo,
            List<Long> otts,
            MovieSearchSort requestedSort,
            MovieSearchSort effectiveSort) {}
}
