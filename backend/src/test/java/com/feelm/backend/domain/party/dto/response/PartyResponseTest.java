package com.feelm.backend.domain.party.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.party.dto.response.PartyResponse.UserSummary;
import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.domain.user.entity.Gender;
import com.feelm.backend.domain.user.entity.User;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 파티 응답의 사용자 요약 변환(S15P21E106-759). */
@DisplayName("파티 응답 사용자 요약 — 탈퇴자 표기")
class PartyResponseTest {

    @Test
    @DisplayName("T8 탈퇴자 문구는 닉네임 규칙에 맞지 않아 살아 있는 닉네임과 겹칠 수 없다")
    void withdrawnNicknameCannotCollideWithLivingNickname() {
        assertThat(NicknamePolicy.isValidFormat(UserSummary.WITHDRAWN_NICKNAME)).isFalse();
    }

    @Test
    @DisplayName("T8 탈퇴한 사용자는 문구로, 살아 있는 사용자는 저장된 닉네임으로 내보낸다")
    void labelsWithdrawnUserAndKeepsLivingNickname() {
        User living = user(10, "팝콘러버");
        User withdrawn = user(20, "영화광");
        withdrawn.withdraw(Instant.parse("2026-09-01T00:00:00Z"));

        UserSummary livingSummary = UserSummary.from(living);
        UserSummary withdrawnSummary = UserSummary.from(withdrawn);

        assertThat(livingSummary).isEqualTo(new UserSummary(10, "팝콘러버"));
        assertThat(withdrawnSummary.userId()).isEqualTo(20);
        assertThat(withdrawnSummary.nickname()).isEqualTo(UserSummary.WITHDRAWN_NICKNAME);
        // 내부 치환 값은 응답에 나가지 않는다.
        assertThat(withdrawn.getNickname()).startsWith("withdrawn-");
        assertThat(withdrawnSummary.nickname()).doesNotStartWith("withdrawn-");
    }

    private static User user(long id, String nickname) {
        User user =
                User.register(
                        "user" + id + "@example.test",
                        "hash",
                        nickname,
                        LocalDate.of(2000, 1, 1),
                        Gender.OTHER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
