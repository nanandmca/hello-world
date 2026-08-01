package com.example.migration.service;

import com.example.migration.config.MigrationProperties;
import com.example.migration.domain.ChunkResult;
import com.example.migration.domain.ClaimResult;
import com.example.migration.domain.RunResult;
import com.example.migration.repository.StageRepository;
import com.example.migration.util.Partitions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunProcessor {
    private final ChunkProcessor chunkProcessor;
    private final StageRepository stageRepository;
    private final MigrationProperties properties;
    @Qualifier("migrationExecutor")
    private final ExecutorService migrationExecutor;

    public RunResult processAndWait(ClaimResult claim) {
        Instant start = Instant.now();
        List<List<Long>> chunks = Partitions.of(claim.customerIds(), properties.getCustomerChunkSize());
        CompletionService<ChunkResult> completion = new ExecutorCompletionService<>(migrationExecutor);
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("heartbeat-" + claim.runId()).daemon(true).factory());
        heartbeat.scheduleWithFixedDelay(() -> safeHeartbeat(claim),
                properties.getHeartbeatIntervalSeconds(), properties.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);

        int next = 0, submitted = 0, completed = 0, migrated = 0, already = 0, failed = 0, failedChunks = 0;
        try {
            int initial = Math.min(properties.getAsyncThreads(), chunks.size());
            for (; next < initial; next++) {
                submit(completion, claim, chunks.get(next));
                submitted++;
            }
            while (completed < submitted) {
                try {
                    ChunkResult r = completion.take().get();
                    migrated += r.migrated(); already += r.alreadyMigrated(); failed += r.failed();
                } catch (ExecutionException e) {
                    failedChunks++;
                    log.error("Unexpected chunk failure runId={}", claim.runId(), e.getCause());
                }
                completed++;
                if (next < chunks.size()) {
                    submit(completion, claim, chunks.get(next++));
                    submitted++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Run interrupted " + claim.runId(), e);
        } finally {
            heartbeat.shutdownNow();
        }
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        return new RunResult(claim.runId(), claim.customerIds().size(), migrated, already, failed, failedChunks, elapsed);
    }

    private void submit(CompletionService<ChunkResult> completion, ClaimResult claim, List<Long> chunk) {
        completion.submit(() -> chunkProcessor.process(claim.runId(), claim.workerId(), chunk));
    }

    private void safeHeartbeat(ClaimResult claim) {
        try { stageRepository.heartbeat(claim.runId(), claim.workerId()); }
        catch (Exception e) { log.error("Heartbeat failed runId={}", claim.runId(), e); }
    }
}
