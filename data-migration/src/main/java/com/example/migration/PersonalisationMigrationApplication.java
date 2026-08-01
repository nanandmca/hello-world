package com.example.migration;

import com.example.migration.config.MigrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MigrationProperties.class)
public class PersonalisationMigrationApplication {
    public static void main(String[] args) {
        SpringApplication.run(PersonalisationMigrationApplication.class, args);
    }
}
