package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.service.SocialReauthenticator;
import com.feelm.backend.domain.party.service.PartyWithdrawalService;
import com.feelm.backend.domain.user.dto.request.AccountWithdrawalRequest;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
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
 * <p>본인 확인 수단은 둘이다. 비밀번호와, 연결된 소셜 계정의 재인가 코드다(S15P21E106-435 결정 D1). 비밀번호가 없는 계정이 탈퇴할 수 없던 제약이 이것으로
 * 풀린다. 계약이 본인 확인으로 적어 두었던 "최근 로그인(10분 이내)"은 채택하지 않았다 — 그 창은 Access Token의 유효 구간과 겹쳐 토큰을 쥔 쪽에게 추가
 * 관문이 되지 못한다.
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final WithdrawnAccountWriter writer;
    private final UserBlockService userBlockService;
    private final RefreshTokenService refreshTokenService;
    private final SocialReauthenticator socialReauthenticator;
    private final PartyWithdrawalService partyWithdrawalService;

    /**
     * 계정을 파기하고 뒷정리를 한다. 이 메서드에는 트랜잭션이 없다 — 아래 다섯 단계가 각각 독립이어야 하기 때문이다.
     *
     * <p><b>0단계</b>는 소셜 경로일 때만 있다. 인가 코드를 제공자와 교환해 식별자를 얻는 외부 HTTP 호출이라 트랜잭션을 열기 전에 끝낸다(컨벤션 8장).
     * 여기서 얻은 식별자를 계정과 대조하는 일은 1단계가 잠금 안에서 한다. 이 단계에서 실패하면 계정은 아무것도 바뀌지 않는다.
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
     * <p><b>4단계</b>는 참여 중인 파티에서 탈퇴자를 떼어 낸다(S15P21E106-436). 파티 도메인의 진입점 하나만 부르며, 그 안에서 파티마다 따로
     * 트랜잭션이 열린다. 계정 잠금을 쥔 1단계에 넣지 않은 이유는 계정 행을 잠근 채 파티 행까지 잠그면 잠금 범위가 계정 하나에서 그 사람이 속한 모든 파티로 번지기
     * 때문이다. 순서가 3단계 뒤인 것은 차단 정리가 파티 합류 규칙(FR-39)과 얽혀 있어 먼저 끝내 두는 편이 읽기 쉬워서다.
     *
     * <p>3·4단계가 실패하면 고아 행이 남는다. 계정은 이미 {@code WITHDRAWN}이라 로그인·재설정·인증 어느 경로로도 쓰이지 않고 재가입도 막지 않으므로,
     * 원자성보다 교착을 피하는 쪽을 택했다.
     */
    public void withdraw(long userId, AccountWithdrawalRequest request) {
        SocialCredential credential = reauthenticate(request);
        writer.withdrawAccount(userId, request.password(), credential);
        refreshTokenService.revokeAll(userId);
        writer.deleteAuthTokens(userId);
        userBlockService.deleteAllForWithdrawal(userId);
        partyWithdrawalService.leaveAllForWithdrawal(userId);
    }

    /**
     * 소셜 경로면 인가 코드를 교환하고, 아니면 {@code null}을 돌려준다.
     *
     * <p>제공자 이름과 인가 코드는 교환을 <b>시도하기 전에</b> 본다. 목록에 없는 제공자와 빈 코드는 제공자에게 물어볼 것도 없이 잘못된 요청이고, 쓸모없는 외부
     * 호출을 남기지 않기 위해서다. {@code redirectUri}는 {@link SocialReauthenticator}가 같은 자리에서 허용 목록과
     * 대조한다(D-4).
     *
     * <p>여기서는 계정 상태를 보지 않는다. 계정 판정은 잠금을 쥔 1단계의 몫이며, 잠그기 전에 읽은 상태는 잠근 뒤의 상태와 다를 수 있다. 그래서 이미 탈퇴한 계정의
     * 요청도 교환까지는 진행한 뒤 1단계에서 {@code UNAUTHORIZED}로 거절된다. 되돌릴 수 없는 변경은 아무것도 일어나지 않는다.
     */
    private SocialCredential reauthenticate(AccountWithdrawalRequest request) {
        if (!request.usesSocialReauthentication()) {
            return null;
        }
        OAuthProvider provider = OAuthProvider.from(request.provider());
        if (request.code() == null || request.code().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String providerUserId =
                socialReauthenticator.authenticate(provider, request.code(), request.redirectUri());
        return new SocialCredential(provider, providerUserId);
    }

    /** 교환으로 확인한 제공자 계정. 1단계가 이 값이 정말 그 사용자에게 연결돼 있는지 대조한다. */
    record SocialCredential(OAuthProvider provider, String providerUserId) {}
}
