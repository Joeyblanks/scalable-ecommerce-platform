package io.github.joeyblanks.ecommerce.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.client.RestClient;

/** Which sender is used is decided only by the environment (EMAIL_PROVIDER, SENDGRID_API_KEY), and it is exactly one. */
class EmailSenderSelectionTest {

    private static final String KEY = SendGridKeysTest.KEY;

    @EnableConfigurationProperties(AppProperties.SendGrid.class)
    @Import({SendGridEmailSender.class, SmtpEmailSender.class})
    static class Cfg {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Cfg.class)
            .withBean(JavaMailSenderImpl.class, JavaMailSenderImpl::new)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withPropertyValues("app.sendgrid.api-base=https://api.sendgrid.com");

    private static String only(org.springframework.context.ApplicationContext ctx) {
        return ctx.getBean(EmailSender.class).name(); // fails if there are zero or several
    }

    @Test
    void smtpByDefault() {
        runner.run(ctx -> assertThat(only(ctx)).isEqualTo("smtp"));
        runner.withPropertyValues("app.sendgrid.api-key=").run(ctx -> assertThat(only(ctx)).isEqualTo("smtp"));
    }

    @Test
    void aKeySelectsSendGrid() {
        runner.withPropertyValues("app.sendgrid.api-key=" + KEY).run(ctx -> assertThat(only(ctx)).isEqualTo("sendgrid"));
    }

    @Test
    void smtpCanBeForcedEvenWhenAKeyIsSet() {
        runner.withPropertyValues("app.sendgrid.api-key=" + KEY, "app.email.provider=smtp")
                .run(ctx -> assertThat(only(ctx)).isEqualTo("smtp"));
    }

    @Test
    void forcingSendGridWithoutAKeyStopsStartup() {
        runner.withPropertyValues("app.email.provider=sendgrid").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void aBadKeyStopsStartupWithoutEchoingIt() {
        runner.withPropertyValues("app.sendgrid.api-key=SECRETVALUE-not-a-sendgrid-key-123").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure().toString()).doesNotContain("SECRETVALUE");
        });
    }
}
