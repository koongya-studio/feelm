package com.feelm.backend.domain.search.controller;

import com.feelm.backend.domain.search.dto.request.MovieSearchCriteria;
import com.feelm.backend.domain.search.dto.response.MovieSearchPageResponse;
import com.feelm.backend.domain.search.service.MovieSearchService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequiredArgsConstructor
public class MovieSearchController {

    private final MovieSearchService service;

    @InitBinder
    void validateSearchParameterNames(WebRequest request) {
        // List 생성자 바인딩보다 먼저 검사한다. 배열은 같은 이름의 파라미터를 반복해 전달한다.
        for (String name : request.getParameterMap().keySet()) {
            if (name.indexOf('[') >= 0 || name.indexOf(']') >= 0 || name.indexOf('.') >= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    @Operation(summary = "영화 검색", description = "영화 제목·원제·배우·감독을 검색하고 장르·국가·개봉연도·OTT 조건으로 필터링합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "검색 성공. 결과가 없으면 totalCount=0과 빈 items를 반환합니다.",
                content =
                        @Content(schema = @Schema(implementation = MovieSearchPageResponse.class))),
        @ApiResponse(responseCode = "400", description = "검색조건 또는 cursor 검증 실패"),
        @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않음")
    })
    @GetMapping("/api/v1/movies/search")
    public MovieSearchPageResponse search(
            @Valid @ParameterObject @ModelAttribute MovieSearchCriteria criteria,
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return service.search(criteria, userId);
    }
}
