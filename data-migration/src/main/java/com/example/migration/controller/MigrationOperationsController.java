package com.example.migration.controller;

import com.example.migration.domain.RunResult;
import com.example.migration.repository.StageRepository;
import com.example.migration.service.ManualMigrationService;
import com.example.migration.service.MonitoringService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/internal/migration")
@RequiredArgsConstructor
public class MigrationOperationsController {
    private final ManualMigrationService manualService;
    private final MonitoringService monitoringService;
    private final StageRepository stageRepository;

    @PostMapping("/sanity")
    public RunResult sanity(@RequestParam(defaultValue = "500") @Min(1) @Max(5000) int limit) {
        return manualService.processLimit(limit);
    }

    @PostMapping("/process")
    public RunResult process(@RequestParam @Min(1) @Max(100000) int limit) {
        return manualService.processLimit(limit);
    }

    @GetMapping("/runs")
    public List<StageRepository.RunSummary> runs(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return monitoringService.recentRuns(limit);
    }

    @GetMapping("/runs/{runId}")
    public MonitoringService.BatchMonitor monitor(@PathVariable String runId) {
        return monitoringService.monitor(runId);
    }

    @PostMapping("/runs/{runId}/retrigger")
    public ResponseEntity<Map<String, Object>> retrigger(@PathVariable String runId,
            @RequestParam(defaultValue = "false") boolean includeFailed,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int processNowLimit) {
        int rowsReset = manualService.retrigger(runId, includeFailed, force);
        RunResult result = processNowLimit > 0 ? manualService.processLimit(processNowLimit) : null;
        return ResponseEntity.ok(Map.of("runId", runId, "rowsReset", rowsReset,
                "processNowResult", result == null ? "NOT_REQUESTED" : result));
    }

    @GetMapping("/pending")
    public Map<String, Long> pending() {
        return Map.of("pendingCustomers", stageRepository.countPendingCustomers());
    }


    @GetMapping("/verify/keys")
    public MonitoringService.KeyVerificationResult verifyKeys(
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return monitoringService.verifyKeys(offset, limit);
    }

    @GetMapping("/verify")
    public MonitoringService.VerificationResult verify(
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "500") @Min(1) @Max(5000) int limit) {
        return monitoringService.verify(offset, limit);
    }
}
