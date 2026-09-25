package com.feelm.backend.domain.user.dto.request;

import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.domain.user.entity.Gender;
import com.feelm.backend.domain.user.entity.Visibility;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * API-015 내 프로필 수정 요청. 전달한 필드만 변경한다.
 *
 * <p>필드가 <b>없으면</b> 변경하지 않는다. 반대로 <b>명시적 {@code null}</b>은 {@code 400 VALIDATION_FAILED}이며(API-015
 * 처리 규칙), 그 구분은 {@link ProfileUpdateRequestDeserializer}가 바인딩 전에 원본 JSON을 보고 판단한다. 여기까지 온 값은 모두
 * "전달된 값"이므로 Service는 {@code null}을 변경 없음으로만 해석하면 된다.
 *
 * <p>이메일은 변경할 수 없으므로 필드를 두지 않는다. 본문에 실려 와도 역직렬화에서 무시된다(API-015 처리 규칙).
 *
 * <p>{@code gender}·{@code visibility}와 세 항목 공개 범위를 enum으로 받아 허용하지 않는 값은 역직렬화 단계에서 걸러진다. 항목 공개 범위의
 * {@code null} 규칙은 {@code visibility}와 같다(S15P21E106-856).
 *
 * <p>생년월일에 범위 제약을 두지 않는다. 계약(API-001·015)과 DB 어디에도 없는 규칙이라 이 경로에만 넣으면 가입에서 통과한 값이 수정에서 거절된다(리뷰
 * B-4). 세 경로의 생년월일 규칙 통일은 후속 작업으로 분리했다.
 */
@JsonDeserialize(using = ProfileUpdateRequestDeserializer.class)
public record ProfileUpdateRequest(
        @Pattern(regexp = NicknamePolicy.PATTERN, message = NicknamePolicy.MESSAGE) String nickname,
        LocalDate birthDate,
        Gender gender,
        Visibility visibility,
        Visibility frameVisibility,
        Visibility popcornVisibility,
        Visibility reviewVisibility) {}
