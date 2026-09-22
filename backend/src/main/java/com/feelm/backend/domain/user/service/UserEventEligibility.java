package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 행동 이벤트와 탈퇴가 같은 계정 행에서 순서대로 확정되게 한다. */
@Component
@RequiredArgsConstructor
public class UserEventEligibility {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActive(long userId) {
        return userRepository.findByIdForUpdate(userId).filter(User::isActive).isPresent();
    }
}
