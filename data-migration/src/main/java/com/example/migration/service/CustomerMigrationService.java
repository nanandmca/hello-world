package com.example.migration.service;

import com.example.migration.domain.StageRecord;
import com.example.migration.repository.MariaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerMigrationService {
    private final MariaRepository mariaRepository;

    @Transactional(transactionManager = "mariaTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void migrateCustomer(long customerId, List<StageRecord> rows) {
        if (rows.isEmpty()) {
            throw new IllegalStateException("No staging rows for customer " + customerId);
        }
        boolean mixedCustomer = rows.stream().anyMatch(row -> row.customerId() != customerId);
        if (mixedCustomer) {
            throw new IllegalArgumentException("Chunk contains rows from another customer");
        }
        mariaRepository.upsertRows(rows);
    }
}
