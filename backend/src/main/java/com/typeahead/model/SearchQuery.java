package com.typeahead.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Durable record of a search query and its all-time popularity count.
 * Postgres is the source of truth; Redis only ever holds derived,
 * expendable cache data computed from this table.
 */
@Entity
@Table(
    name = "search_query",
    indexes = {
        // Powers prefix lookups (GET /suggest) on cache miss.
        // Combined with pg_trgm this can become a much faster index;
        // see V2__add_trigram_index.sql for the optional upgrade.
        @Index(name = "idx_query_lower", columnList = "queryLower")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SearchQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 512)
    private String query;

    // Lower-cased copy of `query`, maintained alongside it, so prefix
    // matching can be case-insensitive without LOWER() on every row at
    // query time.
    @Column(nullable = false, length = 512)
    private String queryLower;

    @Column(nullable = false)
    private Long count = 0L;

    @Column(nullable = false)
    private Instant lastSearchedAt;

    @Column(nullable = false)
    private Instant createdAt;

    public SearchQuery(String query, Long count) {
        this.query = query;
        this.queryLower = query.toLowerCase();
        this.count = count;
        this.lastSearchedAt = Instant.now();
        this.createdAt = Instant.now();
    }
}
