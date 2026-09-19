package com.feelm.backend.domain.user;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 내 프로필 조회(API-014) 계약 테스트. */
@DisplayName("내 프로필 조회 API — API-014 계약")
class UserProfileApiContractTest extends UserProfileApiTestSupport {

    @Test
    @DisplayName("프로필 조회는 인증이 필요하다")
    void profileRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ME_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("계약의 모든 필드를 명세한 값으로 반환한다")
    void returnsFullProfileContract() throws Exception {
        long userId = activeUser("user@example.com", "팝콘러버");

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.nickname").value("팝콘러버"))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.linkedProviders", empty()))
                .andExpect(jsonPath("$.onboardingCompleted").value(false))
                .andExpect(jsonPath("$.stats.frameCount").value(0))
                .andExpect(jsonPath("$.stats.popcornCount").value(0))
                .andExpect(jsonPath("$.stats.ratingCount").value(0))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT))
                // 계약 외 필드가 늘어나는 것도 잡도록 필드 집합까지 고정한다(리뷰 B 2장).
                .andExpect(
                        content()
                                .json(
                                        """
                                        {"userId":%d,"email":"user@example.com","nickname":"팝콘러버",
                                         "birthDate":"%s","gender":"FEMALE","visibility":"PRIVATE",
                                         "hasPassword":true,"linkedProviders":[],
                                         "onboardingCompleted":false,
                                         "stats":{"frameCount":0,"popcornCount":0,"ratingCount":0},
                                         "createdAt":"%s"}
                                        """
                                                .formatted(userId, BIRTH_DATE, CREATED_AT),
                                        true));
    }

    @Test
    @DisplayName("소셜 전용 계정은 hasPassword가 거짓이고 연결된 제공자를 이름순으로 모두 반환한다")
    void socialOnlyAccountHasNoPasswordAndListsProviders() throws Exception {
        long userId = socialOnlyUser("social@example.com", "소셜러버");
        linkProvider(userId, "KAKAO");
        linkProvider(userId, "GOOGLE");

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.linkedProviders", contains("GOOGLE", "KAKAO")));
    }

    @Test
    @DisplayName("탈퇴한 계정의 유효한 토큰으로는 조회할 수 없다")
    void withdrawnAccountCannotReadProfile() throws Exception {
        long userId = insertUser("gone@example.com", null, "탈퇴계정", "WITHDRAWN", "PRIVATE", false);

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("활성 계정이 아니면 조회할 수 없다")
    void inactiveAccountCannotReadProfile() throws Exception {
        long userId =
                insertUser(
                        "inactive@example.com",
                        "$2a$10$hash",
                        "비활성계정",
                        "WITHDRAWN",
                        "PRIVATE",
                        false);

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("활동 수는 본인 행만 세고 팝콘은 대표 맛이 있는 평가만 센다")
    void statsCountOnlyOwnRows() throws Exception {
        long userId = activeUser("active@example.com", "활동러버");
        long otherId = activeUser("other@example.com", "타인러버");
        insertFlavor(1, "FLAVOR_A", 1);
        insertMovie(101, "대표 맛 있는 영화", 1L);
        insertMovie(102, "대표 맛 있는 다른 영화", 1L);
        insertMovie(103, "대표 맛 없는 영화", null);
        insertRating(201, userId, 101, "4.5");
        insertRating(202, userId, 102, "3.5");
        insertRating(203, userId, 103, "5.0");
        insertRating(204, otherId, 101, "1.0");
        insertRating(205, otherId, 102, "2.0");
        insertFrame(301, 201, userId, 101);
        insertFrame(302, 202, userId, 102);
        insertFrame(303, 203, userId, 103);
        insertFrame(304, 204, otherId, 101);
        insertFrame(305, 205, otherId, 102);

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.frameCount").value(3))
                .andExpect(jsonPath("$.stats.ratingCount").value(3))
                .andExpect(jsonPath("$.stats.popcornCount").value(2));
    }

    @Test
    @DisplayName("활동이 없으면 세 값이 모두 0이다")
    void statsAreZeroWithoutActivity() throws Exception {
        long userId = activeUser("quiet@example.com", "조용한사람");

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.frameCount").value(0))
                .andExpect(jsonPath("$.stats.popcornCount").value(0))
                .andExpect(jsonPath("$.stats.ratingCount").value(0));
    }
}
