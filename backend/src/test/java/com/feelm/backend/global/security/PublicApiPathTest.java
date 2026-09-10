package com.feelm.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * 공개 경로 판정이 회원 전용 경로를 함께 열지 않는지 확인한다.
 *
 * <p>닉네임 사용 가능 확인(API-016)은 {@code /api/v1/users/} 아래에 있는 유일한 공개 API다. 목록에 와일드카드를 쓰면 같은 접두사의 {@code
 * /users/me} 계열이 통째로 열리므로, 그 경계를 테스트로 고정한다.
 */
@DisplayName("공개 API 경로 판정")
class PublicApiPathTest {

    @Test
    @DisplayName("닉네임 사용 가능 확인은 공개이고 내 프로필 계열은 공개가 아니다")
    void nicknameAvailabilityIsPublicAndMeIsNot() {
        assertThat(
                        SecurityConfig.isPublicApi(
                                HttpMethod.GET, "/api/v1/users/nickname-availability"))
                .isTrue();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/users/me")).isFalse();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.PATCH, "/api/v1/users/me")).isFalse();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.PUT, "/api/v1/users/me/password"))
                .isFalse();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/users/me/frames")).isFalse();
    }

    @Test
    @DisplayName("닉네임 사용 가능 확인은 GET에만 열려 있다")
    void nicknameAvailabilityIsOpenForGetOnly() {
        // 같은 경로에 쓰기 계열이 추가되어도 등록 절차 없이 열리지 않아야 한다(리뷰 B-3).
        assertThat(
                        SecurityConfig.isPublicApi(
                                HttpMethod.POST, "/api/v1/users/nickname-availability"))
                .isFalse();
        assertThat(
                        SecurityConfig.isPublicApi(
                                HttpMethod.DELETE, "/api/v1/users/nickname-availability"))
                .isFalse();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.POST, "/api/v1/popcorn-flavors"))
                .isFalse();
    }
}
