package com.example.migration.domain;

public record ChunkResult(int migrated, int alreadyMigrated, int failed) {
    public static ChunkResult empty() {
        return new ChunkResult(0, 0, 0);
    }
}
