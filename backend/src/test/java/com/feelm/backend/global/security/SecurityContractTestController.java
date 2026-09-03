package com.feelm.backend.global.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 골격 검증 전용 테스트 컨트롤러. 테스트 소스에만 존재하며 운영 코드에 포함되지 않는다. */
@RestController
@RequestMapping("/api/v1/test/security")
class SecurityContractTestController {

    /** 회원 전용 경로. 현재 사용자 조회 지점이 컨트롤러에서 어떻게 보이는지도 함께 보여 준다. */
    @GetMapping("/me")
    Long me(@AuthenticationPrincipal Long userId) {
        return userId;
    }
}

/** auth 하위 경로가 와일드카드로 공개되지 않는지 검증하기 위한 회원 전용 컨트롤러. */
@RestController
@RequestMapping("/api/v1/auth")
class PrivateAuthContractTestController {

    @GetMapping("/private-test")
    Long privateAuth(@AuthenticationPrincipal Long userId) {
        return userId;
    }
}
