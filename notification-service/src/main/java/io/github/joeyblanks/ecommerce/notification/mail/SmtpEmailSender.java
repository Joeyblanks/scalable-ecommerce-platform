package io.github.joeyblanks.ecommerce.notification.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeBodyPart;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Default sender: SMTP (a local Mailpit in Docker Compose, so emails are visible in a browser). Each email is
 * multipart/alternative (plain text + HTML) with a stable Message-ID.
 */
@Component
// Exactly one sender is active: this one unless SendGrid is selected (see SendGridEmailSender).
@ConditionalOnExpression("!('${app.email.provider:auto}' == 'sendgrid' or "
        + "('${app.email.provider:auto}' == 'auto' and !'${app.sendgrid.api-key:}'.isBlank()))")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSenderImpl mail;

    public SmtpEmailSender(JavaMailSenderImpl mail) {
        this.mail = mail;
        log.info("Using the SMTP email sender ({}:{})", mail.getHost(), mail.getPort());
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public void send(Email email) {
        try {
            String messageId = email.messageId();
            MimeMessage message = new MimeMessage(mail.getSession()) {
                @Override
                protected void updateMessageID() throws MessagingException {
                    setHeader("Message-ID", messageId); // keep ours instead of a random one
                }
            };
            message.setFrom(new InternetAddress(email.from()));
            message.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(email.to(), true));
            message.setSubject(email.subject(), StandardCharsets.UTF_8.name());
            MimeBodyPart text = new MimeBodyPart();
            text.setText(email.text(), StandardCharsets.UTF_8.name());
            MimeBodyPart html = new MimeBodyPart();
            html.setContent(email.html(), "text/html; charset=UTF-8");
            MimeMultipart alternative = new MimeMultipart("alternative", text, html);
            message.setContent(alternative);
            mail.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Could not build or send the email: " + e.getMessage(), e);
        }
    }
}
