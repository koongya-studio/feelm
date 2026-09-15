package com.feelm.backend.domain.user.dto.request;

/**
 * API-013 회원 탈퇴 요청.
 *
 * <p>이메일 계정은 비밀번호가 필수지만 <b>Bean Validation으로 강제하지 않는다.</b> 비어 있으면 요청이 Service에 닿기 전에 400이 되는데, 그러면
 * 비밀번호가 없는 소셜 전용 계정이 계약에 적힌 {@code 409 PASSWORD_NOT_SET}에 영영 도달하지 못한다(리뷰 A-F7·B-F6). 계정 판정이 값 검증보다
 * 앞서야 하므로 순서를 Service가 정한다 — 계정 상태 → 비밀번호 보유 → 값 유무 → 대조.
 *
 * <p>값의 형식도 검사하지 않는다. 가입 시점의 규칙이 바뀌면 이전 규칙으로 만든 비밀번호를 가진 계정이 탈퇴하지 못하게 되고, 대조는 어차피 해시가 판단한다.
 */
public record AccountWithdrawalRequest(String password) {}
