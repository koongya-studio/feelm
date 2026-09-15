package com.feelm.backend.infrastructure.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 계정 메일(가입 인증, 비밀번호 재설정) 발송 구현체를 {@code feelm.mail.mode}로 고른다. 두 메일은 항상 같은 모드로 함께 뜬다.
 *
 * <p>모드 분기를 한 곳에 모아 두어 어떤 환경에서 어떤 구현이 뜨는지 설정만 보고 판단할 수 있게 했다. {@code smtp} 모드는 {@link
 * JavaMailSender}를 필요로 하므로 {@code spring.mail.*}이 설정되지 않은 환경에서는 기동 시점에 실패한다. 잘못된 설정으로 메일이 조용히 나가지
 * 않는 것보다 낫다.
 *
 * <p>메일 종류마다 빈을 두지 않는다. 발송 구현이 종류를 파라미터로 받으므로 모드별로 하나씩만 있으면 된다(S15P21E106-284). 종류가 늘어도 이 설정은 그대로다.
 */
@Configuration
@EnableConfigurationProperties(AuthMailProperties.class)
class AuthMailConfig {

    @Bean
    AuthMailModeGuard authMailModeGuard(Environment environment, AuthMailProperties properties) {
        return new AuthMailModeGuard(environment, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "feelm.mail.mode", havingValue = "smtp")
    AuthMailSender smtpAuthMailSender(JavaMailSender mailSender, AuthMailProperties properties) {
        return new SmtpAuthMailSender(mailSender, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "feelm.mail.mode", havingValue = "log")
    AuthMailSender loggingAuthMailSender(AuthMailProperties properties) {
        return new LoggingAuthMailSender(properties);
    }
}
