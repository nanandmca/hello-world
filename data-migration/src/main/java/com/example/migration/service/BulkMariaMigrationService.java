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
public class BulkMariaMigrationService {
    private final MariaRepository mariaRepository;

    /** Fast success path: one MariaDB transaction for one customer chunk. */
    @Transactional(transactionManager = "mariaTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void migrateChunk(List<StageRecord> rows) {
        if (!rows.isEmpty()) mariaRepository.upsertRows(rows);
    }
}
