package com.feelm.backend.domain.user.dto.request;

/**
 * API-013 회원 탈퇴 요청.
 *
 * <p>본인 확인 수단이 둘이라 <b>어느 쪽인지는 이 본문이 고른다</b>. {@code provider}가 있으면 소셜 재인증, 없으면 비밀번호다
 * (S15P21E106-435 결정 D2). 비밀번호가 없는 계정이 {@code provider} 없이 오면 계약대로 {@code 409 PASSWORD_NOT_SET}이며,
 * 그 응답은 "비밀번호 경로로는 탈퇴할 수 없다"는 뜻으로 남는다.
 *
 * <p>{@code provider}가 비어 있는 값이면 <b>없는 것과 같게</b> 본다. 빈 문자열을 소셜 요청으로 받으면 비밀번호를 보낸 이메일 계정이 {@code
 * UNSUPPORTED_PROVIDER}로 거절되는데, 그 사용자가 고칠 수 있는 것이 아니다.
 *
 * <p>어느 필드도 <b>Bean Validation으로 강제하지 않는다.</b> 비어 있으면 요청이 Service에 닿기 전에 400이 되는데, 그러면 비밀번호가 없는 소셜
 * 전용 계정이 계약에 적힌 {@code 409 PASSWORD_NOT_SET}에 영영 도달하지 못한다(리뷰 A-F7·B-F6). 계정 판정이 값 검증보다 앞서야 하므로 순서를
 * Service가 정한다 — 계정 상태 → 확인 수단 선택 → 값 유무 → 대조.
 *
 * <p>비밀번호의 형식도 검사하지 않는다. 가입 시점의 규칙이 바뀌면 이전 규칙으로 만든 비밀번호를 가진 계정이 탈퇴하지 못하게 되고, 대조는 어차피 해시가 판단한다.
 *
 * @param password 이메일 경로의 현재 비밀번호
 * @param provider 소셜 경로의 제공자 이름({@code google}·{@code kakao}·{@code naver}, 대소문자 무시)
 * @param code 소셜 경로의 인가 코드
 * @param redirectUri 인가 코드를 받은 주소. 허용 목록과 정확히 일치해야 한다(D-4)
 */
public record AccountWithdrawalRequest(
        String password, String provider, String code, String redirectUri) {

    /** 소셜 재인증 경로인지 판정한다. 값 검증이 아니라 경로 선택이므로 {@code code}·{@code redirectUri}는 보지 않는다. */
    public boolean usesSocialReauthentication() {
        return provider != null && !provider.isBlank();
    }
}
