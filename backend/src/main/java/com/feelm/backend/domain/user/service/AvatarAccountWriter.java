package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AvatarAccountWriter {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    void requireActive(long userId) {
        userRepository
                .findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    @Transactional
    String replace(long userId, String storageKey) {
        return activeUser(userId).replaceAvatar(storageKey);
    }

    @Transactional
    String remove(long userId) {
        return activeUser(userId).removeAvatar();
    }

    private User activeUser(long userId) {
        return userRepository
                .findByIdForUpdate(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
