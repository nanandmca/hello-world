package com.example.migration.scheduler;

import com.example.migration.config.MigrationProperties;
import com.example.migration.domain.ClaimResult;
import com.example.migration.service.ClaimService;
import com.example.migration.service.RunProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationScheduler {
    private final MigrationProperties properties;
    private final ClaimService claimService;
    private final RunProcessor runProcessor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${migration.scheduler-delay-ms:5000}")
    public void execute() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) return;
        try {
            ClaimResult claim = claimService.claimNextRun();
            if (claim.customerIds().isEmpty()) {
                log.debug("No pending customers");
                return;
            }
            log.info("Claimed runId={} workerId={} customers={}",
                    claim.runId(), claim.workerId(), claim.customerIds().size());
            var result = runProcessor.processAndWait(claim);
            log.info("Run completed {}", result);
        } catch (Exception e) {
            log.error("Scheduler execution failed", e);
        } finally {
            running.set(false);
        }
    }
}
