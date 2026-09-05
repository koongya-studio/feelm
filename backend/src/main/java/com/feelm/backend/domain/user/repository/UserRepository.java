package com.feelm.backend.domain.user.repository;

import com.feelm.backend.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 이메일은 소문자로 정규화해 저장하므로 조회 값도 정규화한 뒤 넘긴다. */
    Optional<User> findByEmail(String email);

    /**
     * 닉네임 중복을 유일 인덱스와 같은 정규화 식으로 판단한다.
     *
     * <p>{@code uix_users_nickname_normalized}가 {@code lower(replace(nickname, ' ', ''))} 식의 함수 기반
     * 인덱스이므로, 같은 식을 쓰지 않으면 인덱스를 타지 못하고 판정 결과도 DB 제약과 달라진다. JPQL로는 이 식을 그대로 표현할 수 없어 Native Query를
     * 사용한다(컨벤션 9장).
     */
    @Query(
            value =
                    "select count(*) from users"
                            + " where lower(replace(nickname, ' ', ''))"
                            + " = lower(replace(:nickname, ' ', ''))",
            nativeQuery = true)
    long countByNormalizedNickname(@Param("nickname") String nickname);
}
