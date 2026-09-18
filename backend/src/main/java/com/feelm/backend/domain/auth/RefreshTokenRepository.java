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
     * <p>회전 직후 짧은 유예 동안에는 같은 토큰이 한 번 더 들어와도 탈취로 보지 않고, 그 세션의 현재 식별자를 돌려준다. 재발급 응답을 받지 못한 채 끊긴 요청이 한
     * 번 더 도착하는 일은 정상 사용에서도 생기는데(새로고침·이탈), 이것까지 탈취로 처리하면 사용자의 모든 세션이 끊긴다(S15P21E106-596).
     *
     * <p>유예 안에 회전이 이어졌다면 소비된 식별자들의 링크를 따라가 현재 식별자에 도달한다. 한 세대만 보면 "중복이 도착하기 전에 다른 요청이 한 번 더 회전했다"는
     * 흔한 경우까지 탈취로 판정된다(리뷰 A-2·B-2).
     *
     * <p>유예를 지난 재사용은 그대로 탈취로 보고 해당 사용자의 토큰을 모두 폐기한다. 사용자의 토큰 집합 자체가 없는 경우는 폐기할 것이 없으므로 구분해서 알린다. 둘
     * 다 거절이지만 원인이 다르다. 전자는 토큰 하나가 두 번 쓰인 것이고, 후자는 이미 전량 폐기됐거나 저장소가 비워진 것이다.
     *
     * @return 회전 결과. 거절인 경우 {@link Rotation#tokenId()}는 {@code null}이다
     */
    Rotation rotate(long userId, String currentTokenId, String newTokenId);

    /** 회전 결과와, 이 회전 뒤 현재 세션이 써야 할 토큰 식별자. */
    record Rotation(Outcome outcome, String tokenId) {

        public static Rotation rotated(String tokenId) {
            return new Rotation(Outcome.ROTATED, tokenId);
        }

        public static Rotation replayed(String tokenId) {
            return new Rotation(Outcome.REPLAYED, tokenId);
        }

        public static Rotation rejected(Outcome outcome) {
            return new Rotation(outcome, null);
        }

        public boolean accepted() {
            return tokenId != null;
        }
    }

    enum Outcome {
        /** 유효한 토큰을 소비하고 새 토큰을 등록했다. */
        ROTATED,
        /** 유예 안에 같은 토큰이 다시 들어와 직전 회전의 결과를 그대로 다시 내려준다. */
        REPLAYED,
        /** 유예를 지나 이미 폐기된 토큰이 다시 쓰였다. 탈취로 보고 전량 폐기했다. */
        REUSED,
        /** 사용자의 토큰 집합 자체가 없다. 이미 전량 폐기됐거나 저장소가 비워졌다. */
        UNKNOWN_SESSION,
        /**
         * 이 토큰의 계보가 회전이 아니라 폐기로 끝났다. 로그아웃·비밀번호 변경·탈퇴 뒤에 뒤늦게 도착한 중복이다.
         *
         * <p>폐기할 것이 남아 있지 않으므로 탈취 대응을 발동하지 않는다. 발동하면 그 사이 다시 로그인한 세션이 끊긴다(리뷰 B-3).
         */
        REVOKED_SESSION
    }

    /** 해당 사용자의 Refresh Token을 모두 폐기한다. 재사용 감지와 전 기기 로그아웃에 쓴다. */
    void deleteAll(long userId);
}
