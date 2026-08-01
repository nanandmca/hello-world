package com.example.migration.service;

import com.example.migration.domain.ClaimResult;
import com.example.migration.domain.RunResult;
import com.example.migration.repository.StageRepository;
import com.example.migration.config.MigrationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManualMigrationService {
    private final ClaimService claimService;
    private final RunProcessor runProcessor;
    private final StageRepository stageRepository;
    private final MigrationProperties properties;

    public RunResult processLimit(int limit) {
        ClaimResult claim = claimService.claimNextRun(limit);
        if (claim.customerIds().isEmpty()) return new RunResult(claim.runId(), 0, 0, 0, 0, 0, 0);
        return runProcessor.processAndWait(claim);
    }

    public int retrigger(String runId, boolean includeFailed, boolean force) {
        var activity = stageRepository.runActivity(runId);
        if (!force && activity != null && activity.inProgressRows() > 0 && activity.lastHeartbeatAt() != null &&
                activity.lastHeartbeatAt().isAfter(java.time.LocalDateTime.now().minusMinutes(properties.getStaleTimeoutMinutes()))) {
            throw new IllegalStateException("Run is still active; use force=true only after operational confirmation");
        }
        return stageRepository.resetRunForRetry(runId, includeFailed);
    }
}
