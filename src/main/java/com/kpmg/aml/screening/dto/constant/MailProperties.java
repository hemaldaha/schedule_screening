package com.kpmg.aml.screening.dto.constant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
        String fromEmail,
        String fromName,
        List<String> receiverEmails
) {}