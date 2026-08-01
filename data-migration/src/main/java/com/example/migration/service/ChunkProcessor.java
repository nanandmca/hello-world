package com.example.migration.service;

import com.example.migration.config.MigrationProperties;
import com.example.migration.domain.ChunkResult;
import com.example.migration.domain.CustomerFailure;
import com.example.migration.domain.StageRecord;
import com.example.migration.repository.MariaRepository;
import com.example.migration.repository.StageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkProcessor {
    private final StageRepository stageRepository;
    private final MariaRepository mariaRepository;
    private final BulkMariaMigrationService bulkMigrationService;
    private final CustomerMigrationService customerMigrationService;
    private final MigrationProperties properties;
    public ChunkResult process(String runId, String workerId, List<Long> customerIds) {
        List<Long> alreadyMigrated = new ArrayList<>();
        List<Long> migrated = new ArrayList<>();
        List<CustomerFailure> failures = new ArrayList<>();

        try {
            List<Long> pending = customerIds;
            if (properties.isSkipExistingCustomers()) {
                Set<Long> existing = mariaRepository.findExistingCustomers(customerIds);
                alreadyMigrated.addAll(existing);
                pending = customerIds.stream().filter(id -> !existing.contains(id)).toList();
                if (!alreadyMigrated.isEmpty()) {
                    stageRepository.markAlreadyMigrated(runId, workerId, alreadyMigrated);
                }
            }

            if (!pending.isEmpty()) {
                List<StageRecord> rows = stageRepository.findRows(runId, workerId, pending);
                Map<Long, List<StageRecord>> rowsByCustomer = rows.stream().collect(Collectors.groupingBy(
                        StageRecord::customerId,
                        LinkedHashMap::new,
                        Collectors.toList()));

                try {
                    // Fast path: one transaction and JDBC batches for the whole chunk.
                    bulkMigrationService.migrateChunk(rows);
                    migrated.addAll(pending);
                } catch (Exception bulkError) {
                    // Isolation path: roll back the chunk and identify bad customers one by one.
                    log.warn("Bulk chunk failed; falling back to customer transactions runId={} customers={}",
                            runId, pending.size(), bulkError);
                    for (Long customerId : pending) {
                        try {
                            customerMigrationService.migrateCustomer(
                                    customerId, rowsByCustomer.getOrDefault(customerId, List.of()));
                            migrated.add(customerId);
                        } catch (Exception customerError) {
                            log.warn("Customer migration failed runId={} customerId={}",
                                    runId, customerId, customerError);
                            failures.add(CustomerFailure.from(customerId, customerError));
                        }
                    }
                }

                if (!migrated.isEmpty()) stageRepository.markMigrated(runId, workerId, migrated);
                if (!failures.isEmpty()) stageRepository.markFailures(runId, workerId, failures);
            }

            return new ChunkResult(migrated.size(), alreadyMigrated.size(), failures.size());
        } catch (Exception chunkError) {
            log.error("Whole chunk failed runId={} customers={}", runId, customerIds.size(), chunkError);
            List<CustomerFailure> chunkFailures = customerIds.stream()
                    .map(id -> CustomerFailure.from(id, chunkError))
                    .toList();
            stageRepository.markFailures(runId, workerId, chunkFailures);
            return new ChunkResult(0, 0, customerIds.size());
        }
    }
}
