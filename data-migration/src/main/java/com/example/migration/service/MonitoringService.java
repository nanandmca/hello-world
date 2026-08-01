package com.example.migration.service;

import com.example.migration.repository.MariaRepository;
import com.example.migration.repository.StageRepository;
import com.example.migration.config.MigrationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MonitoringService {
    private final StageRepository stageRepository;
    private final MariaRepository mariaRepository;
    private final MigrationProperties properties;

    public BatchMonitor monitor(String runId) {
        var counts = stageRepository.statusCounts(runId);
        long totalCustomers = counts.stream().mapToLong(StageRepository.RunStatusCount::customerCount).sum();
        long totalRows = counts.stream().mapToLong(StageRepository.RunStatusCount::rowCount).sum();
        var activity = stageRepository.runActivity(runId);
        boolean hasInProgress = activity != null && activity.inProgressRows() > 0;
        boolean stale = hasInProgress && (activity.lastHeartbeatAt() == null ||
                activity.lastHeartbeatAt().isBefore(java.time.LocalDateTime.now().minusMinutes(properties.getStaleTimeoutMinutes())));
        return new BatchMonitor(runId, totalCustomers, totalRows, hasInProgress, stale, activity, counts);
    }

    public List<StageRepository.RunSummary> recentRuns(int limit) { return stageRepository.recentRuns(limit); }

    public VerificationResult verify(int offset, int limit) {
        List<Long> ids = stageRepository.findCustomersByOffset(offset, limit);
        Map<Long, Long> source = stageRepository.sourceCounts(ids).stream()
                .collect(Collectors.toMap(StageRepository.SourceCustomerCount::customerId,
                        StageRepository.SourceCustomerCount::rowCount));
        Map<Long, Long> target = mariaRepository.targetCounts(ids);
        List<CustomerVerification> details = new ArrayList<>();
        long matched = 0, missing = 0, countMismatch = 0;
        for (Long id : ids) {
            long sourceCount = source.getOrDefault(id, 0L);
            Long targetCount = target.get(id);
            String status;
            if (targetCount == null) { status = "MISSING"; missing++; }
            else if (targetCount == sourceCount) { status = "MATCHED"; matched++; }
            else { status = "COUNT_MISMATCH"; countMismatch++; }
            details.add(new CustomerVerification(id, sourceCount, targetCount == null ? 0 : targetCount, status));
        }
        return new VerificationResult(offset, limit, ids.size(), matched, missing, countMismatch, details);
    }


    public KeyVerificationResult verifyKeys(int offset, int limit) {
        List<Long> ids = stageRepository.findCustomersByOffset(offset, limit);
        Map<Long, Set<String>> source = stageRepository.sourceBusinessKeys(ids);
        Map<Long, Set<String>> target = mariaRepository.targetBusinessKeys(ids);
        List<KeyCustomerVerification> details = new ArrayList<>();
        long matched = 0, mismatched = 0;
        for (Long id : ids) {
            Set<String> sourceKeys = source.getOrDefault(id, Set.of());
            Set<String> targetKeys = target.getOrDefault(id, Set.of());
            Set<String> missing = new TreeSet<>(sourceKeys); missing.removeAll(targetKeys);
            Set<String> extra = new TreeSet<>(targetKeys); extra.removeAll(sourceKeys);
            String status = missing.isEmpty() && extra.isEmpty() ? "MATCHED" : "KEY_MISMATCH";
            if (status.equals("MATCHED")) matched++; else mismatched++;
            details.add(new KeyCustomerVerification(id, sourceKeys.size(), targetKeys.size(), status,
                    new ArrayList<>(missing), new ArrayList<>(extra)));
        }
        return new KeyVerificationResult(offset, limit, ids.size(), matched, mismatched, details);
    }

    public record BatchMonitor(String runId, long totalCustomers, long totalRows, boolean hasInProgress,
                               boolean stale, StageRepository.RunActivity activity,
                               List<StageRepository.RunStatusCount> statusCounts) {}
    public record CustomerVerification(long customerId, long sourceRows, long targetRows, String status) {}
    public record KeyCustomerVerification(long customerId, int sourceKeyCount, int targetKeyCount, String status,
                                          List<String> missingKeys, List<String> extraKeys) {}
    public record KeyVerificationResult(int offset, int limit, int checkedCustomers, long matched, long mismatched,
                                        List<KeyCustomerVerification> details) {}
    public record VerificationResult(int offset, int limit, int checkedCustomers, long matched, long missing,
                                     long countMismatch, List<CustomerVerification> details) {}
}
