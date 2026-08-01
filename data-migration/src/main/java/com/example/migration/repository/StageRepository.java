package com.example.migration.repository;

import com.example.migration.config.MigrationProperties;
import com.example.migration.domain.CustomerFailure;
import com.example.migration.domain.StageRecord;
import com.example.migration.util.Partitions;
import com.example.migration.util.SqlPlaceholders;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class StageRepository {

    @Qualifier("db2JdbcTemplate")
    private final JdbcTemplate jdbc;
    private final MigrationProperties properties;

    public List<Long> findCandidateCustomers(int limit) {
        String sql = """
                SELECT CUSTOMER_SYS_GEN_ID
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE STATUS IN ('PENDING', 'RETRY_PENDING')
                 GROUP BY CUSTOMER_SYS_GEN_ID
                 ORDER BY MIN(STAGE_ID)
                 FETCH FIRST %d ROWS ONLY
                """.formatted(limit);
        return jdbc.queryForList(sql, Long.class);
    }

    @Transactional(transactionManager = "db2TransactionManager")
    public void claimCandidates(String runId, String workerId, List<Long> candidates) {
        String sql = """
                UPDATE PERSONALISATION_MIGRATION_STAGE
                   SET STATUS = 'IN_PROGRESS',
                       RUN_ID = ?,
                       CLAIMED_BY = ?,
                       CLAIMED_AT = CURRENT TIMESTAMP,
                       LAST_HEARTBEAT_AT = CURRENT TIMESTAMP,
                       UPDATED_AT = CURRENT TIMESTAMP,
                       ERROR_CODE = NULL,
                       ERROR_MESSAGE = NULL
                 WHERE CUSTOMER_SYS_GEN_ID = ?
                   AND STATUS IN ('PENDING', 'RETRY_PENDING')
                """;

        jdbc.batchUpdate(sql, candidates, properties.getClaimJdbcBatchSize(), (ps, customerId) -> {
            ps.setString(1, runId);
            ps.setString(2, workerId);
            ps.setLong(3, customerId);
        });
    }

    public List<Long> findClaimedCustomers(String runId, String workerId, int limit) {
        String sql = """
                SELECT CUSTOMER_SYS_GEN_ID
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE RUN_ID = ?
                   AND CLAIMED_BY = ?
                   AND STATUS = 'IN_PROGRESS'
                 GROUP BY CUSTOMER_SYS_GEN_ID
                 ORDER BY MIN(STAGE_ID)
                 FETCH FIRST %d ROWS ONLY
                """.formatted(limit);
        return jdbc.queryForList(sql, Long.class, runId, workerId);
    }

    public List<StageRecord> findRows(String runId, String workerId, List<Long> customerIds) {
        if (customerIds.isEmpty()) return List.of();
        String sql = """
                SELECT STAGE_ID,
                       CUSTOMER_SYS_GEN_ID,
                       PERSONALISATION_TYPE,
                       PERSONALISATION_KEY,
                       PERSONALISATION_VALUE,
                       SOURCE_UPDATED_AT
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE RUN_ID = ?
                   AND CLAIMED_BY = ?
                   AND STATUS = 'IN_PROGRESS'
                   AND CUSTOMER_SYS_GEN_ID IN (%s)
                 ORDER BY CUSTOMER_SYS_GEN_ID, STAGE_ID
                """.formatted(SqlPlaceholders.forCount(customerIds.size()));

        List<Object> args = new ArrayList<>();
        args.add(runId);
        args.add(workerId);
        args.addAll(customerIds);

        return jdbc.query(sql, (rs, rowNum) -> new StageRecord(
                rs.getLong("STAGE_ID"),
                rs.getLong("CUSTOMER_SYS_GEN_ID"),
                rs.getString("PERSONALISATION_TYPE"),
                rs.getString("PERSONALISATION_KEY"),
                rs.getString("PERSONALISATION_VALUE"),
                rs.getTimestamp("SOURCE_UPDATED_AT") == null ? null : rs.getTimestamp("SOURCE_UPDATED_AT").toLocalDateTime()
        ), args.toArray());
    }

    @Transactional(transactionManager = "db2TransactionManager")
    public void markMigrated(String runId, String workerId, List<Long> customerIds) {
        updateUniformStatus(runId, workerId, customerIds, "MIGRATED");
    }

    @Transactional(transactionManager = "db2TransactionManager")
    public void markAlreadyMigrated(String runId, String workerId, List<Long> customerIds) {
        updateUniformStatus(runId, workerId, customerIds, "ALREADY_MIGRATED");
    }

    protected void updateUniformStatus(String runId, String workerId, List<Long> customerIds, String status) {
        for (List<Long> chunk : Partitions.of(customerIds, properties.getStatusUpdateChunkSize())) {
            if (chunk.isEmpty()) continue;
            String sql = """
                    UPDATE PERSONALISATION_MIGRATION_STAGE
                       SET STATUS = ?,
                           COMPLETED_AT = CURRENT TIMESTAMP,
                           LAST_HEARTBEAT_AT = CURRENT TIMESTAMP,
                           UPDATED_AT = CURRENT TIMESTAMP,
                           ERROR_CODE = NULL,
                           ERROR_MESSAGE = NULL
                     WHERE RUN_ID = ?
                       AND CLAIMED_BY = ?
                       AND STATUS = 'IN_PROGRESS'
                       AND CUSTOMER_SYS_GEN_ID IN (%s)
                    """.formatted(SqlPlaceholders.forCount(chunk.size()));

            List<Object> args = new ArrayList<>();
            args.add(status);
            args.add(runId);
            args.add(workerId);
            args.addAll(chunk);
            jdbc.update(sql, args.toArray());
        }
    }

    @Transactional(transactionManager = "db2TransactionManager")
    public void markFailures(String runId, String workerId, List<CustomerFailure> failures) {
        if (failures.isEmpty()) return;
        String sql = """
                UPDATE PERSONALISATION_MIGRATION_STAGE
                   SET STATUS = CASE WHEN RETRY_COUNT + 1 < ? THEN 'RETRY_PENDING' ELSE 'FAILED' END,
                       RETRY_COUNT = RETRY_COUNT + 1,
                       RUN_ID = RUN_ID,
                       CLAIMED_BY = CLAIMED_BY,
                       CLAIMED_AT = CLAIMED_AT,
                       LAST_HEARTBEAT_AT = NULL,
                       COMPLETED_AT = CASE WHEN RETRY_COUNT + 1 >= ? THEN CURRENT TIMESTAMP ELSE NULL END,
                       ERROR_CODE = ?,
                       ERROR_MESSAGE = ?,
                       UPDATED_AT = CURRENT TIMESTAMP
                 WHERE RUN_ID = ?
                   AND CLAIMED_BY = ?
                   AND CUSTOMER_SYS_GEN_ID = ?
                   AND STATUS = 'IN_PROGRESS'
                """;

        jdbc.batchUpdate(sql, failures, 100, (ps, failure) -> {
            int max = properties.getMaxRetries();
            ps.setInt(1, max);
            ps.setInt(2, max);
            ps.setString(3, truncate(failure.errorCode(), 100));
            ps.setString(4, truncate(failure.errorMessage(), 1900));
            ps.setString(5, runId);
            ps.setString(6, workerId);
            ps.setLong(7, failure.customerId());
        });
    }

    public int heartbeat(String runId, String workerId) {
        return jdbc.update("""
                UPDATE PERSONALISATION_MIGRATION_STAGE
                   SET LAST_HEARTBEAT_AT = CURRENT TIMESTAMP,
                       UPDATED_AT = CURRENT TIMESTAMP
                 WHERE RUN_ID = ?
                   AND CLAIMED_BY = ?
                   AND STATUS = 'IN_PROGRESS'
                """, runId, workerId);
    }

    @Transactional(transactionManager = "db2TransactionManager")
    public int recoverStale(int staleMinutes) {
        String sql = """
                UPDATE PERSONALISATION_MIGRATION_STAGE
                   SET STATUS = CASE WHEN RETRY_COUNT + 1 < ? THEN 'RETRY_PENDING' ELSE 'FAILED' END,
                       RETRY_COUNT = RETRY_COUNT + 1,
                       RUN_ID = RUN_ID,
                       CLAIMED_BY = CLAIMED_BY,
                       CLAIMED_AT = CLAIMED_AT,
                       LAST_HEARTBEAT_AT = NULL,
                       COMPLETED_AT = CASE WHEN RETRY_COUNT + 1 >= ? THEN CURRENT TIMESTAMP ELSE NULL END,
                       ERROR_CODE = 'STALE_WORKER',
                       ERROR_MESSAGE = 'Worker heartbeat expired',
                       UPDATED_AT = CURRENT TIMESTAMP
                 WHERE STATUS = 'IN_PROGRESS'
                   AND LAST_HEARTBEAT_AT < CURRENT TIMESTAMP - %d MINUTES
                """.formatted(staleMinutes);
        int max = properties.getMaxRetries();
        return jdbc.update(sql, max, max);
    }



    public RunActivity runActivity(String runId) {
        return jdbc.queryForObject("""
                SELECT MIN(CLAIMED_AT) AS STARTED_AT,
                       MAX(LAST_HEARTBEAT_AT) AS LAST_HEARTBEAT_AT,
                       MAX(UPDATED_AT) AS LAST_ACTIVITY_AT,
                       COUNT(CASE WHEN STATUS = 'IN_PROGRESS' THEN 1 END) AS IN_PROGRESS_ROWS
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE RUN_ID = ?
                """, (rs, n) -> new RunActivity(
                rs.getTimestamp("STARTED_AT") == null ? null : rs.getTimestamp("STARTED_AT").toLocalDateTime(),
                rs.getTimestamp("LAST_HEARTBEAT_AT") == null ? null : rs.getTimestamp("LAST_HEARTBEAT_AT").toLocalDateTime(),
                rs.getTimestamp("LAST_ACTIVITY_AT") == null ? null : rs.getTimestamp("LAST_ACTIVITY_AT").toLocalDateTime(),
                rs.getLong("IN_PROGRESS_ROWS")), runId);
    }

    public List<RunStatusCount> statusCounts(String runId) {
        return jdbc.query("""
                SELECT STATUS, COUNT(DISTINCT CUSTOMER_SYS_GEN_ID) AS CUSTOMER_COUNT, COUNT(*) AS ROW_COUNT
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE RUN_ID = ?
                 GROUP BY STATUS
                 ORDER BY STATUS
                """, (rs, n) -> new RunStatusCount(rs.getString("STATUS"),
                rs.getLong("CUSTOMER_COUNT"), rs.getLong("ROW_COUNT")), runId);
    }

    public List<RunSummary> recentRuns(int limit) {
        String sql = """
                SELECT RUN_ID, CLAIMED_BY,
                       MIN(CLAIMED_AT) AS STARTED_AT,
                       MAX(COALESCE(COMPLETED_AT, UPDATED_AT)) AS LAST_ACTIVITY_AT,
                       COUNT(DISTINCT CUSTOMER_SYS_GEN_ID) AS CUSTOMER_COUNT,
                       COUNT(*) AS ROW_COUNT,
                       SUM(CASE WHEN STATUS = 'IN_PROGRESS' THEN 1 ELSE 0 END) AS IN_PROGRESS_ROWS,
                       SUM(CASE WHEN STATUS = 'FAILED' THEN 1 ELSE 0 END) AS FAILED_ROWS,
                       SUM(CASE WHEN STATUS = 'MIGRATED' THEN 1 ELSE 0 END) AS MIGRATED_ROWS,
                       SUM(CASE WHEN STATUS = 'ALREADY_MIGRATED' THEN 1 ELSE 0 END) AS ALREADY_ROWS
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE RUN_ID IS NOT NULL
                 GROUP BY RUN_ID, CLAIMED_BY
                 ORDER BY MAX(COALESCE(COMPLETED_AT, UPDATED_AT)) DESC
                 FETCH FIRST %d ROWS ONLY
                """.formatted(limit);
        return jdbc.query(sql, (rs, n) -> new RunSummary(rs.getString("RUN_ID"), rs.getString("CLAIMED_BY"),
                rs.getTimestamp("STARTED_AT") == null ? null : rs.getTimestamp("STARTED_AT").toLocalDateTime(),
                rs.getTimestamp("LAST_ACTIVITY_AT") == null ? null : rs.getTimestamp("LAST_ACTIVITY_AT").toLocalDateTime(),
                rs.getLong("CUSTOMER_COUNT"), rs.getLong("ROW_COUNT"), rs.getLong("IN_PROGRESS_ROWS"),
                rs.getLong("FAILED_ROWS"), rs.getLong("MIGRATED_ROWS"), rs.getLong("ALREADY_ROWS")));
    }

    @Transactional(transactionManager = "db2TransactionManager")
    public int resetRunForRetry(String runId, boolean includeFailed) {
        String statuses = includeFailed ? "('IN_PROGRESS','RETRY_PENDING','FAILED')" : "('IN_PROGRESS','RETRY_PENDING')";
        return jdbc.update("""
                UPDATE PERSONALISATION_MIGRATION_STAGE
                   SET STATUS = 'RETRY_PENDING', RUN_ID = NULL, CLAIMED_BY = NULL, CLAIMED_AT = NULL,
                       LAST_HEARTBEAT_AT = NULL, COMPLETED_AT = NULL,
                       ERROR_CODE = 'MANUAL_RETRIGGER', ERROR_MESSAGE = 'Reset by operator for retrigger',
                       UPDATED_AT = CURRENT TIMESTAMP
                 WHERE RUN_ID = ? AND STATUS IN %s
                """.formatted(statuses), runId);
    }

    public long countPendingCustomers() {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT CUSTOMER_SYS_GEN_ID)
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE STATUS IN ('PENDING','RETRY_PENDING')
                """, Long.class);
        return value == null ? 0 : value;
    }

    public List<Long> findCustomersByOffset(int offset, int limit) {
        String sql = """
                SELECT CUSTOMER_SYS_GEN_ID
                  FROM (SELECT CUSTOMER_SYS_GEN_ID, MIN(STAGE_ID) AS FIRST_STAGE_ID
                          FROM PERSONALISATION_MIGRATION_STAGE
                         GROUP BY CUSTOMER_SYS_GEN_ID)
                 ORDER BY FIRST_STAGE_ID
                 OFFSET %d ROWS FETCH NEXT %d ROWS ONLY
                """.formatted(offset, limit);
        return jdbc.queryForList(sql, Long.class);
    }

    public List<SourceCustomerCount> sourceCounts(List<Long> customerIds) {
        if (customerIds.isEmpty()) return List.of();
        String sql = """
                SELECT CUSTOMER_SYS_GEN_ID, COUNT(*) AS ROW_COUNT
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE CUSTOMER_SYS_GEN_ID IN (%s)
                 GROUP BY CUSTOMER_SYS_GEN_ID
                """.formatted(SqlPlaceholders.forCount(customerIds.size()));
        return jdbc.query(sql, (rs, n) -> new SourceCustomerCount(rs.getLong("CUSTOMER_SYS_GEN_ID"),
                rs.getLong("ROW_COUNT")), customerIds.toArray());
    }


    public java.util.Map<Long, java.util.Set<String>> sourceBusinessKeys(List<Long> customerIds) {
        if (customerIds.isEmpty()) return java.util.Map.of();
        String sql = """
                SELECT CUSTOMER_SYS_GEN_ID, PERSONALISATION_TYPE, PERSONALISATION_KEY
                  FROM PERSONALISATION_MIGRATION_STAGE
                 WHERE CUSTOMER_SYS_GEN_ID IN (%s)
                """.formatted(SqlPlaceholders.forCount(customerIds.size()));
        java.util.Map<Long, java.util.Set<String>> result = new java.util.HashMap<>();
        jdbc.query(sql, rs -> result.computeIfAbsent(rs.getLong("CUSTOMER_SYS_GEN_ID"), k -> new java.util.HashSet<>())
                .add(rs.getString("PERSONALISATION_TYPE") + "|" + rs.getString("PERSONALISATION_KEY")), customerIds.toArray());
        return result;
    }

    public record RunActivity(java.time.LocalDateTime startedAt, java.time.LocalDateTime lastHeartbeatAt,
                              java.time.LocalDateTime lastActivityAt, long inProgressRows) {}
    public record RunStatusCount(String status, long customerCount, long rowCount) {}
    public record RunSummary(String runId, String claimedBy, java.time.LocalDateTime startedAt,
                             java.time.LocalDateTime lastActivityAt, long customerCount, long rowCount,
                             long inProgressRows, long failedRows, long migratedRows, long alreadyMigratedRows) {}
    public record SourceCustomerCount(long customerId, long rowCount) {}

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
