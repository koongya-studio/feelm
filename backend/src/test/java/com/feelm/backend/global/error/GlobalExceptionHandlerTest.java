package com.feelm.backend.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 인증 필터를 끄고 예외 처리 자체만 검증한다. 인증 실패 응답은 AuthContractTest가 다룬다.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = ErrorContractTestController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("GlobalExceptionHandler — API-000 오류 응답 형식")
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("검증 실패는 400 VALIDATION_FAILED와 details.errors[] 필드별 사유로 응답한다")
    void validationFailureReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(
                        post("/test/errors/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"\",\"score\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details.errors").isArray())
                .andExpect(jsonPath("$.details.errors[?(@.field == 'nickname')]").exists())
                .andExpect(jsonPath("$.details.errors[?(@.field == 'score')]").exists());
    }

    @Test
    @DisplayName("본문을 읽을 수 없는 요청은 400 VALIDATION_FAILED로 응답한다")
    void unreadableBodyReturns400() throws Exception {
        mockMvc.perform(
                        post("/test/errors/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("BusinessException은 ErrorCode에 매핑된 상태와 코드로 응답한다")
    void businessExceptionUsesMappedStatusAndCode() throws Exception {
        mockMvc.perform(get("/test/errors/business-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("details 없는 오류 응답은 details 키를 직렬화하지 않는다")
    void detailsIsOmittedWhenAbsent() throws Exception {
        mockMvc.perform(get("/test/errors/business-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("BusinessException에 담은 details는 오류 응답에 그대로 포함된다")
    void businessExceptionDetailsAreSerialized() throws Exception {
        mockMvc.perform(get("/test/errors/business-conflict-with-details"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details.field").value("nickname"));
    }

    @Test
    @DisplayName("미처리 예외는 500 INTERNAL_ERROR로 변환하고 내부 메시지를 노출하지 않는다")
    void unhandledExceptionReturns500WithoutInternalDetail() throws Exception {
        mockMvc.perform(get("/test/errors/unhandled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "internal-secret-detail"))));
    }

    @Test
    @DisplayName("알림 스트림 외 비동기 타임아웃은 기존 500 오류 응답을 유지한다")
    void nonStreamAsyncTimeoutReturns500() throws Exception {
        mockMvc.perform(get("/test/errors/async-timeout"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("존재하지 않는 경로는 404 RESOURCE_NOT_FOUND로 응답한다")
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/test/errors/unknown-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("파라미터 검증 실패는 400 VALIDATION_FAILED로 응답한다 (리뷰 F2)")
    void parameterValidationFailureReturns400() throws Exception {
        mockMvc.perform(get("/test/errors/parameter-validation").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("허용하지 않는 메서드는 405 METHOD_NOT_ALLOWED로 응답한다 (결정 5)")
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(post("/test/errors/get-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type은 415 UNSUPPORTED_MEDIA_TYPE으로 응답한다 (결정 5)")
    void unsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(
                        post("/test/errors/validation")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("nickname=a"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("필수 쿠키 누락은 400 VALIDATION_FAILED로 응답한다 (리뷰 !39)")
    void missingRequiredCookieReturns400() throws Exception {
        mockMvc.perform(get("/test/errors/required-cookie"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("필수 헤더 누락은 400 VALIDATION_FAILED로 응답한다 (리뷰 !39)")
    void missingRequiredHeaderReturns400() throws Exception {
        mockMvc.perform(get("/test/errors/required-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
