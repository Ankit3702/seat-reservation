package com.paytmmoney.seats.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;

/**
 * Single file DB (H2). Same conditional-UPDATE SQL runs unchanged on
 * Oracle/Postgres — only the JDBC URL changes. Mirrors the Servosys loan
 * app pattern: one JdbcTemplate/Hibernate datasource, indexed lookups.
 */
@Configuration
public class DataConfig {

    @Bean
    public DataSource dataSource(@Value("${app.db-path:mem:seats}") String dbPath) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        if (dbPath.startsWith("mem:")) {
            // In-memory: no fsync per commit (~100x faster on network disks).
            // Safe on free tiers where the filesystem is ephemeral anyway;
            // use a file path (or Postgres) where durability is required.
            ds.setUrl("jdbc:h2:mem:seats;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000;CACHE_SIZE=131072");
        } else {
            ds.setUrl("jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE;LOCK_TIMEOUT=60000;CACHE_SIZE=131072;DB_CLOSE_DELAY=-1");
        }
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    public TransactionTemplate txTemplate(DataSource ds) {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(ds);
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        t.setTimeout(120);
        return t;
    }

    /** DDL + index init (like the Oracle indexing work: seat lookups are indexed). */
    @Bean
    public org.springframework.boot.CommandLineRunner initSchema(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("CREATE TABLE IF NOT EXISTS shows(" +
                "id VARCHAR(32) PRIMARY KEY, name VARCHAR(256) NOT NULL, " +
                "price_paise BIGINT NOT NULL, per_user_limit INT NOT NULL, created_at VARCHAR(32) NOT NULL)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS seats(" +
                "show_id VARCHAR(32) NOT NULL, seat_no VARCHAR(32) NOT NULL, " +
                "status VARCHAR(16) NOT NULL, holder_user_id VARCHAR(128), reservation_id VARCHAR(32), " +
                "PRIMARY KEY(show_id, seat_no))");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_seats_holder ON seats(show_id, holder_user_id, status)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_seats_status ON seats(show_id, status)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS reservations(" +
                "id VARCHAR(32) PRIMARY KEY, show_id VARCHAR(32) NOT NULL, user_id VARCHAR(128) NOT NULL, " +
                "seats_json CLOB NOT NULL, amount_paise BIGINT NOT NULL, status VARCHAR(16) NOT NULL, " +
                "created_at VARCHAR(32) NOT NULL)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS idempotency(" +
                "ikey VARCHAR(256) PRIMARY KEY, user_id VARCHAR(128) NOT NULL, show_id VARCHAR(32) NOT NULL, " +
                "body_hash VARCHAR(64) NOT NULL, reservation_id VARCHAR(32), " +
                "status_code INT NOT NULL, response_json CLOB NOT NULL, created_at VARCHAR(32) NOT NULL)");
        };
    }
}
