package io.github.joeyblanks.ecommerce.notification.support;

import io.github.joeyblanks.ecommerce.notification.mail.EmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestMailConfig {

    @Bean
    @Primary
    ControlledEmailSender controlledEmailSender(EmailSender real) {
        return new ControlledEmailSender(real);
    }
}
