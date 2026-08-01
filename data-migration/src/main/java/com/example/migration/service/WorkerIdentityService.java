package com.example.migration.service;

import com.example.migration.config.MigrationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkerIdentityService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS", Locale.ROOT);
    private final MigrationProperties properties;
    private final Clock clock = Clock.systemUTC();

    public String workerId() {
        String index = firstNonBlank(
                System.getenv("CF_INSTANCE_INDEX"),
                System.getenv("INSTANCE_INDEX"),
                "0");
        String guid = firstNonBlank(System.getenv("CF_INSTANCE_GUID"), hostName(), "local");
        return properties.getWorkerPrefix() + "_" + index + "_" + shorten(guid, 10);
    }

    public String newRunId() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        return workerId() + "_" + LocalDateTime.now(clock).format(TS) + "_" + suffix;
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "unknown";
    }

    private String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
