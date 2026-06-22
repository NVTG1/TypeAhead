package com.typeahead.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bulk-loads dataset/queries.csv (format: query,count - one row per
 * distinct query, header row included) into the search_query table on
 * application startup, if the table is currently empty.
 *
 * Why raw JDBC batching instead of JpaRepository.saveAll():
 *   Hibernate's saveAll on 100,000+ entities generates one round trip
 *   (or, even with batching enabled, a lot of per-entity overhead:
 *   dirty checking, persistence-context bookkeeping) per row by default.
 *   A plain JDBC PreparedStatement with addBatch()/executeBatch() in
 *   chunks of BATCH_SIZE is dramatically faster for a one-time bulk
 *   import and keeps startup time reasonable on a laptop.
 *
 * Swapping in the real AOL-derived dataset: replace
 * src/main/resources/dataset/queries.csv with the output of
 * data/aggregate_aol_log.py run against the real AOL log file. The
 * format (header "query,count", one row per distinct query) is
 * identical, so no code change is needed here.
 */
@Component
@Slf4j
public class DataLoader implements ApplicationRunner {

    private static final int BATCH_SIZE = 1000;
    private static final String CSV_RESOURCE_PATH = "dataset/queries.csv";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public DataLoader(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Long existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM search_query", Long.class);
        if (existing != null && existing > 0) {
            log.info("search_query already has {} rows - skipping dataset load", existing);
            return;
        }

        ClassPathResource resource = new ClassPathResource(CSV_RESOURCE_PATH);
        if (!resource.exists()) {
            log.warn("No dataset found at classpath:{} - starting with an empty table. " +
                    "Run data/generate_sample_dataset.py or data/aggregate_aol_log.py and " +
                    "place the output at backend/src/main/resources/dataset/queries.csv", CSV_RESOURCE_PATH);
            return;
        }

        long start = System.currentTimeMillis();
        int totalLoaded = 0;

        String insertSql = """
            INSERT INTO search_query (query, query_lower, count, last_searched_at, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (query) DO NOTHING
            """;

        try (InputStream is = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
             Connection conn = dataSource.getConnection()) {

            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                String line = reader.readLine(); // header row, discarded
                int batched = 0;
                Timestamp now = Timestamp.from(Instant.now());

                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Row row = parseLine(line);
                    if (row == null) continue;

                    ps.setString(1, row.query());
                    ps.setString(2, row.query().toLowerCase());
                    ps.setLong(3, row.count());
                    ps.setTimestamp(4, now);
                    ps.setTimestamp(5, now);
                    ps.addBatch();
                    batched++;
                    totalLoaded++;

                    if (batched >= BATCH_SIZE) {
                        ps.executeBatch();
                        conn.commit();
                        batched = 0;
                    }
                }
                if (batched > 0) {
                    ps.executeBatch();
                    conn.commit();
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - start;
        log.info("Loaded {} queries from {} in {} ms", totalLoaded, CSV_RESOURCE_PATH, elapsedMs);
    }

    private record Row(String query, long count) {}

    /**
     * Minimal CSV parsing for our own generated format. Handles the
     * common case (no embedded commas/quotes in query text from our
     * generator/aggregator) plus a defensive fallback for quoted fields,
     * since real-world search queries occasionally do contain commas.
     */
    private Row parseLine(String line) {
        try {
            int lastComma = line.lastIndexOf(',');
            if (lastComma < 0) return null;
            String queryPart = line.substring(0, lastComma).trim();
            String countPart = line.substring(lastComma + 1).trim();

            // strip wrapping quotes if present (defensive, for queries containing commas)
            if (queryPart.startsWith("\"") && queryPart.endsWith("\"") && queryPart.length() >= 2) {
                queryPart = queryPart.substring(1, queryPart.length() - 1).replace("\"\"", "\"");
            }
            if (queryPart.isBlank()) return null;
            if (queryPart.length() > 512) queryPart = queryPart.substring(0, 512);

            long count = Long.parseLong(countPart);
            return new Row(queryPart, count);
        } catch (Exception e) {
            return null; // skip malformed rows rather than failing the whole load
        }
    }
}
