package com.example.migration.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@RequiredArgsConstructor
public class ExecutorConfig {
    private final MigrationProperties properties;

    @Bean(destroyMethod = "shutdown", name = "migrationExecutor")
    public ExecutorService migrationExecutor() {
        return Executors.newFixedThreadPool(properties.getAsyncThreads(), Thread.ofPlatform()
                .name("migration-worker-", 0).factory());
    }
}
