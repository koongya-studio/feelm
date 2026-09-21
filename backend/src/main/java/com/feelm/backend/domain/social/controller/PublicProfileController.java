package com.feelm.backend.domain.social.controller;

import com.feelm.backend.domain.social.dto.response.PublicProfileResponse;
import com.feelm.backend.domain.social.service.PublicProfileService;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-062 공개 프로필 조회. 선택 인증이라 비회원도 호출한다.
 *
 * <p>내 프로필(API-014·015)은 회원 구분이라 {@code /api/v1/users/me}를 쓰는 {@code UserProfileController}에 따로 있다.
 * 닉네임 사용 가능 확인(API-016)을 컨트롤러로 나눠 둔 것과 같은 기준이다.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class PublicProfileController {

    private final PublicProfileService publicProfileService;

    @Operation(
            summary = "공개 프로필 조회",
            description =
                    "PUBLIC 사용자의 닉네임·취향 키워드·팝콘·최근 필름·공개 한줄평 수를 조회합니다. "
                            + "비회원도 호출할 수 있습니다. 없는 사용자, PRIVATE 사용자, "
                            + "로그인 사용자와 차단 관계인 사용자는 모두 404로 구분 없이 응답합니다. "
                            + "본인이 자신의 publicId로 조회하면 공개 범위와 무관하게 200입니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "공개 프로필 조회 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PublicProfileResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "RESOURCE_NOT_FOUND: 없는 사용자·PRIVATE 사용자·차단 관계",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "INTERNAL_ERROR: 공개 프로필 조회 실패",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{publicId}/profile")
    public PublicProfileResponse getPublicProfile(
            @Parameter(
                            description =
                                    "users.public_id(UUID). 내부 숫자 ID는 공개 URL에 쓰지 않는다(ADR-0008)",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"))
                    @PathVariable
                    String publicId,
            @Parameter(hidden = true) @AuthenticationPrincipal Long viewerId) {
        return publicProfileService.getPublicProfile(publicId, viewerId);
    }
}
