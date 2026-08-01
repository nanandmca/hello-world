package com.example.migration.service;

import com.example.migration.config.MigrationProperties;
import com.example.migration.domain.ClaimResult;
import com.example.migration.repository.StageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ClaimService {
    private final StageRepository stageRepository;
    private final WorkerIdentityService identityService;
    private final MigrationProperties properties;

    public ClaimResult claimNextRun() {
        return claimNextRun(properties.getMaxCustomersPerRun());
    }

    public ClaimResult claimNextRun(int requestedLimit) {
        String runId = identityService.newRunId();
        String workerId = identityService.workerId();

        List<Long> candidates = stageRepository.findCandidateCustomers(
                Math.min(requestedLimit, properties.getMaxCustomersPerRun()));
        if (candidates.isEmpty()) return new ClaimResult(runId, workerId, List.of());

        stageRepository.claimCandidates(runId, workerId, candidates);

        List<Long> actuallyClaimed = stageRepository.findClaimedCustomers(
                runId, workerId, Math.min(requestedLimit, properties.getMaxCustomersPerRun()));
        return new ClaimResult(runId, workerId, actuallyClaimed);
    }
}
