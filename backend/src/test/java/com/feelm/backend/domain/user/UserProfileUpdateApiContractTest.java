package com.feelm.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 내 프로필 수정(API-015) 계약 테스트. */
@DisplayName("내 프로필 수정 API — API-015 계약")
class UserProfileUpdateApiContractTest extends UserProfileApiTestSupport {

    @Test
    @DisplayName("프로필 수정은 인증이 필요하다")
    void updateRequiresAuthentication() throws Exception {
        mockMvc.perform(update("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("전달한 필드만 바꾸고 나머지는 유지하며 조회와 같은 본문을 반환한다")
    void updatesOnlyProvidedFields() throws Exception {
        long userId = activeUser("user@example.com", "이전닉네임");

        mockMvc.perform(update("{\"nickname\":\"새닉네임\"}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.onboardingCompleted").value(false))
                .andExpect(jsonPath("$.stats.ratingCount").value(0))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT));
    }

    @Test
    @DisplayName("공개 범위·성별·생년월일을 함께 바꾸면 모두 저장된다")
    void updatesVisibilityAndGenderAndBirthDate() throws Exception {
        long userId = activeUser("user@example.com", "유지닉네임");

        mockMvc.perform(
                        update(
                                "{\"visibility\":\"PUBLIC\",\"gender\":\"OTHER\",\"birthDate\":\"2000-01-02\"}",
                                userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.gender").value("OTHER"))
                .andExpect(jsonPath("$.birthDate").value("2000-01-02"))
                .andExpect(jsonPath("$.nickname").value("유지닉네임"));

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.gender").value("OTHER"))
                .andExpect(jsonPath("$.birthDate").value("2000-01-02"));
    }

    @Test
    @DisplayName("빈 본문은 아무것도 바꾸지 않는다")
    void emptyBodyChangesNothing() throws Exception {
        long userId = activeUser("user@example.com", "그대로닉네임");

        mockMvc.perform(update("{}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("그대로닉네임"))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    @DisplayName("닉네임 형식 위반은 400이고 계정은 바뀌지 않는다")
    void rejectsInvalidNicknameFormat() throws Exception {
        long userId = activeUser("user@example.com", "원래닉네임");

        for (String nickname : List.of("a", "열한글자를넘기는닉네임입니다", "공백 포함", "특수!문자")) {
            mockMvc.perform(update("{\"nickname\":\"" + nickname + "\"}", userId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(nicknameOf(userId)).isEqualTo("원래닉네임");
    }

    @Test
    @DisplayName("허용하지 않는 성별 값은 400이다")
    void rejectsInvalidGender() throws Exception {
        long userId = activeUser("user@example.com", "성별테스트");

        mockMvc.perform(update("{\"gender\":\"UNKNOWN\"}", userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("생년월일 범위는 가입 경로와 같게 제한하지 않는다")
    void doesNotConstrainBirthDateRange() throws Exception {
        long userId = activeUser("user@example.com", "생일테스트");

        // 가입(API-001·008)이 받아들이는 값을 수정이 거절하면 경로별 기준이 갈린다(리뷰 B-4).
        mockMvc.perform(update("{\"birthDate\":\"2999-01-01\"}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthDate").value("2999-01-01"));
    }

    @Test
    @DisplayName("다른 사용자가 쓰는 닉네임은 409다")
    void rejectsNicknameTakenByAnotherUser() throws Exception {
        long userId = activeUser("user@example.com", "내닉네임");
        activeUser("other@example.com", "팝콘러버");

        mockMvc.perform(update("{\"nickname\":\"팝콘러버\"}", userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
        assertThat(nicknameOf(userId)).isEqualTo("내닉네임");
    }

    @Test
    @DisplayName("중복 비교는 대소문자를 무시한다")
    void nicknameComparisonIgnoresCase() throws Exception {
        long userId = activeUser("user@example.com", "myNickname");
        activeUser("other@example.com", "Popcorn");

        mockMvc.perform(update("{\"nickname\":\"popcorn\"}", userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
    }

    @Test
    @DisplayName("자기 닉네임의 대소문자만 바꾸는 수정은 허용한다")
    void allowsRecasingOwnNickname() throws Exception {
        long userId = activeUser("user@example.com", "Popcorn");

        mockMvc.perform(update("{\"nickname\":\"POPCORN\"}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("POPCORN"));
        assertThat(nicknameOf(userId)).isEqualTo("POPCORN");
    }

    @Test
    @DisplayName("이메일은 본문에 실려도 변경되지 않는다")
    void ignoresImmutableEmailField() throws Exception {
        long userId = activeUser("user@example.com", "이메일테스트");

        mockMvc.perform(update("{\"email\":\"other@example.com\",\"nickname\":\"바뀐닉네임\"}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.nickname").value("바뀐닉네임"));
    }

    @Test
    @DisplayName("탈퇴한 계정은 수정할 수 없다")
    void withdrawnAccountCannotUpdateProfile() throws Exception {
        long userId = insertUser("gone@example.com", null, "탈퇴계정", "WITHDRAWN", "PRIVATE", false);

        mockMvc.perform(update("{\"nickname\":\"새닉네임\"}", userId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(nicknameOf(userId)).isEqualTo("탈퇴계정");
    }

    @Test
    @DisplayName("명시적 null은 400이고 계정은 바뀌지 않는다")
    void explicitNullIsRejected() throws Exception {
        long userId = activeUser("user@example.com", "널테스트");

        for (String body :
                List.of(
                        "{\"nickname\":null}",
                        "{\"birthDate\":null}",
                        "{\"gender\":null}",
                        "{\"visibility\":null}",
                        "{\"frameVisibility\":null}",
                        "{\"popcornVisibility\":null}",
                        "{\"reviewVisibility\":null}")) {
            mockMvc.perform(update(body, userId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(nicknameOf(userId)).isEqualTo("널테스트");
        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(jsonPath("$.frameVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.popcornVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.reviewVisibility").value("PUBLIC"));
    }

    @Test
    @DisplayName("항목별 공개 범위 세 값을 저장하고 다시 조회해도 그대로다")
    void updatesItemVisibilities() throws Exception {
        long userId = activeUser("user@example.com", "항목공개");

        mockMvc.perform(
                        update(
                                """
                                {"frameVisibility":"PRIVATE","popcornVisibility":"PRIVATE",
                                 "reviewVisibility":"PRIVATE"}
                                """,
                                userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameVisibility").value("PRIVATE"))
                .andExpect(jsonPath("$.popcornVisibility").value("PRIVATE"))
                .andExpect(jsonPath("$.reviewVisibility").value("PRIVATE"))
                // 프로필 공개 범위와 다른 필드는 그대로다.
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.nickname").value("항목공개"));

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameVisibility").value("PRIVATE"))
                .andExpect(jsonPath("$.popcornVisibility").value("PRIVATE"))
                .andExpect(jsonPath("$.reviewVisibility").value("PRIVATE"));

        // 하나만 보내면 그 하나만 바뀐다.
        mockMvc.perform(update("{\"popcornVisibility\":\"PUBLIC\"}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameVisibility").value("PRIVATE"))
                .andExpect(jsonPath("$.popcornVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.reviewVisibility").value("PRIVATE"));
    }

    @Test
    @DisplayName("항목별 공개 범위에 계약에 없는 값을 보내면 400이다")
    void unknownItemVisibilityIsRejected() throws Exception {
        long userId = activeUser("user@example.com", "파티원만");

        // 와이어프레임의 `파티원만`은 계약 값이 아니다(S15P21E106-856 D-1).
        for (String body :
                List.of(
                        "{\"frameVisibility\":\"PARTY\"}",
                        "{\"popcornVisibility\":\"FRIENDS\"}",
                        "{\"reviewVisibility\":\"public\"}",
                        // 숫자와 숫자 문자열을 enum 순번으로 읽지 않는다(리뷰 B-1)
                        "{\"frameVisibility\":1}",
                        "{\"popcornVisibility\":\"1\"}",
                        "{\"reviewVisibility\":0}",
                        "{\"visibility\":1}",
                        "{\"gender\":0}",
                        "{\"frameVisibility\":[\"PRIVATE\"]}")) {
            mockMvc.perform(update(body, userId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(jsonPath("$.frameVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.popcornVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.reviewVisibility").value("PUBLIC"));
    }

    @Test
    @DisplayName("필드 부재는 명시적 null과 달리 변경 없음으로 처리한다")
    void absentFieldIsNotTreatedAsNull() throws Exception {
        long userId = activeUser("user@example.com", "부재테스트");

        mockMvc.perform(update("{\"visibility\":\"PUBLIC\"}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("부재테스트"))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    @DisplayName("JSON 객체가 아닌 본문은 400이다")
    void rejectsNonObjectBody() throws Exception {
        long userId = activeUser("user@example.com", "본문테스트");

        for (String body : List.of("[]", "\"abc\"", "123", "true")) {
            mockMvc.perform(update(body, userId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(nicknameOf(userId)).isEqualTo("본문테스트");
    }

    @Test
    @DisplayName("빈 문자열과 공백만 있는 값은 무시하지 않고 400으로 거절한다")
    void rejectsEmptyStringValue() throws Exception {
        long userId = activeUser("user@example.com", "빈값테스트");

        // Jackson이 날짜·enum의 빈 문자열을 null로 강제 변환해 조용히 무시되던 경로다(종합 리뷰 3장).
        // 변환 전에 값을 trim하므로 공백만 있는 값도 같은 경로를 탄다(MR !173 리뷰).
        for (String body :
                List.of(
                        "{\"birthDate\":\"\"}",
                        "{\"gender\":\"\"}",
                        "{\"visibility\":\"\"}",
                        "{\"nickname\":\"\"}",
                        "{\"birthDate\":\" \"}",
                        "{\"gender\":\" \"}",
                        "{\"visibility\":\" \"}",
                        "{\"nickname\":\" \"}",
                        "{\"frameVisibility\":\"\"}",
                        "{\"popcornVisibility\":\" \"}",
                        "{\"reviewVisibility\":\"\"}")) {
            mockMvc.perform(update(body, userId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE))
                .andExpect(jsonPath("$.gender").value("FEMALE"));
    }

    @Test
    @DisplayName("같은 계정에 대한 동시 수정이 서로의 변경을 잃지 않는다")
    void concurrentUpdatesToSameAccountDoNotLoseWrites() throws Exception {
        long userId = activeUser("user@example.com", "동시성테스트");
        List<String> bodies = List.of("{\"nickname\":\"동시성닉네임\"}", "{\"visibility\":\"PUBLIC\"}");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(bodies.size());
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (String body : bodies) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await(5, TimeUnit.SECONDS);
                                    return mockMvc.perform(update(body, userId))
                                            .andReturn()
                                            .getResponse();
                                }));
            }
            start.countDown();
            for (Future<MockHttpServletResponse> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("동시성닉네임"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    private MockHttpServletRequestBuilder update(String body) {
        return patch(ME_PATH).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder update(String body, long userId) {
        return update(body).header("Authorization", bearerFor(userId));
    }
}
