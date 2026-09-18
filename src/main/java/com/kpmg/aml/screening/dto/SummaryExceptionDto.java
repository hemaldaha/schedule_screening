package com.kpmg.aml.screening.dto;

import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import java.time.LocalDateTime;

/**
 * Lightweight view of a {@code screening_exceptions} row for list / scanning use cases.
 * Returned by {@code GET /api/screening/exceptions?startDate=&endDate=}.
 */
public record SummaryExceptionDto(
        String        id,
        String        clientId,
        String        action,
        String        reasonCode,
        LocalDateTime createdAt
) {
    public static SummaryExceptionDto from(ScreeningExceptionEntity e) {
        return new SummaryExceptionDto(
                e.getId(),
                e.getClientId(),
                e.getAction(),
                e.getReasonCode(),
                e.getCreatedAt()
        );
    }
}
