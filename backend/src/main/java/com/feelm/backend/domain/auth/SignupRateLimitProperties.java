package com.feelm.backend.domain.auth;

import com.feelm.backend.global.properties.WindowQuotas;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * 이메일 회원가입(API-001)의 IP 단위 요청 제한 값이다(S15P21E106-603, 601 결정 4).
 *
 * <p>메일 축({@link MailRateLimitProperties})과 <b>카운터도 설정도 나눈다</b>. 가입은 인증번호 전환 뒤로 메일을 보내지 않아 발송 쿼터를
 * 공유할 이유가 없고, 공유하면 가입 한 건이 같은 30건/1시간 예산을 발송과 가입으로 두 번 소모해 공유 NAT 한 버킷의 실질 가입 한도가 반감된다. 메일 축의 값은 공용
 * NAT 뒤 여러 사용자가 한 버킷을 쓰는 환경을 기준으로 정해진 값이라 그 손실이 그대로 드러난다.
 *
 * <p>이메일 단위 간격은 두지 않는다. 같은 이메일의 반복 가입은 인증 토큰이 1회용이라는 성질이 막는다.
 *
 * <p>값이 없으면 그 축은 제한하지 않는다(메일·로그인 축과 같은 방식). 운영 기본값은 jar에 담기는 {@code application.yml}에 있다. 창 중복 거부와
 * 오름차순 정규화, 창 길이 범위 검증은 {@link WindowQuotas}를 공유한다.
 *
 * @param ip 한 클라이언트 IP 버킷에 허용하는 창별 가입 요청 수
 */
@ConfigurationProperties(prefix = "feelm.auth.signup")
public record SignupRateLimitProperties(@DefaultValue List<Quota> ip) {

    public SignupRateLimitProperties {
        ip = WindowQuotas.normalize("feelm.auth.signup.ip", ip, Quota::window);
    }

    /**
     * 한 창에서 허용하는 가입 요청 수.
     *
     * @param limit 창 안에서 허용하는 요청 수
     * @param window 요청을 세는 기간. 단위를 생략하면 초로 읽는다
     */
    public record Quota(long limit, @DurationUnit(ChronoUnit.SECONDS) Duration window) {

        /** 한도가 0 이하이면 그 IP의 모든 가입이 막히고, 창이 범위를 벗어나면 카운터가 쌓이지 않거나 사실상 영구히 남는다. */
        public Quota {
            if (limit < 1) {
                throw new IllegalArgumentException(
                        "feelm.auth.signup.ip의 limit은 1 이상이어야 합니다. 현재 값: " + limit);
            }
            WindowQuotas.requireWindowInRange("feelm.auth.signup.ip의 window", window);
        }
    }
}
