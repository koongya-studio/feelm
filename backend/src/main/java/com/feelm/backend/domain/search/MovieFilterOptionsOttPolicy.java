package com.feelm.backend.domain.search;

import java.util.List;

/** API-029에서 확정한 지원 OTT 5종과 표시 순서. DB의 ID·이름은 별도로 조회한다. */
public final class MovieFilterOptionsOttPolicy {

    private static final List<String> SUPPORTED_CODES =
            List.of("NETFLIX", "DISNEY_PLUS", "WATCHA", "WAVVE", "TVING");

    private MovieFilterOptionsOttPolicy() {}

    public static List<String> supportedCodes() {
        return SUPPORTED_CODES;
    }

    public static int displayOrder(String code) {
        int index = SUPPORTED_CODES.indexOf(code);
        return index < 0 ? SUPPORTED_CODES.size() : index;
    }
}
