package com.feelm.backend.infrastructure.mail;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 전역 발송 상한을 Redis 고정 창 카운터로 지킨다(S15P21E106-287).
 *
 * <p>창 카운터는 인증 제한과 같은 고정 창이다(S15P21E106-281 결정 D-5). 창 경계에서 최대 2배 버스트가 가능하지만, SMTP 계정의 일 상한은 시간 창보다
 * 훨씬 큰 일 창이 함께 잡는다.
 *
 * <p>카운터가 세는 것은 <b>실제 발송</b>이다. 보낼 메일이 없는 요청은 발송 스레드에 들어오지 않으므로 예산을 소모하지 않고, 큐가 차서 버려진 발송도 마찬가지다.
 * 메일 종류는 가리지 않는다. 가입 인증 메일(API-001)과 재발송(API-003), 비밀번호 재설정(API-010)이 같은 SMTP 계정의 쿼터를 쓰므로 한 예산으로
 * 묶는다(결정 D-5).
 *
 * <p>키는 {@code auth:mail:sent:<창 초>}다. 옛 전역 키({@code auth:mail:global:<창 초>})는 시도를 세던 값이라 재사용하지
 * 않는다. 배포 직후 남은 옛 카운터는 TTL로 스스로 사라진다(결정 D-10). 창 길이를 키에 담으므로 설정을 바꿔도 이전 창의 카운터가 새 설정에 섞이지 않는다.
 *
 * <p><b>모든 창을 스크립트 한 번에 확인하고, 전부 통과할 때만 올린다</b>(리뷰 A-2). 창을 순서대로 올리면 앞 창이 통과하고 뒤 창이 막을 때 앞 창만 소모된다.
 * 일 창이 마른 구간에서는 시도마다 시간 창이 발송 없이 타 들어가, 시간 창 소진 경고가 진짜 원인(일 창)을 가리고 일 창이 만료된 뒤에도 최대 한 창 길이만큼 발송이 더
 * 막힌다.
 *
 * <p><b>발송이 실패하면 소모한 자리를 되돌린다</b>({@link #release()}, 리뷰 B-4). 이 상한이 지키는 것은 SMTP 계정의 쿼터이고, 실패한 발송은
 * 쿼터를 쓰지 않는다. 되돌리지 않으면 자격 증명 폐기처럼 즉시 실패하는 장애에서 상한이 몇 초 만에 마르고, 원인을 고쳐도 창이 끝날 때까지 메일이 나가지 않는다.
 *
 * <p>스크립트를 {@code AuthRateLimiter}와 공유하지 않은 것은 그 클래스가 인증 도메인에 있기 때문이다. 발송 인프라가 도메인을 참조하지 않는 쪽이 컨벤션
 * 11장의 방향이고, 두 곳의 카운터는 세는 대상(시도 / 발송)도 다르다.
 *
 * <p>값을 받지 못하면 보내지 않는다(fail-closed, 결정 D-11). 예산을 확인할 수 없는 상태에서 SMTP 쿼터를 태우지 않는다. 그 경로는 조용히 지나가지 않고
 * WARN을 남긴다(리뷰 B-13). Redis가 응답하지 않아 예외가 나면 그대로 올려 {@link MailDispatcher}가 원인을 기록한다. 이 예외는 발송 스레드에서
 * 나므로 요청은 영향을 받지 않는다.
 *
 * <p>WARN은 두 지점에서 <b>창마다 한 번</b>씩 남긴다. 상한을 넘겨 발송을 막을 때와, 사용률이 상한의 80%에 닿았을 때다. 소진 로그만 있으면 예산이 마른
 * 뒤에야 알게 되고, 마른 상태는 정상 사용자의 메일이 조용히 사라지는 상태다. 버려지는 발송마다 남기지 않는 이유는 부하가 이어지는 동안 같은 줄이 쌓여 정작 필요한 기록을
 * 밀어내기 때문이다(결정 D-8). 이메일과 사용자 식별자는 남기지 않는다({@code docs/backend-convention.md} 12장).
 *
 * <p>"창마다 한 번"은 카운터 값이 임계와 <b>정확히 일치</b>하는지가 아니라 <b>표시 키</b>({@code SET NX})로 판정한다(리뷰 B-5). 정확 일치로
 * 보면 창 도중에 한도를 내렸을 때 이미 지나간 임계값에는 영영 걸리지 않아, 재배포 없이 값을 조정하는 통로(결정 D-9)가 인지 수단(결정 D-8)을 조용히 무력화한다.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisMailSendBudget implements MailSendBudget {

    private static final String SENT_KEY_PREFIX = "auth:mail:sent:";

    private static final String EXHAUSTED_MARKER_PREFIX = "auth:mail:sent:warn:exhausted:";

    private static final String APPROACHING_MARKER_PREFIX = "auth:mail:sent:warn:approaching:";

    /**
     * 모든 창을 확인하고 전부 통과할 때만 올린다.
     *
     * <p>{@code KEYS}는 창별 카운터 키, {@code ARGV}는 창마다 (만료 밀리초, 상한) 두 값이다. 통과하면 {@code {1, 창별 새
     * 카운터...}}를, 막히면 {@code {0, 막은 창의 번호}}를 돌려준다. 확인과 증가가 한 스크립트 안에 있어 동시 발송이 상한을 넘겨 통과하지 못한다.
     *
     * <p>{@code INCR}와 {@code PEXPIRE}를 따로 보내면 그 사이에 연결이 끊겼을 때 TTL 없는 카운터가 남아 그 창의 메일이 영영 나가지 않는다.
     * 첫 발송이거나(카운터가 1) 어떤 이유로든 TTL이 없으면 만료를 다시 건다.
     */
    private static final DefaultRedisScript<List> CONSUME_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    for i = 1, #KEYS do
                      local current = tonumber(redis.call('GET', KEYS[i]) or '0')
                      if current + 1 > tonumber(ARGV[i * 2]) then return {0, i} end
                    end
                    local counts = {1}
                    for i = 1, #KEYS do
                      local count = redis.call('INCR', KEYS[i])
                      if count == 1 or redis.call('PTTL', KEYS[i]) < 0 then
                        redis.call('PEXPIRE', KEYS[i], ARGV[i * 2 - 1])
                      end
                      counts[i + 1] = count
                    end
                    return counts
                    """,
                    List.class);

    /**
     * 소모한 자리를 되돌린다. 창이 만료돼 키가 사라졌으면 아무것도 하지 않는다.
     *
     * <p>{@code EXISTS} 확인 없이 {@code DECR}하면 만료된 창에 TTL 없는 음수 카운터가 새로 생겨, 그 창이 상한만큼 더 발송을 허용하게 된다.
     *
     * <p>{@code EXISTS}가 막지 못하는 경로가 하나 있다(리뷰 C). 키 이름에 창 epoch가 없어 만료 후 재생성돼도 이름이 같으므로, {@link
     * #tryConsume()}과 이 호출 사이에 창이 만료되고 <b>다음 창의 첫 발송</b>이 키를 다시 만들면 여기서 다음 창의 카운터를 깎는다. 카운터가 0이 되면
     * 그다음 {@code INCR}가 {@code count == 1}로 TTL을 다시 걸어 고정 창도 그만큼 연장된다. 어긋나는 양은 창 경계에 걸친 in-flight
     * 발송 수(발송 풀 크기)로 묶여 SMTP 쿼터를 의미 있게 넘기지 않으므로, 창 epoch를 키에 담는 대신 이 성질을 감수한다.
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    for i = 1, #KEYS do
                      if redis.call('EXISTS', KEYS[i]) == 1 then redis.call('DECR', KEYS[i]) end
                    end
                    return 1
                    """,
                    Long.class);

    private final StringRedisTemplate redisTemplate;
    private final MailSendBudgetProperties properties;

    /**
     * 발송 한 건의 자리를 확보한다. 창 목록이 비어 있으면 상한을 적용하지 않는다.
     *
     * <p>막힌 창의 카운터는 올리지 않으므로, 마른 창이 다른 창의 예산을 함께 태우지 않는다(리뷰 A-2).
     *
     * <p>{@code @SuppressWarnings}는 {@link #CONSUME_SCRIPT}의 raw {@code List} 때문이다. {@code
     * RedisTemplate#execute}는 반환 타입을 {@code Class}로 받아 {@code List<Long>.class}를 표현할 방법이 없으므로 raw
     * {@code List.class}가 유일한 선택이고, 스크립트가 Lua 정수만 돌려주므로 원소는 항상 {@code Long}이다. 경고를 남겨 두면 다음 사람이
     * 실수인지 판단할 수 없다(리뷰 E).
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean tryConsume() {
        List<MailSendBudgetProperties.Quota> windows = properties.windows();
        if (windows.isEmpty()) {
            return true;
        }
        List<Long> result =
                redisTemplate.execute(CONSUME_SCRIPT, counterKeys(windows), consumeArgs(windows));
        if (result == null || result.isEmpty()) {
            log.warn("계정 메일의 전역 발송 상한을 확인하지 못해 발송하지 않았습니다. Redis 응답이 비어 있습니다.");
            return false;
        }
        if (result.getFirst() == 0L) {
            warnExhausted(windows.get(result.get(1).intValue() - 1));
            return false;
        }
        for (int index = 0; index < windows.size(); index++) {
            warnIfApproaching(windows.get(index), result.get(index + 1));
        }
        return true;
    }

    /** 발송이 실패했을 때 소모한 자리를 되돌린다. 실패한 발송은 SMTP 쿼터를 쓰지 않는다(리뷰 B-4). */
    @Override
    public void release() {
        List<MailSendBudgetProperties.Quota> windows = properties.windows();
        if (windows.isEmpty()) {
            return;
        }
        redisTemplate.execute(RELEASE_SCRIPT, counterKeys(windows));
    }

    /**
     * 지금 보낼 자리가 있는지 <b>소모하지 않고</b> 본다(리뷰 B-2).
     *
     * <p>요청 경로가 이 값을 보고 토큰 회전 같은 되돌릴 수 없는 부작용을 건너뛴다. 읽기만 하므로 시도가 예산을 소모하지 않는 성질(결정 D-1)은 그대로다. 값을
     * 읽지 못하면 자리가 없는 것으로 본다(fail-closed).
     *
     * <p>이 확인은 요청 경로에서 <b>계정 존재 여부와 무관하게 모든 요청에</b> 걸어야 한다. 계정이 있는 요청에만 걸면 Redis 왕복 한 번이 응답 시간 차가
     * 되어 S15P21E106-282가 없앤 신호가 되살아난다.
     */
    @Override
    public boolean hasCapacity() {
        List<MailSendBudgetProperties.Quota> windows = properties.windows();
        if (windows.isEmpty()) {
            return true;
        }
        List<String> counts = redisTemplate.opsForValue().multiGet(counterKeys(windows));
        if (counts == null || counts.size() != windows.size()) {
            return false;
        }
        for (int index = 0; index < windows.size(); index++) {
            if (count(counts.get(index)) >= windows.get(index).limit()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 기동 시 유효한 발송 상한을 남긴다.
     *
     * <p>목록이 비어 있으면 상한이 없는데, 설정 블록을 지우거나 키를 잘못 적어도 기동은 성공한다. 그 상태는 SMTP 계정의 쿼터가 마를 때까지 아무도 모르므로 실제
     * 값이 무엇인지 기동 로그에 남긴다({@code AuthRateLimiter}의 로그인 IP 축과 같은 방식).
     */
    @PostConstruct
    void logSendBudget() {
        if (properties.windows().isEmpty()) {
            log.warn("계정 메일의 전역 발송 상한이 설정되지 않았습니다(feelm.mail.send-budget). 발송량을 묶는 상한이 없습니다.");
            return;
        }
        log.info("계정 메일의 전역 발송 상한: {}", properties.windows());
    }

    private void warnExhausted(MailSendBudgetProperties.Quota quota) {
        if (firstInWindow(EXHAUSTED_MARKER_PREFIX, quota.window())) {
            log.warn(
                    "계정 메일의 전역 발송 상한에 도달했습니다. 이 창의 남은 메일은 발송하지 않습니다. 창 길이: {}, 상한: {}건",
                    quota.window(),
                    quota.limit());
        }
    }

    private void warnIfApproaching(MailSendBudgetProperties.Quota quota, long count) {
        if (count < approachingThreshold(quota.limit())) {
            return;
        }
        if (firstInWindow(APPROACHING_MARKER_PREFIX, quota.window())) {
            log.warn(
                    "계정 메일의 전역 발송 상한에 가까워졌습니다. 창 길이: {}, 발송: {}건, 상한: {}건",
                    quota.window(),
                    count,
                    quota.limit());
        }
    }

    /**
     * 이 창에서 아직 남기지 않은 경고인지 본다. 표시 키의 수명이 창 길이와 같아 창이 바뀌면 다시 한 번 남는다.
     *
     * <p>표시 키를 만들지 못하면(Redis 이상) 남기지 않는다. 경고를 위해 발송 경로에서 예외를 낼 이유가 없고, 그 상황은 발송 자체가 이미 fail-closed로
     * 막혀 다른 기록이 남는다.
     */
    private boolean firstInWindow(String prefix, Duration window) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(prefix + window.toSeconds(), "1", window));
    }

    /**
     * 예고를 남기는 발송 수. 상한의 80%를 올림한 값이다.
     *
     * <p>{@code limit - limit / 5}는 {@code ceil(limit * 0.8)}과 같고 곱셈이 없어 넘치지 않는다. 상한이 1이면 첫 발송이 곧
     * 마지막 발송이라 예고와 소진이 잇달아 나오는데, 그 설정에서는 그것이 사실이다.
     */
    private static long approachingThreshold(long limit) {
        return limit - limit / 5;
    }

    private List<String> counterKeys(List<MailSendBudgetProperties.Quota> windows) {
        return windows.stream().map(quota -> SENT_KEY_PREFIX + quota.window().toSeconds()).toList();
    }

    /** 창마다 (만료 밀리초, 상한) 순서로 넘긴다. 스크립트가 {@code ARGV[i * 2 - 1]}·{@code ARGV[i * 2]}로 읽는다. */
    private Object[] consumeArgs(List<MailSendBudgetProperties.Quota> windows) {
        List<String> args = new ArrayList<>(windows.size() * 2);
        for (MailSendBudgetProperties.Quota quota : windows) {
            args.add(Long.toString(quota.window().toMillis()));
            args.add(Long.toString(quota.limit()));
        }
        return args.toArray();
    }

    private static long count(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }
}
