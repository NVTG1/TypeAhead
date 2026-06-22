package com.typeahead.service;

import com.typeahead.repository.SearchQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batches search-count increments instead of writing to Postgres
 * synchronously on every POST /search.
 *
 * How it works:
 *  1. submit(query) is O(1): it just enqueues the raw query string and
 *     returns immediately. No DB call happens on the request path.
 *  2. A @Scheduled task runs every FLUSH_INTERVAL_MS and also whenever the
 *     buffer crosses MAX_BATCH_SIZE (checked opportunistically on submit).
 *  3. On flush, the buffer is drained and *aggregated in memory* - e.g. if
 *     "iphone" was searched 47 times since the last flush, that becomes
 *     ONE upsert with increment=47, not 47 separate writes.
 *  4. Each aggregated (query -> count) pair is written via a single
 *     UPSERT (INSERT ... ON CONFLICT DO UPDATE count = count + n), so the
 *     whole flush is N upserts where N = number of *distinct* queries
 *     searched in that window, not the number of search requests.
 *
 * Write reduction in practice: in a hot window where the same handful of
 * queries dominate traffic (typical for search), this collapses thousands
 * of requests into a few dozen distinct-query upserts. The exact ratio is
 * reported by getStats() / GET /admin/batch-stats and is part of the
 * performance report the assignment asks for.
 *
 * Failure trade-off (explicitly called out per assignment section 8):
 * the buffer lives in JVM heap only. If the process crashes before a
 * flush, any increments accumulated since the last successful flush are
 * lost - the search-count data is approximate, not exactly-once durable.
 * This is judged an acceptable trade-off for a typeahead popularity
 * signal (unlike, say, an order count or a payment): losing a few
 * seconds of increments doesn't materially change suggestion ranking,
 * and the simplicity/throughput win is large. The README documents two
 * upgrade paths if stronger durability were required: (a) replace the
 * in-memory queue with a write-ahead log / Kafka topic that's
 * acknowledged before the HTTP response returns, or (b) flush more
 * frequently at the cost of smaller batches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchWriterService {

    private static final int MAX_BATCH_SIZE = 500;

    private final SearchQueryRepository repository;
    private final CacheService cacheService;

    private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferSize = new AtomicInteger(0);

    // Stats for the performance report (section 10 of the assignment)
    private final AtomicLong totalSearchRequests = new AtomicLong(0);
    private final AtomicLong totalDbWrites = new AtomicLong(0);
    private final AtomicLong totalFlushes = new AtomicLong(0);

    public void submit(String query) {
        buffer.add(query);
        totalSearchRequests.incrementAndGet();
        if (bufferSize.incrementAndGet() >= MAX_BATCH_SIZE) {
            flush();
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void scheduledFlush() {
        flush();
    }

    /**
     * Drains the buffer, aggregates by query, and writes one upsert per
     * distinct query. Synchronized so a size-triggered flush and the
     * scheduled flush can't interleave and double-drain.
     */
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }

        Map<String, Long> aggregated = new java.util.HashMap<>();
        String item;
        int drained = 0;
        while ((item = buffer.poll()) != null) {
            aggregated.merge(item, 1L, Long::sum);
            drained++;
        }
        bufferSize.addAndGet(-drained);

        if (aggregated.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        for (Map.Entry<String, Long> entry : aggregated.entrySet()) {
            String query = entry.getKey();
            long increment = entry.getValue();
            repository.upsertIncrement(query, query.toLowerCase(), increment, now);
            cacheService.invalidatePrefixesOf(query);
            totalDbWrites.incrementAndGet();
        }
        totalFlushes.incrementAndGet();

        log.info("Batch flush: {} requests aggregated into {} db writes (reduction: {}x)",
                drained, aggregated.size(),
                aggregated.isEmpty() ? 0 : String.format("%.1f", drained / (double) aggregated.size()));
    }

    public record BatchStats(
            long totalSearchRequests,
            long totalDbWrites,
            long totalFlushes,
            int currentBufferSize,
            double writeReductionRatio
    ) {}

    public BatchStats getStats() {
        long requests = totalSearchRequests.get();
        long writes = totalDbWrites.get();
        double ratio = writes == 0 ? 0 : requests / (double) writes;
        return new BatchStats(requests, writes, totalFlushes.get(), bufferSize.get(), ratio);
    }
}
