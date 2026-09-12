package com.feelm.backend.domain.user.controller;

import com.feelm.backend.domain.user.dto.response.SearchedUserResponse;
import com.feelm.backend.domain.user.service.UserSearchService;
import com.feelm.backend.global.response.CursorPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API-019 사용자 검색. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserSearchController {

    private final UserSearchService userSearchService;

    /**
     * {@code nickname}을 필수로 선언하지 않고 Service에서 판정한다. 누락과 형식 위반이 모두 {@code VALIDATION_FAILED}로 같은 곳에서
     * 결정돼야 계약이 한 갈래로 유지된다.
     */
    @GetMapping("/search")
    public CursorPageResponse<SearchedUserResponse> search(
            @AuthenticationPrincipal Long requesterId,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return userSearchService.search(requesterId, nickname, cursor, size);
    }
}
