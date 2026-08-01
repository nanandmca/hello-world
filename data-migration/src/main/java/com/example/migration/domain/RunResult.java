package com.example.migration.domain;

public record RunResult(String runId, int claimed, int migrated, int alreadyMigrated, int failed, int failedChunks, long elapsedMs) {}
