package io.github.joeyblanks.ecommerce.notification.support;

import io.github.joeyblanks.ecommerce.notification.mail.SmtpEmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestMailConfig {

    @Bean
    @Primary
    ControlledEmailSender controlledEmailSender(SmtpEmailSender smtp) {
        return new ControlledEmailSender(smtp);
    }
}
