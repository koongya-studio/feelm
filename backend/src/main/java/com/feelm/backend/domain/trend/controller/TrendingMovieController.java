package com.feelm.backend.domain.trend.controller;

import com.feelm.backend.domain.trend.dto.response.TrendingMoviesResponse;
import com.feelm.backend.domain.trend.service.TrendingMovieService;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TrendingMovieController {

    private final TrendingMovieService service;

    @Operation(
            summary = "급상승 영화 조회",
            description =
                    "최근 회원의 영화 상세 조회 추이를 Spark가 사전 집계한 급상승 목록을 조회합니다. 공개 API이며 요청 시 순위를 다시 계산하지 않습니다. "
                            + "유효한 집계가 아직 없으면 확정된 초기 고정 목록(source=DEFAULT)을 반환하고, 첫 유효 집계 이후에는 "
                            + "최신 결과가 만료돼도 마지막 유효 결과를 stale=true로 계속 반환합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "급상승 영화 조회 성공",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = TrendingMoviesResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "size가 정수가 아니거나 1~30 범위를 벗어남",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "503",
                description = "게시된 급상승 결과를 조회할 수 없고 초기 고정 목록으로 대체할 수도 없음",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/v1/movies/trending")
    public TrendingMoviesResponse getTrending(
            @Parameter(
                            description = "반환할 영화 수(기본 10, 1~30)",
                            schema =
                                    @Schema(
                                            type = "integer",
                                            minimum = "1",
                                            maximum = "30",
                                            defaultValue = "10"))
                    @RequestParam(defaultValue = "10")
                    @Min(1)
                    @Max(30)
                    int size) {
        return service.getTrending(size);
    }
}
