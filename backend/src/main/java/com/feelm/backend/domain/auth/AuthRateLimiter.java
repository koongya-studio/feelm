package com.feelm.backend.domain.auth;

import com.feelm.backend.domain.auth.MailRateLimitProperties.Quota;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 인증·재설정 메일 발송과 로그인 연속 실패를 Redis로 제한한다(플랜 결정 4).
 *
 * <p>메일 발송은 세 축을 함께 본다. 이메일 단위 최소 간격, IP 단위 창 카운터, 전역 창 카운터다(S15P21E106-281 결정 D-1). 이메일 간격만으로는 서로
 * 다른 이메일을 대량으로 넣는 발송 폭탄을 막지 못한다. IP·전역 카운터는 재발송(API-003)과 재설정 요청(API-010)이 공유한다. 두 경로가 같은 SMTP 계정의
 * 발송 쿼터를 소모하므로 공격자에게는 같은 발송 수단이고, 경로별로 나누면 한쪽을 막아도 다른 쪽으로 같은 양을 더 보낼 수 있기 때문이다(결정 D-2). 이메일 간격은
 * 지금처럼 경로별로 분리한다. 재발송과 재설정은 사용자가 서로 다른 이유로 요청하는 정상 흐름이다.
 *
 * <p>평가 순서는 이메일 간격 → IP → 전역이다(결정 D-3). 같은 이메일을 반복해서 넣는 요청은 이메일 간격에서 먼저 막혀 IP·전역 예산을 소모하지 않는다. 앞 축을
 * 통과하며 올린 카운터는 뒤 축이 거절해도 되돌리지 않는다. 되돌리려면 원자성이 깨지고, 거절된 요청이 예산을 조금 소모하는 쪽이 방어에 안전한 방향이다. 카운터는 계정 존재
 * 여부나 실제 발송 여부와 무관하게 시도 시점에 센다. 발송한 것만 세면 카운터 소모 여부가 계정 존재를 알려 주는 신호가 된다(결정 D-4).
 *
 * <p>이메일 간격만은 <b>확인을 먼저 하고 기록은 마지막</b>에 한다(결정 D-3의 보완, 2026-09-08). 카운터를 되돌리지 않는 원칙은 요청을 보낸 쪽이 자기
 * 예산을 조금 잃는 데까지만 안전하다. 이메일 간격 슬롯은 요청자의 것이 아니라 <b>그 이메일 주인</b>의 것이어서, IP·전역에서 거절될 요청이 슬롯을 심으면 예산을 이미
 * 넘긴 공격자가 임의의 이메일에 대해 재설정·재발송을 분당 한 건씩 무기한 지연시킬 수 있다. 공유 NAT에서 남의 IP 예산 때문에 429를 받은 사용자도 자기 요청이 심은
 * 슬롯에 다시 걸린다.
 *
 * <p>창 카운터는 로그인 제한과 같은 고정 창이다(결정 D-5). 창 경계에서 최대 2배 버스트가 가능하지만, 슬라이딩 윈도로 얻는 정확도보다 구현·검증의 단순함이 크다고
 * 보았다.
 *
 * <p>클라이언트 IP는 호출자가 {@code HttpServletRequest#getRemoteAddr()}로 얻어 넘긴다. {@code X-Forwarded-For}는 믿지
 * 않는다. 신뢰할 프록시 목록 없이 헤더를 믿으면 헤더 한 줄로 IP 제한을 우회할 수 있기 때문이다(결정 D-9). <b>Nginx가 API를 프록시하게 되면</b> 모든
 * 요청이 Nginx 컨테이너 IP 하나로 보여 전체 사용자가 IP 제한에 함께 걸린다. 그때 {@code X-Forwarded-For} 전달과 {@code
 * server.forward-headers-strategy}를 함께 도입해야 한다(후속 업무).
 *
 * <p>키에는 이메일과 IP 원문 대신 해시를 쓴다. Redis 키 목록만으로 어떤 이메일이 서비스에 있는지, 어떤 IP가 서비스를 썼는지 알 수 있으면 안 되고, IPv6까지
 * 길이가 고르게 된다. 창 길이를 키에 넣어 설정을 바꿔도 이전 창의 카운터가 새 설정에 섞이지 않게 한다(결정 D-7). 네임스페이스는 Refresh Token이 쓰는
 * {@code auth:}를 이어 사용한다.
 *
 * <p>로그인 제한은 실패를 나중에 기록하는 대신 시도를 먼저 센다. 확인과 기록이 분리되어 있으면 동시 요청이 모두 확인을 통과해 제한 횟수보다 많은 비밀번호 대조가 일어나기
 * 때문이다. 카운터는 DB 트랜잭션과 무관하게 즉시 반영된다. Redis가 응답하지 않으면 예외가 그대로 올라가 인증 요청은 500으로 실패한다(fail-closed). 로그인
 * 성공 자체가 Refresh Token을 Redis에 저장해야 하므로 리미터만 열어 두어도 얻는 것이 없고, 발송 제한을 열면 장애 중 메일 폭탄이 가능해지기 때문이다(결정
 * D-10). 장애 시 정책을 바꾸려면 후속 업무에서 정한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(MailRateLimitProperties.class)
public class AuthRateLimiter {

    private static final String RESEND_KEY_PREFIX = "auth:email-resend:";
    private static final String LOGIN_FAILURE_KEY_PREFIX = "auth:login-fail:";
    private static final String PASSWORD_RESET_KEY_PREFIX = "auth:password-reset:";
    private static final String MAIL_IP_KEY_PREFIX = "auth:mail:ip:";
    private static final String MAIL_GLOBAL_KEY_PREFIX = "auth:mail:global:";

    /**
     * 시도 횟수를 올리고 만료를 보장한 뒤 올린 횟수를 돌려주는 스크립트. {@code INCR}와 {@code PEXPIRE}를 따로 보내면 그 사이에 연결이 끊겼을 때
     * TTL 없는 카운터가 남아 해당 이메일이 무기한 429가 된다. 첫 시도이거나(카운터가 1) 어떤 이유로든 TTL이 없으면 만료를 다시 건다.
     */
    private static final DefaultRedisScript<Long> RECORD_ATTEMPT_SCRIPT =
            new DefaultRedisScript<>(
                    "local count = redis.call('INCR', KEYS[1]); "
                            + "if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then "
                            + "redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                            + "return count",
                    Long.class);

    /** 로그인 연속 실패를 세는 기간. */
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(10);

    /** 이 횟수까지는 비밀번호를 대조하고, 넘는 시도는 대조 없이 429로 막는다(플랜 결정 14). */
    private static final long LOGIN_ATTEMPT_LIMIT = 5;

    private final StringRedisTemplate redisTemplate;
    private final MailRateLimitProperties mailRateLimit;

    /**
     * 인증 메일 재발송(API-003)의 발송 자리를 확보한다.
     *
     * @param clientIp 요청을 보낸 클라이언트 IP
     * @return 발송해도 되면 {@code true}, 어느 한 축이라도 제한을 넘었으면 {@code false}
     */
    public boolean tryAcquireResendSlot(String email, String clientIp) {
        return tryAcquireMailSendSlot(resendKey(email), clientIp);
    }

    /**
     * 비밀번호 재설정 요청(API-010)의 발송 자리를 확보한다.
     *
     * <p>계정 존재 여부와 무관하게 요청 이메일 기준으로 센다. 존재하는 계정만 셌다면 429 응답 자체가 가입 여부를 알려 주는 신호가 되기 때문이다(API-010 처리
     * 규칙).
     *
     * @param clientIp 요청을 보낸 클라이언트 IP
     * @return 처리해도 되면 {@code true}, 어느 한 축이라도 제한을 넘었으면 {@code false}
     */
    public boolean tryAcquirePasswordResetSlot(String email, String clientIp) {
        return tryAcquireMailSendSlot(passwordResetKey(email), clientIp);
    }

    /**
     * 로그인 시도 자리를 확보한다. 비밀번호를 대조하기 전에 호출한다.
     *
     * <p>확인과 기록을 한 번의 원자적 증가로 합쳐, 동시 요청이 몰려도 한 창 안에서 대조가 정확히 제한 횟수만 일어난다. 첫 시도에 만료를 걸어 최초 시도 시점부터
     * 기간이 흐른다. 시도가 실패로 남을지는 호출자가 정한다. 비밀번호가 맞으면 {@link #clearLoginAttempts}로 지우고, 틀리면 그대로 둔다.
     *
     * @return 대조해도 되면 {@code true}, 제한을 넘었으면 {@code false}
     */
    public boolean tryAcquireLoginAttempt(String email) {
        Long count =
                redisTemplate.execute(
                        RECORD_ATTEMPT_SCRIPT,
                        List.of(loginFailureKey(email)),
                        Long.toString(LOGIN_FAILURE_WINDOW.toMillis()));
        return count != null && count <= LOGIN_ATTEMPT_LIMIT;
    }

    /** 자격이 확인되면 시도 기록을 지운다. */
    public void clearLoginAttempts(String email) {
        redisTemplate.delete(loginFailureKey(email));
    }

    /**
     * 세 축을 순서대로 확보한다. 이메일 간격은 먼저 확인하고 기록은 마지막에 한다.
     *
     * <p>확인을 앞에 두어 같은 이메일을 반복하는 요청이 IP·전역 예산을 소모하지 않는 D-3의 성질을 유지하고, 기록을 뒤로 미뤄 IP·전역에서 거절될 요청이 그
     * 이메일의 간격 슬롯을 소모하지 않게 한다(클래스 주석의 보완 항목).
     *
     * <p>확인과 기록이 나뉘어 그 사이에 같은 이메일의 요청이 동시에 들어올 수 있지만, 기록이 {@code SET NX}라 정확히 한 건만 통과한다. 경합에서 진 요청은
     * IP·전역 카운터를 하나 올린 채 거절되는데, 이는 거절된 요청이 예산을 조금 소모하는 D-3의 방향과 같다.
     */
    private boolean tryAcquireMailSendSlot(String emailIntervalKey, String clientIp) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(emailIntervalKey))) {
            return false;
        }
        return tryAcquireMailSendBudget(clientIp) && tryAcquireEmailInterval(emailIntervalKey);
    }

    /** 이메일 단위 최소 간격을 확보한다. 간격 안의 두 번째 요청은 값이 이미 있어 실패한다. */
    private boolean tryAcquireEmailInterval(String key) {
        Boolean acquired =
                redisTemplate.opsForValue().setIfAbsent(key, "1", mailRateLimit.emailInterval());
        return !Boolean.FALSE.equals(acquired);
    }

    /**
     * IP 단위 예산과 전역 예산을 순서대로 확보한다. 앞 축에서 막히면 뒤 축의 카운터는 올리지 않는다(결정 D-3).
     *
     * <p>전역 제한에 걸린 경우에만 WARN을 남긴다. 한 IP가 자기 예산을 다 쓰는 것은 정상 운영 중에도 일어나지만, 전역 예산이 마르는 것은 정상 사용자까지 메일을
     * 받지 못하는 상태라 운영 중 인지해야 한다. IP와 이메일 원문은 남기지 않는다({@code docs/backend-convention.md} 12장, 결정
     * D-11).
     *
     * <p>WARN은 <b>창마다 한 번</b>, 카운터가 한도를 처음 넘는 요청에서만 남긴다. 거절되는 모든 요청에 남기면 공격이 이어지는 동안 초당 요청 수만큼 같은
     * 줄이 쌓여, 정작 로그가 필요한 순간에 다른 기록을 밀어낸다. 창이 만료되고 카운터가 다시 한도를 넘으면 그 창의 WARN이 다시 한 번 남는다.
     */
    private boolean tryAcquireMailSendBudget(String clientIp) {
        for (Quota quota : mailRateLimit.ip()) {
            if (recordAttempt(mailIpKey(clientIp, quota.window()), quota.window())
                    > quota.limit()) {
                return false;
            }
        }
        for (Quota quota : mailRateLimit.global()) {
            long count = recordAttempt(mailGlobalKey(quota.window()), quota.window());
            if (count > quota.limit()) {
                if (count == quota.limit() + 1) {
                    log.warn(
                            "인증·재설정 메일 발송의 전역 제한에 도달했습니다. 창 길이: {}, 한도: {}건",
                            quota.window(),
                            quota.limit());
                }
                return false;
            }
        }
        return true;
    }

    /**
     * 창 카운터를 하나 올리고 올린 값을 돌려준다.
     *
     * <p>스크립트가 값을 주지 못하면 어떤 한도보다도 큰 값을 돌려준다. 카운터를 확인할 수 없는 상태에서 통과시키지 않는 쪽이 fail-closed(결정 D-10)와
     * 방향이 같다.
     */
    private long recordAttempt(String key, Duration window) {
        Long count =
                redisTemplate.execute(
                        RECORD_ATTEMPT_SCRIPT, List.of(key), Long.toString(window.toMillis()));
        return count == null ? Long.MAX_VALUE : count;
    }

    private String resendKey(String email) {
        return RESEND_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    private String loginFailureKey(String email) {
        return LOGIN_FAILURE_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    private String passwordResetKey(String email) {
        return PASSWORD_RESET_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    private String mailIpKey(String clientIp, Duration window) {
        return MAIL_IP_KEY_PREFIX + Sha256Hasher.hex(clientIp) + ":" + window.toSeconds();
    }

    private String mailGlobalKey(Duration window) {
        return MAIL_GLOBAL_KEY_PREFIX + window.toSeconds();
    }
}
