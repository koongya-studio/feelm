package com.feelm.backend.infrastructure.mail;

import com.feelm.backend.infrastructure.mail.VerificationMailProperties.Mode;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/**
 * {@code log} 모드가 로컬·테스트 밖에서 켜지지 않도록 기동 시점에 검사한다(플랜 결정 13).
 *
 * <p>{@code log} 모드는 인증 링크를 로그에 남기므로 dev·운영에서 켜지면 두 가지가 동시에 문제가 된다. 인증 메일이 실제로 나가지 않고, 컨벤션 12장이 금지한
 * 인증 토큰이 로그에 남는다. 잘못된 설정을 런타임까지 끌고 가지 않도록 기동을 실패시킨다.
 *
 * <p>판정은 프로파일 <b>허용</b> 목록으로 한다. 허용 목록을 쓰면 새 프로파일이 추가됐을 때 기본이 "차단"이 되어 안전하다. 활성 프로파일이 없는 실행에서는
 * Spring이 보고하는 기본 프로파일을 기준으로 삼는데, 테스트는 활성 프로파일 없이 도는 경우가 있어 이때 기본 프로파일 이름인 {@code default}도 허용 대상에
 * 넣는다.
 */
class VerificationMailModeGuard {

    /** {@code log} 모드를 허용하는 프로파일. {@code default}는 활성 프로파일을 지정하지 않은 실행(테스트 포함)을 뜻한다. */
    static final Set<String> LOG_MODE_PROFILES = Set.of("default", "local", "test");

    VerificationMailModeGuard(Environment environment, VerificationMailProperties properties) {
        verify(properties.mode(), effectiveProfiles(environment));
    }

    static void verify(Mode mode, Collection<String> effectiveProfiles) {
        if (mode == null) {
            throw new IllegalStateException(
                    "feelm.mail.mode(MAIL_MODE)가 설정되지 않았습니다. 로컬·테스트는 log, dev·운영은 smtp를 지정해야 합니다.");
        }
        if (mode != Mode.LOG) {
            return;
        }
        List<String> unsupported =
                effectiveProfiles.stream()
                        .filter(profile -> !LOG_MODE_PROFILES.contains(profile))
                        .sorted()
                        .toList();
        if (!unsupported.isEmpty()) {
            throw new IllegalStateException(
                    "feelm.mail.mode=log는 로컬·테스트 전용입니다. 프로파일 "
                            + unsupported
                            + "에서는 MAIL_MODE=smtp로 설정해야 합니다.");
        }
    }

    /** 활성 프로파일이 지정되지 않았으면 기본 프로파일이 실제로 적용되므로 그것을 판정 대상으로 삼는다. */
    static Set<String> effectiveProfiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        String[] effective = active.length > 0 ? active : environment.getDefaultProfiles();
        return Arrays.stream(effective).collect(Collectors.toUnmodifiableSet());
    }
}
