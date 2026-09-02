package com.feelm.backend.domain.auth;

/**
 * 폐기되지 않은 Refresh Token 식별자를 관리한다.
 *
 * <p>토큰의 수명은 토큰 자체의 만료 시각이 정하고, 이 저장소는 "아직 폐기되지 않았는가"만 판단한다. 즉 허용 목록이다. 저장소를 두는 이유는 Access Token과
 * 달리 Refresh Token은 취소할 수 있어야 하기 때문이다(ADR-0009).
 */
public interface RefreshTokenRepository {

    /** 발급한 토큰 식별자를 유효한 것으로 등록한다. */
    void save(long userId, String tokenId);

    /** 아직 폐기되지 않았는지 확인한다. 상태를 바꾸지 않으므로 검증 목적으로만 쓴다. */
    boolean exists(long userId, String tokenId);

    /**
     * 토큰 식별자 하나를 폐기하고, 폐기 직전에 실제로 유효했는지를 반환한다.
     *
     * <p>확인과 삭제를 한 번의 원자적 연산으로 수행한다. 둘을 나누면 같은 토큰을 든 두 요청이 동시에 들어왔을 때 양쪽 모두 확인을 통과해 재사용 감지가 뚫린다.
     *
     * @return 이 호출이 유효한 토큰을 폐기했으면 {@code true}, 이미 폐기된 토큰이었으면 {@code false}
     */
    boolean deleteIfPresent(long userId, String tokenId);

    /**
     * 현재 토큰을 소비하고 새 토큰을 등록하는 회전을 원자적으로 수행한다.
     *
     * <p>현재 토큰이 이미 없다면 재사용으로 보고 해당 사용자의 토큰을 모두 폐기한다.
     *
     * @return 현재 토큰이 유효해 회전에 성공했으면 {@code true}, 재사용이 감지됐으면 {@code false}
     */
    boolean rotate(long userId, String currentTokenId, String newTokenId);

    /** 해당 사용자의 Refresh Token을 모두 폐기한다. 재사용 감지와 전 기기 로그아웃에 쓴다. */
    void deleteAll(long userId);
}
