package com.feelm.backend.domain.auth.repository;

import com.feelm.backend.domain.auth.entity.OneTimeTokenEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * 1회용 토큰 리포지토리의 공통 상한이다(S15P21E106-284).
 *
 * <p>공통 발급기({@code OneTimeTokenIssuer})가 종류에 상관없이 미사용 토큰을 무효화하고 새 토큰을 저장할 수 있게 하는 타입 경계일 뿐이다. 빈으로
 * 만들지 않으며({@code @NoRepositoryBean}), 실제 조회 메서드는 하위 인터페이스가 <b>각자 다시 선언한다</b>. 잠금 유무가 종류마다 다르고
 * ({@code password_reset_tokens}만 {@code PESSIMISTIC_WRITE}) 상속만으로 애노테이션이 적용되는지에 기대지 않기 위해서다.
 */
@NoRepositoryBean
public interface OneTimeTokenRepository<T extends OneTimeTokenEntity>
        extends JpaRepository<T, Long> {

    /** 아직 소진되지 않은 토큰. 재발급이 이전 토큰을 무효화할 때 사용한다. */
    List<T> findAllByUserIdAndUsedAtIsNull(Long userId);
}
