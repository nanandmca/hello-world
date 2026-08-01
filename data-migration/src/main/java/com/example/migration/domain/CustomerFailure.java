package com.example.migration.domain;

public record CustomerFailure(long customerId, String errorCode, String errorMessage) {
    public static CustomerFailure from(long customerId, Throwable t) {
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return new CustomerFailure(customerId, t.getClass().getSimpleName(), message);
    }
}
