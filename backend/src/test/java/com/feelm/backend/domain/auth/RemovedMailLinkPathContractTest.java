package com.feelm.backend.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 메일 링크 인증 경로가 실제로 사라졌는지 확인한다(S15P21E106-604).
 *
 * <p>삭제는 지운 코드가 아니라 <b>남은 경로</b>로 확인해야 한다. 컨트롤러만 지우고 공개 경로 목록을 남겨 두는 실수는 코드 리뷰에서 잘 보이지 않는다.
 *
 * <p>응답은 두 갈래다. 공개 목록에서도 빠졌으므로 인증 없는 요청은 {@code 401}이고, 회원 토큰으로 부르면 매핑이 없어 {@code 404}다. 두 응답 모두 "이
 * 경로는 더 이상 없다"는 같은 사실을 말한다.
 */
@DisplayName("사라진 메일 링크 인증 경로 — 옛 API-002·003")
class RemovedMailLinkPathContractTest extends EmailAuthApiTestSupport {

    private static final String[] REMOVED_PATHS = {
        "/api/v1/auth/email/verify", "/api/v1/auth/email/resend"
    };

    @Autowired private TokenProvider tokenProvider;

    @Test
    @DisplayName("인증 없이 부르면 401이다")
    void anonymousRequestIsUnauthorized() throws Exception {
        for (String path : REMOVED_PATHS) {
            postJson(path, "{}")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    @DisplayName("회원 토큰으로 불러도 핸들러가 없어 404다")
    void memberRequestIsNotFound() throws Exception {
        long userId = signupActive(newAccount());
        String accessToken = tokenProvider.createAccessToken(userId);

        for (String path : REMOVED_PATHS) {
            mockMvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isNotFound());
        }
    }
}
