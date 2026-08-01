package com.example.migration.util;

public final class SqlPlaceholders {
    private SqlPlaceholders() {}

    public static String forCount(int count) {
        if (count <= 0) throw new IllegalArgumentException("count must be positive");
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
