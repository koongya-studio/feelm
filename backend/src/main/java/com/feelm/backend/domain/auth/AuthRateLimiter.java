package com.feelm.backend.domain.auth;

import jakarta.annotation.PostConstruct;
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
 * <p>클라이언트 IP는 호출자가 {@code HttpServletRequest#getRemoteAddr()}로 얻어 넘긴다. 그 값이 헤더에서 오는 것은 <b>신뢰 경계 안의
 * 프록시</b>가 보낸 경우뿐이다(S15P21E106-288). Tomcat {@code RemoteIpValve}가 {@code
 * server.tomcat.remoteip.internal-proxies}에 있는 발신지의 요청에서만 헤더를 읽으므로, 그 밖에서 직접 온 요청의 헤더는 무시되고 실제 발신
 * IP가 남는다. 신뢰 목록 없이 헤더를 믿으면 헤더 한 줄로 IP 제한을 우회할 수 있다(결정 D-9).
 *
 * <p>읽는 헤더는 {@code X-Forwarded-For}가 아니라 {@code X-Real-IP}다(리뷰 A-7·B-3). Nginx는 이미 {@code /api}를
 * 프록시하며 {@code X-Real-IP}를 {@code $remote_addr}로 <b>덮어써</b> 보내므로 클라이언트가 보낸 값이 남지 않고 항목도 하나다. {@code
 * X-Forwarded-For}는 {@code $proxy_add_x_forwarded_for}로 이어 붙이는데, 목록의 항목이 모두 신뢰 대역이면 밸브가 <b>맨 왼쪽</b>
 * 값을 채택한다. backend 컨테이너는 호스트 포트를 공개하지 않아 Tomcat에 닿는 모든 요청의 발신지가 신뢰 대역이므로, 그 성질을 그대로 두면 내부 대역에서 온
 * 요청이 헤더로 예산 주체를 바꿀 수 있다.
 *
 * <p>IP 축의 예산은 주소 하나가 아니라 {@link ClientIpBucket}이 정한 <b>버킷 단위</b>로 나눈다. IPv6 클라이언트는 주소만 바꾸면 예산을 새로
 * 얻으므로 기본 {@code /64}로 묶고, NAT 뒤 공용 주소를 더 넓히지 않도록 IPv4는 주소 단위를 유지한다. 길이는 {@link
 * ClientIpProperties}에 있고, 값이 없거나 주소 리터럴이 아닌 요청은 한 버킷을 공유한다.
 *
 * <p>키에는 이메일과 IP 원문 대신 해시를 쓴다. Redis 키 목록만으로 어떤 이메일이 서비스에 있는지, 어떤 IP가 서비스를 썼는지 알 수 있으면 안 되고, IPv6까지
 * 길이가 고르게 된다. 네임스페이스는 Refresh Token이 쓰는 {@code auth:}를 이어 사용한다.
 *
 * <p><b>창 길이를 키에 담지 않는 것은 로그인 이메일 축뿐이다</b>(결정 D-7). 메일 축과 로그인 IP 축은 키에 창 길이가 들어 있어 설정을 바꿔도 이전 창의
 * 카운터가 새 설정에 섞이지 않는다. 로그인 이메일 키는 창을 담지 않으므로, 창 길이를 바꾸면 <b>이미 만들어진 카운터는 옛 창이 만료될 때까지 옛 길이를 유지한다</b>.
 * 스크립트가 만료를 다시 거는 조건이 {@code count == 1}이거나 TTL이 없을 때뿐이기 때문이다. 값을 바꾼 직후에는 설정과 관측이 한 창 동안 어긋날 수 있다.
 * 로그인 이메일 키도 창을 담게 맞추는 안은 기존 계약 테스트가 이 키 형식을 알고 있어 별도 업무로 판단한다(S15P21E106-311 리뷰 A-5·B-4).
 *
 * <p>제한 값은 코드가 아니라 설정에 둔다. 메일 발송은 {@link MailRateLimitProperties}, 로그인은 {@link
 * LoginRateLimitProperties}가 읽고, 잘못된 값은 기동에 실패한다(S15P21E106-311). 로그인 제한만 상수로 남으면 같은 성격의 값이 설정과 코드로
 * 갈려 조정 지점이 둘이 된다. 다만 값은 jar에 담기는 {@code application.yml}에 있고 배포 환경변수 통로는 아직 없어, 바꾸려면 재배포가 필요하다(리뷰
 * B-7, 후속 업무 후보).
 *
 * <p>로그인은 IP 축과 이메일 축을 함께 본다(S15P21E106-312). 이메일 축만 있으면 두 경로가 열려 있다. 비밀번호 하나를 서로 다른 계정 수천 개에 한 번씩
 * 넣는 password spraying은 계정당 한도에 걸리지 않고, 피해자 이메일만 알면 틀린 비밀번호 몇 번으로 그 계정을 창 길이만큼 반복해서 잠글 수 있는데 그 시도를
 * 어디에서도 세지 않았다. IP 축은 계정 잠금 자체를 없애지는 못하지만 한 IP가 잠글 수 있는 계정 수를 묶고 spraying을 카운터에 드러낸다.
 *
 * <p>IP 축은 <b>실패가 아니라 시도</b>를 센다. 확인과 기록을 원자적 증가 하나로 합쳐야 동시 요청이 몰려도 한 창에서 비밀번호 대조가 한도만큼만 일어난다. 실패만
 * 세려면 대조를 마친 뒤에 기록해야 하고, 그러면 병렬 요청이 모두 게이트를 통과해 BCrypt 대조에 들어간다. 시도를 세면 카운터 소모 여부가 계정 존재나 비밀번호 일치를
 * 알려 주는 신호가 되지도 않는다(메일 축 결정 D-4와 같은 이유). 대가는 정상 로그인도 예산을 소모하는 것이라, 한도는 공유 NAT를 기준으로 넉넉히 둔다({@code
 * application.yml}).
 *
 * <p>평가 순서는 <b>IP → 이메일</b>이다. 메일 축(결정 D-3)과 순서가 다른 이유는 반복 요청의 성격이 다르기 때문이다. 같은 이메일을 다시 넣는 메일 요청은
 * 정상 사용자의 재시도이지만, 같은 이메일에 틀린 비밀번호를 반복하는 로그인은 그 자체가 공격 형태다. IP를 먼저 세면 한 계정을 반복해서 잠그려는 시도가 자기 IP 예산을
 * 소모해 결국 IP 축에서 끊긴다. 이메일을 먼저 보면 그 시도는 계정별 429만 받으며 IP 예산을 전혀 쓰지 않는다. 이 순서에서는 IP에서 거절된 요청이 이메일 카운터를
 * 올리지 않는 성질도 함께 얻는다. 공유 NAT의 정상 사용자가 남의 IP 예산 때문에 429를 받아도 자기 계정까지 잠기지는 않는다.
 *
 * <p>로그인의 IP 예산은 메일 축의 IP 예산과 공유하지 않는다. 메일 축을 두 엔드포인트가 공유한 이유는 둘이 같은 SMTP 발송 쿼터를 소모하기 때문인데(결정 D-2),
 * 로그인 시도는 발송 수단을 쓰지 않아 한쪽이 다른 쪽 예산을 마르게 할 이유가 없다.
 *
 * <p>IP 한도에 도달하면 <b>창마다 한 번</b> WARN을 남긴다(메일 전역 축과 같은 방식). 차단된 요청도 카운터를 계속 올리므로 카운터 값 자체는 지표가 되지
 * 못하고, 거절되는 모든 요청에 로그를 남기면 공격이 이어지는 동안 같은 줄이 초당 요청 수만큼 쌓인다. IP 원문과 이메일은 남기지 않는다({@code
 * docs/backend-convention.md} 12장). 기동 시에는 유효 한도를 한 줄 남긴다(리뷰 B-3·B-5).
 *
 * <p>IP 카운터는 로그인 성공에 지워지지 않는다. 이메일 카운터와 달리 여러 계정의 시도가 함께 쌓이는 예산이라, 유효한 계정 하나로 성공할 수 있는 공격자가 창마다 예산을
 * 되돌릴 수 있으면 spraying 한도가 사라진다.
 *
 * <p>두 축의 카운터는 DB 트랜잭션과 무관하게 즉시 반영된다. Redis가 응답하지 않으면 예외가 그대로 올라가 인증 요청은 500으로 실패한다(fail-closed).
 * 로그인 성공 자체가 Refresh Token을 Redis에 저장해야 하므로 리미터만 열어 두어도 얻는 것이 없고, 발송 제한을 열면 장애 중 메일 폭탄이 가능해지기
 * 때문이다(결정 D-10). 장애 시 정책을 바꾸려면 후속 업무에서 정한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties({
    MailRateLimitProperties.class,
    LoginRateLimitProperties.class,
    ClientIpProperties.class
})
public class AuthRateLimiter {

    private static final String RESEND_KEY_PREFIX = "auth:email-resend:";
    private static final String LOGIN_FAILURE_KEY_PREFIX = "auth:login-fail:";
    private static final String LOGIN_IP_KEY_PREFIX = "auth:login:ip:";
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

    private final StringRedisTemplate redisTemplate;
    private final MailRateLimitProperties mailRateLimit;
    private final LoginRateLimitProperties loginRateLimit;
    private final ClientIpProperties clientIpPrefixes;

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
     * <p>IP 축을 먼저 보고 이메일 축을 본다(클래스 주석의 평가 순서). 두 축 모두 확인과 기록을 한 번의 원자적 증가로 합쳐, 동시 요청이 몰려도 한 창 안에서
     * 대조가 정확히 제한 횟수만 일어난다. 첫 시도에 만료를 걸어 최초 시도 시점부터 기간이 흐른다. IP 축에서 거절되면 이메일 카운터는 올리지 않는다.
     *
     * <p>이메일 축의 시도가 실패로 남을지는 호출자가 정한다. 비밀번호가 맞으면 {@link #clearLoginAttempts}로 지우고, 틀리면 그대로 둔다. IP
     * 카운터는 성공에도 지우지 않는다.
     *
     * <p>한도와 창 길이는 {@link LoginRateLimitProperties}에서 온다(S15P21E106-311·-312). 이메일 축의 운영 기본값은
     * 5회/10분이고, IP 축의 운영 기본값은 {@code application.yml}에 있다.
     *
     * @param clientIp 요청을 보낸 클라이언트 IP
     * @return 대조해도 되면 {@code true}, 어느 한 축이라도 제한을 넘었으면 {@code false}
     */
    public boolean tryAcquireLoginAttempt(String email, String clientIp) {
        return tryAcquireLoginIpBudget(clientIp) && tryAcquireEmailLoginAttempt(email);
    }

    /** 자격이 확인되면 그 이메일의 시도 기록을 지운다. IP 예산은 여러 계정이 함께 쓰는 값이라 남긴다. */
    public void clearLoginAttempts(String email) {
        redisTemplate.delete(loginFailureKey(email));
    }

    /**
     * 클라이언트 IP의 시도 예산을 창마다 확보한다(S15P21E106-312).
     *
     * <p>목록이 비어 있으면 IP 축은 제한하지 않는다. 앞 창에서 막히면 뒤 창의 카운터는 올리지 않는다(결정 D-3과 같은 방향).
     *
     * <p>{@link #recordAttempt}를 쓰지 않는 이유는 {@link #tryAcquireEmailLoginAttempt}와 같다. 그 헬퍼의 "값을 받지
     * 못했다" 신호가 설정된 한도와 같아지면 fail-closed가 fail-open으로 뒤집힌다.
     */
    private boolean tryAcquireLoginIpBudget(String clientIp) {
        for (LoginRateLimitProperties.Quota quota : loginRateLimit.ip()) {
            Long count = increment(loginIpKey(clientIp, quota.window()), quota.window());
            if (count == null || count > quota.limit()) {
                if (count != null && count == quota.limit() + 1) {
                    log.warn(
                            "로그인 시도의 IP 단위 제한에 도달했습니다. 창 길이: {}, 한도: {}회",
                            quota.window(),
                            quota.limit());
                }
                return false;
            }
        }
        return true;
    }

    /**
     * 기동 시 로그인 IP 축의 유효 한도를 남긴다.
     *
     * <p>목록이 비어 있으면 그 축은 아무 요청도 막지 않는데, 설정 블록을 지우거나 키를 잘못 적어도 기동은 성공한다. 계약 문서(API-004)는 기본값을 약속하므로
     * 실제 값이 무엇인지 기동 로그에 남겨, 조용히 열린 상태를 운영에서 알아볼 수 있게 한다(리뷰 A-1·B-5).
     */
    @PostConstruct
    void logLoginIpBudget() {
        if (loginRateLimit.ip().isEmpty()) {
            log.warn("로그인 IP 단위 시도 제한이 설정되지 않았습니다(feelm.auth.login.ip). IP 축은 어떤 요청도 막지 않습니다.");
            return;
        }
        log.info("로그인 IP 단위 시도 제한: {}", loginRateLimit.ip());
    }

    /**
     * 이메일 축의 시도 자리를 확보한다.
     *
     * <p>{@link #recordAttempt}를 쓰지 않고 값을 직접 보는 이유는 값이 없을 때의 처리를 한도와 독립으로 두기 위해서다. 그 헬퍼는 값을 받지 못하면
     * 어떤 한도보다도 큰 값을 돌려주는데, 한도가 설정값이 된 뒤에는 {@code attempt-limit}을 {@code Long.MAX_VALUE}로 두면 그 신호가
     * 한도와 같아져 fail-closed가 fail-open으로 뒤집힌다(리뷰 A-1·B-2).
     */
    private boolean tryAcquireEmailLoginAttempt(String email) {
        Long count = increment(loginFailureKey(email), loginRateLimit.attemptWindow());
        return count != null && count <= loginRateLimit.attemptLimit();
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
        for (MailRateLimitProperties.Quota quota : mailRateLimit.ip()) {
            if (recordAttempt(mailIpKey(clientIp, quota.window()), quota.window())
                    > quota.limit()) {
                return false;
            }
        }
        for (MailRateLimitProperties.Quota quota : mailRateLimit.global()) {
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
        Long count = increment(key, window);
        return count == null ? Long.MAX_VALUE : count;
    }

    /** 창 카운터를 하나 올리고 스크립트가 준 값을 그대로 돌려준다. 값을 받지 못하면 {@code null}이다. */
    private Long increment(String key, Duration window) {
        return redisTemplate.execute(
                RECORD_ATTEMPT_SCRIPT, List.of(key), Long.toString(window.toMillis()));
    }

    private String resendKey(String email) {
        return RESEND_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    private String loginFailureKey(String email) {
        return LOGIN_FAILURE_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    /**
     * 로그인 IP 카운터 키. 메일 IP 키와 같이 창 길이를 담아 설정을 바꿔도 옛 창의 카운터가 섞이지 않게 한다(결정 D-7).
     *
     * <p>예산 주체는 주소 하나가 아니라 {@link ClientIpBucket}이 정한 버킷이다. 메일 축과 같은 단위를 쓰지 않으면 IPv6 클라이언트가 같은
     * {@code /64} 안에서 주소만 바꿔 로그인 IP 예산을 새로 얻는다(S15P21E106-288).
     */
    private String loginIpKey(String clientIp, Duration window) {
        return LOGIN_IP_KEY_PREFIX
                + Sha256Hasher.hex(ClientIpBucket.of(clientIp, clientIpPrefixes))
                + ":"
                + window.toSeconds();
    }

    private String passwordResetKey(String email) {
        return PASSWORD_RESET_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    private String mailIpKey(String clientIp, Duration window) {
        return MAIL_IP_KEY_PREFIX
                + Sha256Hasher.hex(ClientIpBucket.of(clientIp, clientIpPrefixes))
                + ":"
                + window.toSeconds();
    }

    private String mailGlobalKey(Duration window) {
        return MAIL_GLOBAL_KEY_PREFIX + window.toSeconds();
    }
}
