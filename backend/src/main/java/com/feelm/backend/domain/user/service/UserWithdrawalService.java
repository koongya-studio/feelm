package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.auth.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * API-013 회원 탈퇴.
 *
 * <p>계정 행은 지우지 않고 {@code WITHDRAWN}으로 바꾼 뒤 식별 가능한 값을 치환한다(D-9). 다른 사용자의 데이터가 {@code users.id}를 가리키고
 * 있어 행을 지우면 그쪽 무결성이 깨지기 때문이다. 치환으로 두 유일 제약이 풀리므로 같은 이메일·닉네임으로 즉시 재가입할 수 있다.
 *
 * <p>이 서비스를 {@code auth}가 아니라 {@code user}에 두는 이유는 경로가 {@code /users/me}이고 주된 변경 대상이 {@code users}
 * 행이기 때문이다. 비밀번호 변경(API-012)이 {@code auth}에 있는 것은 자격 증명 자체를 바꾸는 일이라 기준이 다르다.
 *
 * <p>지금은 이메일 계정만 탈퇴할 수 있다. 소셜 전용 계정의 본인 확인은 계약이 "최근 로그인(10분 이내)"을 가정으로 적어 두었으나 마지막 로그인 시각을 기록하는 곳이
 * 없어 이번 범위에서 제외했다(플랜 결정 U1). 비밀번호 변경이 같은 상황에 쓰는 {@code PASSWORD_NOT_SET}으로 거절한다.
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final WithdrawnAccountWriter writer;
    private final UserBlockService userBlockService;
    private final RefreshTokenService refreshTokenService;

    /**
     * 계정을 파기하고 뒷정리를 한다. 이 메서드에는 트랜잭션이 없다 — 아래 세 단계가 각각 독립이어야 하기 때문이다.
     *
     * <p><b>1단계</b>는 계정 행을 잠근 채 자격 확인부터 치환·소셜 수단 삭제까지 한 트랜잭션으로 끝낸다. 되살아나면 안 되는 것과, 벌크 삭제가 아니라 영속성
     * 컨텍스트를 건드리지 않는 삭제(OTT 구독·알림 설정·온보딩 취향)를 여기에 둔다.
     *
     * <p><b>2단계</b>는 커밋 직후 Refresh Token을 폐기한다. Redis 쓰기라 트랜잭션에 묶이지 않으므로 잠금 구간 밖으로 뺐다(컨벤션 8장). 1단계가
     * 실패하면 아예 오지 않고, 여기서 실패하면 계정은 이미 {@code WITHDRAWN}이라 남은 세션도 회전에서 거절된다({@link
     * RefreshTokenService#rotate}).
     *
     * <p><b>3단계</b>는 남은 인증 토큰과 차단 관계를 각각의 트랜잭션에서 지운다. 1단계에 넣지 않는 이유가 둘 다 다르다. 인증 토큰은 계정을 잠근 채 지우면
     * 저장소가 세워 둔 <b>토큰 → 계정</b> 잠금 순서를 거슬러 교착한다(리뷰 A-F1·B-F1). 차단은 {@link UserBlockService}가 쓰기 메서드를
     * 최상위 트랜잭션에서만 부르도록 못 박아 두었다 — 벌크 삭제가 영속성 컨텍스트를 비워 상위 트랜잭션의 엔티티를 detach 하기 때문이다.
     *
     * <p>3단계가 실패하면 고아 행이 남는다. 계정은 이미 {@code WITHDRAWN}이라 로그인·재설정·인증 어느 경로로도 쓰이지 않고 재가입도 막지 않으므로,
     * 원자성보다 교착을 피하는 쪽을 택했다.
     */
    public void withdraw(long userId, String password) {
        writer.withdrawAccount(userId, password);
        refreshTokenService.revokeAll(userId);
        writer.deleteAuthTokens(userId);
        userBlockService.deleteAllForWithdrawal(userId);
    }
}
