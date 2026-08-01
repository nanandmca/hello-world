package com.example.migration.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Bean("db2DataSource")
    @ConfigurationProperties("app.datasource.db2")
    public HikariDataSource db2DataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean("mariaDataSource")
    @ConfigurationProperties("app.datasource.maria")
    public HikariDataSource mariaDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean("db2JdbcTemplate")
    public JdbcTemplate db2JdbcTemplate(@Qualifier("db2DataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean("mariaJdbcTemplate")
    public JdbcTemplate mariaJdbcTemplate(@Qualifier("mariaDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean("db2TransactionManager")
    public PlatformTransactionManager db2TransactionManager(@Qualifier("db2DataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean("mariaTransactionManager")
    public PlatformTransactionManager mariaTransactionManager(@Qualifier("mariaDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}
