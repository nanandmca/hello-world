package com.example.migration.scheduler;

import com.example.migration.config.MigrationProperties;
import com.example.migration.repository.StageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StaleRecoveryScheduler {
    private final StageRepository stageRepository;
    private final MigrationProperties properties;

    @Scheduled(fixedDelayString = "${migration.recovery-delay-ms:300000}")
    public void recover() {
        if (!properties.isEnabled()) return;
        int updated = stageRepository.recoverStale(properties.getStaleTimeoutMinutes());
        if (updated > 0) log.warn("Recovered stale staging rows={}", updated);
    }
}
