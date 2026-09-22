package com.feelm.backend.domain.user.controller;

import static org.springframework.http.HttpStatus.NO_CONTENT;

import com.feelm.backend.domain.user.dto.response.AvatarResponse;
import com.feelm.backend.domain.user.service.AvatarService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users/me/avatar")
@RequiredArgsConstructor
public class UserAvatarController {

    private final AvatarService avatarService;

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AvatarResponse upload(
            @AuthenticationPrincipal Long userId, @RequestPart("file") MultipartFile file) {
        try {
            return avatarService.upload(userId, file.getBytes());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.AVATAR_STORAGE_UNAVAILABLE);
        }
    }

    @DeleteMapping
    @ResponseStatus(NO_CONTENT)
    void delete(@AuthenticationPrincipal Long userId) {
        avatarService.delete(userId);
    }
}
