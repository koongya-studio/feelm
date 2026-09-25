package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.auth.entity.UserAuthProvider;
import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.domain.taste.service.TasteQueryService;
import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.domain.user.dto.request.ProfileUpdateRequest;
import com.feelm.backend.domain.user.dto.response.UserProfileResponse;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserActivityCountRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ConstraintViolations;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API-014 내 프로필 조회, API-015 내 프로필 수정. */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final UserAuthProviderRepository authProviderRepository;
    private final UserActivityCountRepository activityCountRepository;
    private final TasteQueryService tasteQueryService;
    private final AvatarStorage avatarStorage;

    /** API-014 내 프로필 조회. */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(long userId) {
        return toResponse(activeUserOrReject(userRepository.findById(userId)));
    }

    /**
     * API-015 내 프로필 수정. 전달된 필드만 바꾼다.
     *
     * <p>계정 행을 잠그고 읽어 같은 사용자의 동시 수정이 서로의 결과를 덮어쓰지 않게 한다. 잠그는 행이 계정 하나뿐이라 기존 "토큰 → 계정" 잠금 순서
     * 규약(API-011·012)과 충돌하지 않는다.
     */
    @Transactional
    public UserProfileResponse updateProfile(long userId, ProfileUpdateRequest request) {
        User user = activeUserOrReject(userRepository.findByIdForUpdate(userId));
        applyNickname(user, request.nickname());
        if (request.birthDate() != null) {
            user.changeBirthDate(request.birthDate());
        }
        if (request.gender() != null) {
            user.changeGender(request.gender());
        }
        if (request.visibility() != null) {
            user.changeVisibility(request.visibility());
        }
        if (request.frameVisibility() != null) {
            user.changeFrameVisibility(request.frameVisibility());
        }
        if (request.popcornVisibility() != null) {
            user.changePopcornVisibility(request.popcornVisibility());
        }
        if (request.reviewVisibility() != null) {
            user.changeReviewVisibility(request.reviewVisibility());
        }
        flush();
        return toResponse(user);
    }

    /**
     * 활성 계정이 아니면 거절한다.
     *
     * <p>Access Token은 계정 상태를 담지 않아 탈퇴·미인증 계정도 잔여 수명 동안 서명이 유효하다. 비밀번호 변경(API-012)과 같은 방향으로 막는다.
     */
    private User activeUserOrReject(Optional<User> found) {
        return found.filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /**
     * 닉네임 변경을 반영한다. 정규화 값이 현재 닉네임과 같으면 중복 검사를 건너뛴다.
     *
     * <p>건너뛰지 않으면 자기 자신이 검사에 걸려 대소문자만 바꾸는 수정이 409가 된다(플랜 결정 D-6).
     */
    private void applyNickname(User user, String nickname) {
        if (nickname == null) {
            return;
        }
        if (!NicknamePolicy.normalize(nickname).equals(NicknamePolicy.normalize(user.getNickname()))
                && userRepository.countByNormalizedNickname(nickname) > 0) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        }
        user.changeNickname(nickname);
    }

    /**
     * 변경을 즉시 반영해 유일 제약 위반을 이 트랜잭션 안에서 잡는다.
     *
     * <p>사전 검사와 갱신 사이에 다른 요청이 같은 닉네임을 차지했으면 커밋이 실패하는데, 그때 500이 아니라 사전 검사와 같은 응답을 주어야 한다.
     */
    private void flush() {
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw ConstraintViolations.accountDuplicationOf(exception);
        }
    }

    private UserProfileResponse toResponse(User user) {
        UserActivityCountRepository.ActivityCounts counts =
                activityCountRepository.countByUserId(user.getId());
        long popcornCount = tasteQueryService.getPopcornBucket(user.getId()).totalCount();
        return UserProfileResponse.of(
                user,
                avatarStorage.publicUrl(user.getAvatarStorageKey()),
                linkedProviders(user.getId()),
                new UserProfileResponse.Stats(
                        counts.frameCount(), popcornCount, counts.ratingCount()));
    }

    private List<String> linkedProviders(long userId) {
        return authProviderRepository.findByUserIdOrderByProviderAsc(userId).stream()
                .map(UserAuthProvider::getProvider)
                .map(Enum::name)
                .toList();
    }
}
