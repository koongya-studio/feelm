package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.domain.user.dto.response.NicknameAvailabilityResponse;
import com.feelm.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API-016 닉네임 사용 가능 확인. */
@Service
@RequiredArgsConstructor
public class NicknameAvailabilityService {

    private final UserRepository userRepository;

    /**
     * 형식을 먼저 보고 그다음 중복을 본다.
     *
     * <p>순서를 고정하는 이유는 공백이 든 값처럼 두 사유에 모두 걸리는 입력에서 응답이 흔들리지 않게 하기 위해서다(플랜 결정 D-8). 형식 위반은 400이 아니라
     * {@code available:false}인 200이다. 400은 파라미터 자체가 없을 때만이며 그 판정은 Controller가 한다.
     *
     * <p>중복 비교는 가입(API-001)·수정(API-015)과 같은 정규화 식을 쓴다. 결과는 예약이 아니므로 두 경로가 저장 시점에 다시 검사한다.
     */
    @Transactional(readOnly = true)
    public NicknameAvailabilityResponse check(String nickname) {
        if (!NicknamePolicy.isValidFormat(nickname)) {
            return NicknameAvailabilityResponse.invalidFormat(nickname);
        }
        if (userRepository.countByNormalizedNickname(nickname) > 0) {
            return NicknameAvailabilityResponse.duplicated(nickname);
        }
        return NicknameAvailabilityResponse.available(nickname);
    }
}
