package com.example.migration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {
    private boolean enabled = true;
    private long schedulerDelayMs = 5000;
    private int maxCustomersPerRun = 100000;
    private int candidateFetchSize = 120000;
    private int customerChunkSize = 500;
    private int claimJdbcBatchSize = 200;
    private int statusUpdateChunkSize = 500;
    private int mariaUpsertBatchSize = 500;
    private int asyncThreads = 2;
    private int executorQueueCapacity = 20;
    private int maxRetries = 3;
    private int heartbeatIntervalSeconds = 60;
    private int staleTimeoutMinutes = 30;
    private boolean skipExistingCustomers = false;
    private String workerPrefix = "DCE";
}
