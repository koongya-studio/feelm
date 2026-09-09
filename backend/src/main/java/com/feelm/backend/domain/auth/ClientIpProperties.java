package com.feelm.backend.domain.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * IP 단위 제한이 예산을 나누는 집계 단위다(S15P21E106-288).
 *
 * <p>접두사는 {@code feelm.auth.client-ip}다. 제한 값이 이미 설정에 있는데({@link MailRateLimitProperties}, {@link
 * LoginRateLimitProperties}) 집계 단위만 코드 상수로 남으면 같은 성격의 정책이 설정과 코드로 갈려 조정 지점이 둘이 된다(S15P21E106-311과
 * 같은 이유). 값을 읽는 쪽이 {@link AuthRateLimiter} 하나뿐이라 record는 도메인에 둔다.
 *
 * <p>IPv6 기본값이 주소 단위(/128)가 아니라 {@code /64}인 이유는 종단 사용자가 보통 {@code /64} 이상을 배정받기 때문이다. 주소 단위로 세면 같은
 * 회선에서 주소만 바꿔 예산을 무한히 새로 얻을 수 있어 IP 축이 사실상 사라진다. {@code /64}는 종단 서브넷의 표준 최소 단위라 정상 사용자를 과도하게 묶지 않는
 * 선에서 이 우회를 막는다.
 *
 * <p>IPv4 기본값은 주소 단위({@code /32})다. NAT 뒤 공용 주소는 이미 여러 사용자가 한 예산을 나눠 쓰므로 더 넓히면 정상 사용자가 함께 걸리는 범위만
 * 커진다. 공유 예산이 좁아 생기는 문제는 집계 단위가 아니라 한도 값에서 다룬다(S15P21E106-287).
 *
 * <p><b>허용 범위를 대역 전체가 아니라 결정 근거의 폭으로 둔다</b>(리뷰 B-4). 값은 검증을 통과하면 기동하고, 잘못된 값의 결과는 런타임 장애가 아니라
 * <b>제한의 조용한 변형</b>이라 운영 중에 드러나지 않는다. 대역 전체를 허용하면 ADR-0019가 기각한 값이 그대로 통과한다.
 *
 * <ul>
 *   <li>IPv6 {@code /128}은 기각한 대안 E와 같은 상태다. 주소 회전 우회가 되살아나는데 기동은 성공한다. 그래서 상한을 {@code /64}로 둔다.
 *   <li>IPv6 {@code /48}보다 넓은 값은 다른 종단 사이트에 배정된 주소까지 한 예산에 묶는다. 하한을 {@code /48}로 둔다.
 *   <li>IPv4 {@code /1}은 주소 공간 절반을 한 예산에 넣어 대량 429를 만든다. 하한을 {@code /24}로 두고 상한은 주소 단위인 {@code
 *       /32}다.
 * </ul>
 *
 * @param ipv4PrefixLength IPv4 주소를 묶는 프리픽스 비트 수. {@code 24}~{@code 32}
 * @param ipv6PrefixLength IPv6 주소를 묶는 프리픽스 비트 수. {@code 48}~{@code 64}
 */
@ConfigurationProperties(prefix = "feelm.auth.client-ip")
public record ClientIpProperties(
        @DefaultValue("32") int ipv4PrefixLength, @DefaultValue("64") int ipv6PrefixLength) {

    static final int IPV4_MINIMUM_PREFIX_LENGTH = 24;
    static final int IPV4_MAXIMUM_PREFIX_LENGTH = 32;
    static final int IPV6_MINIMUM_PREFIX_LENGTH = 48;
    static final int IPV6_MAXIMUM_PREFIX_LENGTH = 64;

    public ClientIpProperties {
        validate(
                "ipv4-prefix-length",
                ipv4PrefixLength,
                IPV4_MINIMUM_PREFIX_LENGTH,
                IPV4_MAXIMUM_PREFIX_LENGTH);
        validate(
                "ipv6-prefix-length",
                ipv6PrefixLength,
                IPV6_MINIMUM_PREFIX_LENGTH,
                IPV6_MAXIMUM_PREFIX_LENGTH);
    }

    private static void validate(String key, int prefixLength, int minimum, int maximum) {
        if (prefixLength < minimum || prefixLength > maximum) {
            throw new IllegalArgumentException(
                    "feelm.auth.client-ip.%s는 %d 이상 %d 이하여야 합니다. 현재 값: %d"
                            .formatted(key, minimum, maximum, prefixLength));
        }
    }
}
