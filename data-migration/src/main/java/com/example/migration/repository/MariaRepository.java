package com.example.migration.repository;

import com.example.migration.config.MigrationProperties;
import com.example.migration.domain.StageRecord;
import com.example.migration.util.SqlPlaceholders;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class MariaRepository {

    @Qualifier("mariaJdbcTemplate")
    private final JdbcTemplate jdbc;
    private final MigrationProperties properties;

    /**
     * Safe only when the presence of any target row guarantees the customer was fully migrated.
     * If partial target data is possible, keep skip-existing-customers=false and upsert every customer.
     */
    public Set<Long> findExistingCustomers(List<Long> customerIds) {
        if (customerIds.isEmpty()) return Set.of();
        String sql = """
                SELECT DISTINCT CUSTOMER_SYS_GEN_ID
                  FROM TARGET_PERSONALISATION
                 WHERE CUSTOMER_SYS_GEN_ID IN (%s)
                """.formatted(SqlPlaceholders.forCount(customerIds.size()));
        return new HashSet<>(jdbc.queryForList(sql, Long.class, customerIds.toArray()));
    }


    public java.util.Map<Long, Long> targetCounts(List<Long> customerIds) {
        if (customerIds.isEmpty()) return java.util.Map.of();
        String sql = """
                SELECT CUSTOMER_SYS_GEN_ID, COUNT(*) AS ROW_COUNT
                  FROM TARGET_PERSONALISATION
                 WHERE CUSTOMER_SYS_GEN_ID IN (%s)
                 GROUP BY CUSTOMER_SYS_GEN_ID
                """.formatted(SqlPlaceholders.forCount(customerIds.size()));
        java.util.Map<Long, Long> result = new java.util.HashMap<>();
        jdbc.query(sql, rs -> result.put(rs.getLong("CUSTOMER_SYS_GEN_ID"), rs.getLong("ROW_COUNT")), customerIds.toArray());
        return result;
    }


    public java.util.Map<Long, java.util.Set<String>> targetBusinessKeys(List<Long> customerIds) {
        if (customerIds.isEmpty()) return java.util.Map.of();
        String sql = """
                SELECT CUSTOMER_SYS_GEN_ID, PERSONALISATION_TYPE, PERSONALISATION_KEY
                  FROM TARGET_PERSONALISATION
                 WHERE CUSTOMER_SYS_GEN_ID IN (%s)
                """.formatted(SqlPlaceholders.forCount(customerIds.size()));
        java.util.Map<Long, java.util.Set<String>> result = new java.util.HashMap<>();
        jdbc.query(sql, rs -> result.computeIfAbsent(rs.getLong("CUSTOMER_SYS_GEN_ID"), k -> new java.util.HashSet<>())
                .add(rs.getString("PERSONALISATION_TYPE") + "|" + rs.getString("PERSONALISATION_KEY")), customerIds.toArray());
        return result;
    }

    public void upsertRows(List<StageRecord> rows) {
        if (rows.isEmpty()) return;
        String sql = """
                INSERT INTO TARGET_PERSONALISATION
                (CUSTOMER_SYS_GEN_ID,
                 PERSONALISATION_TYPE,
                 PERSONALISATION_KEY,
                 PERSONALISATION_VALUE,
                 SOURCE_UPDATED_AT,
                 MIGRATED_AT)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                  PERSONALISATION_VALUE = VALUES(PERSONALISATION_VALUE),
                  SOURCE_UPDATED_AT = VALUES(SOURCE_UPDATED_AT),
                  MIGRATED_AT = CURRENT_TIMESTAMP(6)
                """;

        jdbc.batchUpdate(sql, rows, properties.getMariaUpsertBatchSize(), (ps, row) -> {
            ps.setLong(1, row.customerId());
            ps.setString(2, row.personalisationType());
            ps.setString(3, row.personalisationKey());
            ps.setString(4, row.personalisationValue());
            if (row.sourceUpdatedAt() == null) {
                ps.setTimestamp(5, null);
            } else {
                ps.setTimestamp(5, Timestamp.valueOf(row.sourceUpdatedAt()));
            }
        });
    }
}
