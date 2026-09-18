package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.dto.constant.MailProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Fixes applied:
 *  1. Retry up to 3 attempts with 2-second back-off before giving up.
 *     Handles transient SMTP timeouts (421 4.4.2) without losing the notification silently.
 *  2. Each attempt creates a fresh MimeMessage — reusing a failed message object
 *     can cause a second send to fail immediately.
 */
@Service
@Slf4j
public class ScheduleMailService {

    private static final int    MAX_ATTEMPTS  = 3;
    private static final long   RETRY_DELAY_MS = 2000L;

    private final JavaMailSender  javaMailSender;
    private final MailProperties  mailProperties;

    public ScheduleMailService(JavaMailSender javaMailSender, MailProperties mailProperties) {
        this.javaMailSender = javaMailSender;
        this.mailProperties = mailProperties;
    }

    public boolean sendScheduleEmails(String subject, String body) {

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // FIX: create a fresh MimeMessage on every attempt
                MimeMessage mail         = javaMailSender.createMimeMessage();
                MimeMessageHelper helper =
                        new MimeMessageHelper(mail, false, "UTF-8");

                helper.setFrom(new InternetAddress(
                        mailProperties.fromEmail(),
                        mailProperties.fromName()));
                helper.setTo(mailProperties.receiverEmails().toArray(String[]::new));
                helper.setSubject(subject);
                helper.setText(buildEmailBody(body), true);
                //    helper.setText("test");

                javaMailSender.send(mail);

                log.info("[ScheduleMailService] Email sent — subject={} to={} attempt={}",
                        subject, mailProperties.receiverEmails(), attempt);
                return true;

            } catch (Exception ex) {
                lastException = ex;
                log.warn("[ScheduleMailService] Attempt {}/{} failed — subject={} reason={}",
                        attempt, MAX_ATTEMPTS, subject, ex.getMessage());

                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("[ScheduleMailService] Retry interrupted — subject={}", subject);
                        return false;
                    }
                }
            }
        }

        log.error("[ScheduleMailService] All {} attempts failed — subject={} finalReason={}",
                MAX_ATTEMPTS, subject, lastException != null ? lastException.getMessage() : "unknown",
                lastException);
        return false;
    }

    private String buildEmailBody(String body) {
        return """
        <html>
        <body>
            <p>Dear User,</p>
            <p>%s</p>
            <p>Thank You.<br/>KTMS System.</p>
        </body>
        </html>
        """.formatted(body);

    }
}