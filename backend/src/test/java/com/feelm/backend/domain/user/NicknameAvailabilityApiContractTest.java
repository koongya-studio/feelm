package com.feelm.backend.domain.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 닉네임 사용 가능 확인(API-016) 계약 테스트. */
@DisplayName("닉네임 사용 가능 확인 API — API-016 계약")
class NicknameAvailabilityApiContractTest extends UserProfileApiTestSupport {

    @Test
    @DisplayName("인증 없이 호출할 수 있다")
    void availabilityIsPublic() throws Exception {
        mockMvc.perform(get(NICKNAME_PATH).param("nickname", "사용가능닉네임")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("아무도 쓰지 않는 닉네임은 사용 가능하고 사유가 없다")
    void reportsAvailableNickname() throws Exception {
        activeUser("user@example.com", "이미쓰는닉네임");

        mockMvc.perform(get(NICKNAME_PATH).param("nickname", "빈닉네임"))
                .andExpect(status().isOk())
                // 계약이 사용 가능한 경우의 reason을 null로 명시하므로 필드 집합까지 함께 고정한다.
                .andExpect(
                        content()
                                .json(
                                        "{\"nickname\":\"빈닉네임\",\"available\":true,\"reason\":null}",
                                        true));
    }

    @Test
    @DisplayName("정규화가 같은 닉네임이 있으면 DUPLICATED다")
    void reportsDuplicatedNickname() throws Exception {
        activeUser("user@example.com", "Popcorn");

        mockMvc.perform(get(NICKNAME_PATH).param("nickname", "popcorn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("DUPLICATED"));
    }

    @Test
    @DisplayName("형식을 어긴 닉네임은 400이 아니라 INVALID_FORMAT이다")
    void reportsInvalidFormat() throws Exception {
        for (String nickname : List.of("a", "공백 포함", "특수!문자", "열한글자를넘기는닉네임입니다", "", " ", "   ")) {
            mockMvc.perform(get(NICKNAME_PATH).param("nickname", nickname))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.reason").value("INVALID_FORMAT"));
        }
    }

    @Test
    @DisplayName("파라미터 자체가 없으면 400이다")
    void rejectsMissingParameter() throws Exception {
        mockMvc.perform(get(NICKNAME_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("호출자 자신의 닉네임도 DUPLICATED로 응답한다")
    void ownNicknameIsReportedDuplicated() throws Exception {
        long userId = activeUser("user@example.com", "Popcorn");

        // 공개 API라 인증을 보지 않으므로 토큰을 실어도 자기 행이 중복으로 잡힌다(API-016 처리 규칙).
        mockMvc.perform(
                        get(NICKNAME_PATH)
                                .param("nickname", "POPCORN")
                                .header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("DUPLICATED"));
    }

    @Test
    @DisplayName("형식 위반과 중복에 모두 걸리는 값은 형식 위반으로 응답한다")
    void formatCheckPrecedesDuplicateCheck() throws Exception {
        activeUser("user@example.com", "팝콘러버");

        mockMvc.perform(get(NICKNAME_PATH).param("nickname", "팝콘 러버"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("INVALID_FORMAT"));
    }
}
