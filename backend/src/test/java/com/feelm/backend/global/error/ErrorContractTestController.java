package com.feelm.backend.global.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/** 공통 오류 규약(API-000) 검증 전용 테스트 컨트롤러. 테스트 소스에만 존재하며 운영 코드에 포함되지 않는다. */
@RestController
@RequestMapping("/test/errors")
class ErrorContractTestController {

    @PostMapping("/validation")
    ResponseEntity<Void> validation(@Valid @RequestBody SampleRequest request) {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/business-not-found")
    void businessNotFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @GetMapping("/business-conflict-with-details")
    void businessConflictWithDetails() {
        throw new BusinessException(ErrorCode.CONFLICT, Map.of("field", "nickname"));
    }

    @GetMapping("/unhandled")
    void unhandled() {
        throw new IllegalStateException("internal-secret-detail");
    }

    @GetMapping("/async-timeout")
    void asyncTimeout() {
        throw new AsyncRequestTimeoutException();
    }

    @GetMapping("/get-only")
    ResponseEntity<Void> getOnly() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/parameter-validation")
    ResponseEntity<Void> parameterValidation(@RequestParam @Min(1) int size) {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/required-cookie")
    ResponseEntity<Void> requiredCookie(@CookieValue("refresh_token") String refreshToken) {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/required-header")
    ResponseEntity<Void> requiredHeader(@RequestHeader("X-Required-Header") String value) {
        return ResponseEntity.ok().build();
    }

    record SampleRequest(@NotBlank String nickname, @Min(1) int score) {}
}
