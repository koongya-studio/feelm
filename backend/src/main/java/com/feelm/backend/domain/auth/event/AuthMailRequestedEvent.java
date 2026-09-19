package com.feelm.backend.domain.auth.event;

import com.feelm.backend.infrastructure.mail.MailKind;

/**
 * 계정 메일을 보내야 한다는 사실을 알린다. 인증번호 발송(API-078)이 인증번호 메일을, 재설정 요청(API-010)이 재설정 메일을 발행한다.
 *
 * <p>토큰 원문은 발급 시점에만 알 수 있어 이벤트에 실어 넘긴다. 이벤트는 프로세스 안에서만 전달되며 저장·직렬화하지 않는다.
 *
 * <p>메일 종류마다 이벤트 타입을 두지 않는다(S15P21E106-284). 두 타입의 필드가 같고 리스너 본문도 종류를 빼면 같았다. 종류를 값으로 실으면 새 메일이 생겨도
 * 이벤트·리스너를 만들지 않는다.
 *
 * @param kind 메일 종류. 제목·본문·링크와 발송 실패 로그의 복구 안내를 정한다
 */
public record AuthMailRequestedEvent(MailKind kind, Long userId, String email, String rawToken) {}
