package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.dto.request.NotificationSettingsUpdateRequest;
import com.feelm.backend.domain.user.dto.response.NotificationSettingsResponse;
import com.feelm.backend.domain.user.entity.NotificationSettings;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.NotificationSettingsRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 수신 설정 조회·변경(API-017·018).
 *
 * <p>설정 행은 가입 트랜잭션이 사용자당 하나씩 만든다. 그래도 행이 없는 계정을 만나면 조회는 명세 기본값을 응답만 하고(저장하지 않는다) 변경은 기본행을 만들어 적용한다.
 * 조회 경로에 쓰기를 넣지 않으면서 데이터 이상이 조회 실패로 드러나지 않게 하기 위해서다(플랜 U1).
 *
 * <p>변경은 모든 항목을 함께 덮는 전체 교체라 두 요청이 겹쳐도 항목이 섞이지 않는다. 구독 OTT 설정과 달리 삭제·삽입이 없어 계정 행을 잠그지 않는다. 다만 이 보장은
 * 갱신이 모든 컬럼을 함께 쓴다는 전제 위에 있다. 이 엔티티에 {@code @DynamicUpdate}를 붙이면 두 요청이 서로 다른 컬럼만 써서 어느 쪽도 요청하지 않은
 * 조합이 남을 수 있다(리뷰 A-F1·B-F3).
 *
 * <p>값을 실제로 바꾸는 요청 사이에서는 나중 커밋이 이긴다. 요청 값이 읽은 값과 같으면 갱신 자체가 생기지 않으므로, 그 요청은 응답한 값과 다른 상태를 남길 수 있다.
 * 아무것도 바꾸지 않기로 한 요청에만 해당하므로 그대로 둔다(리뷰 B-F2).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationSettingsService {

    private final NotificationSettingsRepository notificationSettingsRepository;
    private final UserRepository userRepository;

    /**
     * 알림 수신 설정을 읽는다(API-017).
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}
     */
    public NotificationSettingsResponse getMySettings(long userId) {
        requireActiveAccount(userId);
        return NotificationSettingsResponse.from(
                notificationSettingsRepository
                        .findById(userId)
                        .orElseGet(() -> NotificationSettings.createDefault(userId)));
    }

    /**
     * 알림 수신 설정을 전체 교체한다(API-018). 멱등이며 같은 요청을 반복해도 결과가 같다.
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}
     */
    @Transactional
    public NotificationSettingsResponse replaceMySettings(
            long userId, NotificationSettingsUpdateRequest request) {
        requireActiveAccount(userId);
        NotificationSettings settings =
                notificationSettingsRepository
                        .findById(userId)
                        .orElseGet(
                                () ->
                                        notificationSettingsRepository.save(
                                                NotificationSettings.createDefault(userId)));
        settings.replace(
                request.rating(),
                request.recommendation(),
                request.partyInvite(),
                request.reportIssued(),
                request.marketing());
        return NotificationSettingsResponse.from(settings);
    }

    private void requireActiveAccount(long userId) {
        userRepository
                .findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
