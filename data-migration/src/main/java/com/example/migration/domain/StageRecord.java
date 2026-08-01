package com.example.migration.domain;

import java.time.LocalDateTime;

public record StageRecord(
        long stageId,
        long customerId,
        String personalisationType,
        String personalisationKey,
        String personalisationValue,
        LocalDateTime sourceUpdatedAt) {
}
