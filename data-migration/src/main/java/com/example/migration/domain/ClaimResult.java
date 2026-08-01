package com.example.migration.domain;

import java.util.List;

public record ClaimResult(String runId, String workerId, List<Long> customerIds) {
}
