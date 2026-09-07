package com.feelm.backend.infrastructure.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 인증 메일 발송 구현체를 {@code feelm.mail.mode}로 고른다.
 *
 * <p>모드 분기를 한 곳에 모아 두어 어떤 환경에서 어떤 구현이 뜨는지 설정만 보고 판단할 수 있게 했다. {@code smtp} 모드는 {@link
 * JavaMailSender}를 필요로 하므로 {@code spring.mail.*}이 설정되지 않은 환경에서는 기동 시점에 실패한다. 잘못된 설정으로 메일이 조용히 나가지
 * 않는 것보다 낫다.
 */
@Configuration
@EnableConfigurationProperties(VerificationMailProperties.class)
class VerificationMailConfig {

    @Bean
    VerificationMailModeGuard verificationMailModeGuard(
            Environment environment, VerificationMailProperties properties) {
        return new VerificationMailModeGuard(environment, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "feelm.mail.mode", havingValue = "smtp")
    VerificationMailSender smtpVerificationMailSender(
            JavaMailSender mailSender, VerificationMailProperties properties) {
        return new SmtpVerificationMailSender(mailSender, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "feelm.mail.mode", havingValue = "log")
    VerificationMailSender loggingVerificationMailSender(VerificationMailProperties properties) {
        return new LoggingVerificationMailSender(properties);
    }
}
