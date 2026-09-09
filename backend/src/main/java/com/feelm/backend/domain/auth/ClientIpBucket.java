package com.feelm.backend.domain.auth;

import java.util.Arrays;

/**
 * 클라이언트 IP를 제한 예산을 공유하는 버킷 표기로 바꾼다(S15P21E106-288).
 *
 * <p>결과는 {@code 203.0.113.5/32}, {@code 2001:db8::/64}처럼 프리픽스 표기 문자열이고, 같은 버킷의 주소는 같은 문자열을 얻는다.
 * {@link AuthRateLimiter}가 이 문자열을 해시해 Redis 키로 쓰므로 IP 원문은 여전히 남지 않는다. 길이는 {@link
 * ClientIpProperties}에서 온다.
 *
 * <p>주소는 <b>직접 파싱</b>한다. {@link java.net.InetAddress#getByName}은 리터럴이 아닌 값에 이름 조회를 시도할 수 있어, 헤더에서 온
 * 값을 그대로 넘기면 외부가 우리 서버의 DNS 질의를 유발할 수 있다.
 *
 * <p><b>값이 없거나 주소 리터럴이 아니면 모두 {@code unknown} 한 버킷에 모은다</b>(리뷰 A-3·A-4·B-6·B-7). 서블릿 컨테이너의 밸브는 전달
 * 헤더의 항목이 IP인지 검증하지 않으므로, 신뢰 경계 안에서 온 요청은 임의의 문자열을 클라이언트 IP 자리에 넣을 수 있다. 그 값을 그대로 버킷으로 쓰면 값을 조금씩
 * 바꾸는 것만으로 매번 새 예산이 생기고 Redis 키 개수도 무한이 된다. 세 선택지(원문 유지 · 한 버킷 · 요청 거절) 중 원문 유지가 가장 느슨하다. 한 버킷에 모으면
 * 그 요청들이 예산을 공유해 fail-closed 방향이 되고, {@code /}가 없는 표기라 정규 버킷과 이름 공간이 겹치지도 않는다. 원문을 쓰던 구현에서는 {@code
 * X-Real-IP: 203.0.113.5/32} 같은 값이 실제 주소의 버킷과 같은 해시 입력을 만들었다.
 *
 * <p>IPv4-mapped IPv6({@code ::ffff:203.0.113.5})는 IPv4 주소로 되돌린 뒤 IPv4 길이를 적용한다. 같은 클라이언트가 서블릿 컨테이너
 * 설정이나 프록시 표기에 따라 두 형식으로 보일 수 있어, 형식이 예산을 나누면 한 클라이언트가 예산을 두 벌 얻는다.
 */
final class ClientIpBucket {

    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;
    private static final int IPV6_GROUPS = 8;
    private static final int IPV6_GROUP_DIGITS = 4;
    private static final int IPV4_OCTET_DIGITS = 3;
    private static final int IPV4_OCTET_MAXIMUM = 255;

    /** 값이 없거나 주소 리터럴이 아닌 요청이 공유하는 버킷. 정규 버킷 표기에는 항상 {@code /}가 들어가므로 이 이름과 겹치지 않는다. */
    static final String UNKNOWN_BUCKET = "unknown";

    /** IPv4-mapped IPv6의 앞 12바이트. 뒤 4바이트가 IPv4 주소다. */
    private static final byte[] IPV4_MAPPED_PREFIX = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF
    };

    private ClientIpBucket() {}

    /**
     * 클라이언트 IP가 속한 버킷 표기를 돌려준다.
     *
     * <p>값이 없거나 주소 리터럴이 아니면 {@link #UNKNOWN_BUCKET}을 돌려준다. 발신지를 알 수 없는 요청은 모두 그 한 버킷의 예산을 나눠 쓴다.
     */
    static String of(String clientIp, ClientIpProperties prefixes) {
        if (clientIp == null || clientIp.isBlank()) {
            return UNKNOWN_BUCKET;
        }
        byte[] address = parse(clientIp);
        if (address == null) {
            return UNKNOWN_BUCKET;
        }
        int prefixLength =
                address.length == IPV4_BYTES
                        ? prefixes.ipv4PrefixLength()
                        : prefixes.ipv6PrefixLength();
        mask(address, prefixLength);
        return address.length == IPV4_BYTES
                ? formatIpv4(address, prefixLength)
                : formatIpv6(address, prefixLength);
    }

    /** 리터럴이면 주소 바이트를, 아니면 {@code null}을 돌려준다. */
    private static byte[] parse(String value) {
        if (value.indexOf(':') < 0) {
            return parseIpv4(value);
        }
        byte[] address = parseIpv6(value);
        if (address == null) {
            return null;
        }
        return isIpv4Mapped(address)
                ? Arrays.copyOfRange(address, IPV6_BYTES - IPV4_BYTES, IPV6_BYTES)
                : address;
    }

    private static byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != IPV4_BYTES) {
            return null;
        }
        byte[] address = new byte[IPV4_BYTES];
        for (int index = 0; index < IPV4_BYTES; index++) {
            String part = parts[index];
            if (part.isEmpty() || part.length() > IPV4_OCTET_DIGITS) {
                return null;
            }
            int octet = 0;
            for (int position = 0; position < part.length(); position++) {
                int digit = decimalDigit(part.charAt(position));
                if (digit < 0) {
                    return null;
                }
                octet = octet * 10 + digit;
            }
            if (octet > IPV4_OCTET_MAXIMUM) {
                return null;
            }
            address[index] = (byte) octet;
        }
        return address;
    }

    /**
     * IPv6 리터럴을 16바이트로 바꾼다. {@code ::}는 한 번만 허용하고 zone id({@code %eth0})가 붙은 값은 받지 않는다.
     *
     * <p>zone id는 링크 로컬 주소에만 붙고 그런 주소는 프록시를 지나 온 클라이언트 IP일 수 없다. 받지 않으면 {@link #UNKNOWN_BUCKET}으로
     * 모이므로, 형식을 넓히는 대신 그 처리에 맡긴다.
     */
    private static byte[] parseIpv6(String value) {
        if (value.indexOf('%') >= 0) {
            return null;
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 1) >= 0) {
            return null;
        }
        byte[] head = parseGroups(compression < 0 ? value : value.substring(0, compression));
        byte[] tail = compression < 0 ? new byte[0] : parseGroups(value.substring(compression + 2));
        if (head == null || tail == null) {
            return null;
        }
        int length = head.length + tail.length;
        // 압축이 없으면 8그룹이 모두 있어야 하고, 있으면 최소 한 그룹을 대신해야 한다.
        if (compression < 0 ? length != IPV6_BYTES : length > IPV6_BYTES - 2) {
            return null;
        }
        byte[] address = new byte[IPV6_BYTES];
        System.arraycopy(head, 0, address, 0, head.length);
        System.arraycopy(tail, 0, address, IPV6_BYTES - tail.length, tail.length);
        return address;
    }

    /** {@code ::}로 나눈 한쪽의 그룹들을 바이트로 바꾼다. 빈 문자열은 그룹이 없는 것으로 본다. */
    private static byte[] parseGroups(String groups) {
        if (groups.isEmpty()) {
            return new byte[0];
        }
        String[] parts = groups.split(":", -1);
        byte[] bytes = new byte[IPV6_BYTES];
        int length = 0;
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.indexOf('.') >= 0) {
                // IPv4 표기는 마지막 그룹에만 올 수 있다(::ffff:203.0.113.5).
                byte[] embedded = parseIpv4(part);
                boolean placeable =
                        index == parts.length - 1
                                && embedded != null
                                && length + IPV4_BYTES <= IPV6_BYTES;
                if (!placeable) {
                    return null;
                }
                System.arraycopy(embedded, 0, bytes, length, IPV4_BYTES);
                return Arrays.copyOf(bytes, length + IPV4_BYTES);
            }
            if (part.isEmpty() || part.length() > IPV6_GROUP_DIGITS || length + 2 > IPV6_BYTES) {
                return null;
            }
            int group = 0;
            for (int position = 0; position < part.length(); position++) {
                int digit = hexDigit(part.charAt(position));
                if (digit < 0) {
                    return null;
                }
                group = group * 16 + digit;
            }
            bytes[length++] = (byte) (group >> 8);
            bytes[length++] = (byte) group;
        }
        return Arrays.copyOf(bytes, length);
    }

    /** 프리픽스 밖의 비트를 0으로 만든다. */
    private static void mask(byte[] address, int prefixLength) {
        for (int index = 0; index < address.length; index++) {
            int keptBits = Math.clamp(prefixLength - index * Byte.SIZE, 0, Byte.SIZE);
            address[index] = (byte) (address[index] & (0xFF << (Byte.SIZE - keptBits)));
        }
    }

    private static boolean isIpv4Mapped(byte[] address) {
        return Arrays.equals(
                address,
                0,
                IPV4_MAPPED_PREFIX.length,
                IPV4_MAPPED_PREFIX,
                0,
                IPV4_MAPPED_PREFIX.length);
    }

    private static String formatIpv4(byte[] address, int prefixLength) {
        StringBuilder text = new StringBuilder();
        for (byte octet : address) {
            if (!text.isEmpty()) {
                text.append('.');
            }
            text.append(Byte.toUnsignedInt(octet));
        }
        return text.append('/').append(prefixLength).toString();
    }

    /**
     * IPv6 주소를 RFC 5952의 압축 표기로 적는다. 가장 긴 0 구간(같은 길이면 앞쪽)을 {@code ::}로 줄이고, 한 그룹만 0인 구간은 줄이지 않는다.
     */
    private static String formatIpv6(byte[] address, int prefixLength) {
        int[] groups = new int[IPV6_GROUPS];
        for (int index = 0; index < IPV6_GROUPS; index++) {
            groups[index] =
                    (Byte.toUnsignedInt(address[index * 2]) << 8)
                            | Byte.toUnsignedInt(address[index * 2 + 1]);
        }
        int zeroRunStart = -1;
        int zeroRunLength = 0;
        int currentStart = -1;
        int currentLength = 0;
        for (int index = 0; index < IPV6_GROUPS; index++) {
            if (groups[index] != 0) {
                currentStart = -1;
                currentLength = 0;
                continue;
            }
            if (currentStart < 0) {
                currentStart = index;
            }
            currentLength++;
            if (currentLength > zeroRunLength) {
                zeroRunStart = currentStart;
                zeroRunLength = currentLength;
            }
        }
        if (zeroRunLength < 2) {
            zeroRunStart = -1;
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < IPV6_GROUPS; index++) {
            if (zeroRunStart >= 0
                    && index >= zeroRunStart
                    && index < zeroRunStart + zeroRunLength) {
                if (index == zeroRunStart) {
                    text.append("::");
                }
                continue;
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[index]));
        }
        return text.append('/').append(prefixLength).toString();
    }

    private static int decimalDigit(char character) {
        return character >= '0' && character <= '9' ? character - '0' : -1;
    }

    private static int hexDigit(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }
}
