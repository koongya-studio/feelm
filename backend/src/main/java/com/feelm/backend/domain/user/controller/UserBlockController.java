package com.feelm.backend.domain.user.controller;

import com.feelm.backend.domain.user.dto.response.BlockedUserResponse;
import com.feelm.backend.domain.user.dto.response.UserBlockResponse;
import com.feelm.backend.domain.user.service.UserBlockService;
import com.feelm.backend.global.response.CursorPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API-020~022 사용자 차단, 차단 해제, 차단 목록. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserBlockController {

    private final UserBlockService userBlockService;

    @PostMapping("/{userId}/block")
    @ResponseStatus(HttpStatus.CREATED)
    public UserBlockResponse block(
            @AuthenticationPrincipal Long requesterId, @PathVariable long userId) {
        return userBlockService.block(requesterId, userId);
    }

    @DeleteMapping("/{userId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal Long requesterId, @PathVariable long userId) {
        userBlockService.unblock(requesterId, userId);
    }

    @GetMapping("/me/blocks")
    public CursorPageResponse<BlockedUserResponse> blocks(
            @AuthenticationPrincipal Long requesterId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return userBlockService.list(requesterId, cursor, size);
    }
}
