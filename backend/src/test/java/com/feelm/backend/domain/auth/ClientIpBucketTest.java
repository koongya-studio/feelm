package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IP 단위 제한이 예산을 나누는 버킷 표기를 확인한다(S15P21E106-288).
 *
 * <p>버킷 문자열 자체를 고정하는 이유는 이 값이 Redis 키의 해시 입력이기 때문이다. 표기가 흔들리면 같은 클라이언트가 창마다 다른 예산을 얻는다. 값이 아니라
 * 같음·다름만 보면 표기가 요청마다 달라지는 결함을 놓친다.
 */
@DisplayName("클라이언트 IP 집계 버킷 — S15P21E106-288")
class ClientIpBucketTest {

    /** 운영 기본값과 같은 길이(IPv4 /32, IPv6 /64). */
    private static final ClientIpProperties DEFAULT_PREFIXES = new ClientIpProperties(32, 64);

    @Test
    @DisplayName("IPv4는 주소 전체가 버킷이다")
    void ipv4KeepsFullAddress() {
        assertThat(ClientIpBucket.of("203.0.113.5", DEFAULT_PREFIXES)).isEqualTo("203.0.113.5/32");
    }

    @Test
    @DisplayName("같은 /64 안의 IPv6 주소들은 한 버킷을 공유한다")
    void ipv6AddressesInSamePrefixShareBucket() {
        String first = ClientIpBucket.of("2001:db8::1", DEFAULT_PREFIXES);
        String second = ClientIpBucket.of("2001:db8::abcd", DEFAULT_PREFIXES);
        String uncompressed =
                ClientIpBucket.of("2001:0db8:0000:0000:0000:0000:0000:0001", DEFAULT_PREFIXES);

        assertThat(first).isEqualTo("2001:db8::/64");
        assertThat(second).isEqualTo(first);
        assertThat(uncompressed).isEqualTo(first);
    }

    @Test
    @DisplayName("/64가 다른 IPv6 주소는 다른 버킷이다")
    void differentIpv6PrefixesGetDifferentBuckets() {
        String first = ClientIpBucket.of("2001:db8:0:1::1", DEFAULT_PREFIXES);
        String second = ClientIpBucket.of("2001:db8:0:2::1", DEFAULT_PREFIXES);

        assertThat(first).isEqualTo("2001:db8:0:1::/64");
        assertThat(second).isEqualTo("2001:db8:0:2::/64");
    }

    @Test
    @DisplayName("IPv4-mapped IPv6는 같은 IPv4 주소와 한 버킷이다")
    void ipv4MappedIpv6BecomesIpv4Bucket() {
        assertThat(ClientIpBucket.of("::ffff:203.0.113.5", DEFAULT_PREFIXES))
                .isEqualTo(ClientIpBucket.of("203.0.113.5", DEFAULT_PREFIXES));
    }

    /**
     * 값이 없거나 주소 리터럴이 아닌 값은 모두 한 버킷에 모인다(리뷰 A-3·B-6·B-7).
     *
     * <p>밸브는 전달 헤더의 항목이 IP인지 검증하지 않으므로 신뢰 경계 안에서 온 요청은 임의의 문자열을 이 자리에 넣을 수 있다. 원문을 버킷으로 쓰면 값을 조금씩
     * 바꾸는 것만으로 매번 새 예산이 생겨 IP 축이 사라지고 Redis 키 개수도 무한이 된다.
     */
    @Test
    @DisplayName("주소 리터럴이 아닌 값과 빈 값은 모두 한 버킷을 공유한다")
    void unparsableValuesShareOneBucket() {
        assertThat(ClientIpBucket.of("unknown", DEFAULT_PREFIXES))
                .isEqualTo(ClientIpBucket.UNKNOWN_BUCKET);

        assertThat(ClientIpBucket.of("203.0.113.999", DEFAULT_PREFIXES))
                .isEqualTo(ClientIpBucket.UNKNOWN_BUCKET);
        assertThat(ClientIpBucket.of("2001:db8:::1", DEFAULT_PREFIXES))
                .isEqualTo(ClientIpBucket.UNKNOWN_BUCKET);
        assertThat(ClientIpBucket.of("fe80::1%eth0", DEFAULT_PREFIXES))
                .isEqualTo(ClientIpBucket.UNKNOWN_BUCKET);
        assertThat(ClientIpBucket.of(null, DEFAULT_PREFIXES))
                .isEqualTo(ClientIpBucket.UNKNOWN_BUCKET);
        assertThat(ClientIpBucket.of("  ", DEFAULT_PREFIXES))
                .isEqualTo(ClientIpBucket.UNKNOWN_BUCKET);
    }

    /**
     * 버킷 문자열이 곧 예산 주체이므로 서로 다른 입력이 같은 주체로 접히면 안 된다(리뷰 A-4). 원문을 버킷으로 쓰던 구현에서는 프리픽스 표기를 그대로 보낸 값이 실제
     * 주소의 버킷과 같은 해시 입력을 만들었다.
     */
    @Test
    @DisplayName("프리픽스 표기를 그대로 보낸 값은 실제 주소의 버킷과 겹치지 않는다")
    void spoofedBucketNotationDoesNotCollideWithRealAddress() {
        assertThat(ClientIpBucket.of("203.0.113.5/32", DEFAULT_PREFIXES))
                .isNotEqualTo(ClientIpBucket.of("203.0.113.5", DEFAULT_PREFIXES));
        assertThat(ClientIpBucket.of("2001:db8::/64", DEFAULT_PREFIXES))
                .isNotEqualTo(ClientIpBucket.of("2001:db8::1", DEFAULT_PREFIXES));
    }

    /** 정규 버킷 표기에는 항상 {@code /}가 들어가므로 공용 버킷 이름과 겹칠 수 없다. */
    @Test
    @DisplayName("공용 버킷 이름은 정규 버킷 표기와 이름 공간이 겹치지 않는다")
    void unknownBucketHasNoPrefixNotation() {
        assertThat(ClientIpBucket.UNKNOWN_BUCKET).doesNotContain("/");
    }

    /** 설정한 길이가 실제로 적용되는지 본다. 길이를 무시하면 집계 단위를 설정에 둔 의미가 없다. */
    @Test
    @DisplayName("설정한 프리픽스 길이만큼만 남는다")
    void configuredPrefixLengthNarrowsBucket() {
        ClientIpProperties prefixes = new ClientIpProperties(24, 48);

        assertThat(ClientIpBucket.of("203.0.113.5", prefixes)).isEqualTo("203.0.113.0/24");
        assertThat(ClientIpBucket.of("2001:db8:1234:5678::1", prefixes))
                .isEqualTo("2001:db8:1234::/48");
    }

    @Test
    @DisplayName("모두 0인 IPv6도 압축 표기를 쓴다")
    void allZeroIpv6UsesCompressedForm() {
        assertThat(ClientIpBucket.of("::", DEFAULT_PREFIXES)).isEqualTo("::/64");
    }
}
