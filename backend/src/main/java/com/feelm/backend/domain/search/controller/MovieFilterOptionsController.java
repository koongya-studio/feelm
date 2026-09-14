package com.feelm.backend.domain.search.controller;

import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse;
import com.feelm.backend.domain.search.service.MovieFilterOptionsService;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MovieFilterOptionsController {

    private final MovieFilterOptionsService service;

    @Operation(
            summary = "검색 필터 옵션 조회",
            description = "영화 검색 필터에 사용할 장르·국가·개봉연도 범위와 지원 OTT 목록을 조회합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "필터 옵션 조회 성공",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema =
                                        @Schema(
                                                implementation =
                                                        MovieFilterOptionsResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "저장된 국가 코드의 표시명 변환 실패 등 서버 오류",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/v1/movies/filter-options")
    public ResponseEntity<MovieFilterOptionsResponse> getOptions() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(service.getOptions());
    }
}
