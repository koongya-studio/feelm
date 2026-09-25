package com.feelm.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.domain.taste.dto.response.PopcornBucketResponse;
import com.feelm.backend.domain.taste.service.TasteQueryService;
import com.feelm.backend.domain.user.dto.request.ProfileUpdateRequest;
import com.feelm.backend.domain.user.entity.Gender;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserActivityCountRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 계약 테스트로는 도달할 수 없는 두 지점을 고정한다.
 *
 * <p>H2 테스트 스키마에는 함수 기반 유일 인덱스 {@code uix_users_nickname_normalized}가 없어(JPA가 DDL을 만든다) 사전 검사와 갱신
 * 사이의 경합을 계약 테스트로 재현할 수 없다. 그래서 제약 위반 → 409 <b>배선</b>(리뷰 A-4)과 계정 행 잠금 사용(리뷰 B-5)을 여기서 본다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("프로필 수정 서비스 — 계약 테스트가 못 보는 경로")
class UserProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserAuthProviderRepository authProviderRepository;
    @Mock private UserActivityCountRepository activityCountRepository;
    @Mock private TasteQueryService tasteQueryService;
    @Mock private AvatarStorage avatarStorage;

    @InjectMocks private UserProfileService userProfileService;

    @Test
    @DisplayName("갱신이 닉네임 유일 제약을 위반하면 409 NICKNAME_DUPLICATED로 옮긴다")
    void translatesUniqueConstraintViolationToConflict() {
        User user = activeUser();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(userRepository.countByNormalizedNickname(anyString())).thenReturn(0L);
        doThrow(violationOf("uix_users_nickname_normalized")).when(userRepository).flush();

        assertThatThrownBy(
                        () ->
                                userProfileService.updateProfile(
                                        1L,
                                        new ProfileUpdateRequest(
                                                "새닉네임", null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NICKNAME_DUPLICATED);
    }

    @Test
    @DisplayName("수정은 잠금 없는 조회가 아니라 계정 행을 잠그고 읽는다")
    void readsAccountRowWithWriteLock() {
        User user = activeUser();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        stubResponseAssembly();

        userProfileService.updateProfile(
                1L,
                new ProfileUpdateRequest(
                        null, LocalDate.of(2000, 1, 2), null, null, null, null, null));

        // findById로 되돌리는 회귀가 여기서 반드시 실패한다(D-7의 보호를 결정적으로 고정).
        verify(userRepository).findByIdForUpdate(1L);
        verify(userRepository, never()).findById(anyLong());
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(2000, 1, 2));
    }

    @Test
    @DisplayName("활성 계정이 아니면 잠금 조회 결과를 401로 거절한다")
    void rejectsInactiveAccount() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                userProfileService.updateProfile(
                                        1L,
                                        new ProfileUpdateRequest(
                                                "새닉네임", null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private void stubResponseAssembly() {
        when(activityCountRepository.countByUserId(1L))
                .thenReturn(new UserActivityCountRepository.ActivityCounts(0, 0));
        when(tasteQueryService.getPopcornBucket(1L))
                .thenReturn(new PopcornBucketResponse(0, List.of()));
        when(authProviderRepository.findByUserIdOrderByProviderAsc(1L)).thenReturn(List.of());
    }

    private User activeUser() {
        User user =
                User.registerSocial(
                        "user@example.com", "이전닉네임", LocalDate.of(1999, 3, 14), Gender.FEMALE);
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    private DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException(
                "constraint violation",
                new ConstraintViolationException("constraint violation", null, constraintName));
    }
}
