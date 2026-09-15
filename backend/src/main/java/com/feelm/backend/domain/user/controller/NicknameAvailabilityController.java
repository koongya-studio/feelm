package com.feelm.backend.domain.user.controller;

import com.feelm.backend.domain.user.dto.response.NicknameAvailabilityResponse;
import com.feelm.backend.domain.user.service.NicknameAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 닉네임 사용 가능 확인(API-016). 가입 화면에서도 쓰므로 인증 없이 호출한다.
 *
 * <p>400은 파라미터 <b>자체가 없을 때</b>뿐이다({@code @RequestParam}의 필수 판정). 값이 있으면 빈 문자열이나 공백이어도 형식 위반이므로
 * {@code reason:"INVALID_FORMAT"}인 200으로 응답한다(API-016 처리 규칙). 값 검증을 컨트롤러에 두면 그 경계가 400 쪽으로 넘어가 계약과
 * 어긋난다(리뷰 B-2).
 */
@RestController
@RequestMapping("/api/v1/users/nickname-availability")
@RequiredArgsConstructor
public class NicknameAvailabilityController {

    private final NicknameAvailabilityService nicknameAvailabilityService;

    @GetMapping
    NicknameAvailabilityResponse check(@RequestParam String nickname) {
        return nicknameAvailabilityService.check(nickname);
    }
}
