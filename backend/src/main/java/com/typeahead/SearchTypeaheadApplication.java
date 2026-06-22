package com.typeahead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HLD101 Assignment: Search Typeahead System.
 *
 * Architecture summary (see README for full detail):
 *  - Postgres: durable store of (query, count, last_searched_at)
 *  - Redis (3 real nodes): distributed cache of suggestion results,
 *    keyed by prefix, node selection done via consistent hashing
 *  - In-memory buffer + scheduled flusher: batches search-submission
 *    writes instead of hitting Postgres synchronously per request
 *  - Redis sorted sets (per node, same ring): sliding-window recent
 *    activity counters used for recency-aware trending
 */
@SpringBootApplication
@EnableScheduling
public class SearchTypeaheadApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchTypeaheadApplication.class, args);
    }
}
