package com.example.migration.util;

import java.util.ArrayList;
import java.util.List;

public final class Partitions {
    private Partitions() {}

    public static <T> List<List<T>> of(List<T> source, int size) {
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            result.add(List.copyOf(source.subList(i, Math.min(i + size, source.size()))));
        }
        return result;
    }
}
