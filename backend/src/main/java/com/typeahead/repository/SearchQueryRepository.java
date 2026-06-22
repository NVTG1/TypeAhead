package com.typeahead.repository;

import com.typeahead.model.SearchQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    Optional<SearchQuery> findByQueryLower(String queryLower);

    /**
     * Prefix match sorted by popularity, used on cache miss.
     * LIKE 'prefix%' can use a B-tree index in Postgres (left-anchored
     * pattern), unlike '%prefix%'. For larger datasets this is the query
     * that benefits most from a pg_trgm GIN index (see schema.sql notes).
     */
    @Query(value = """
        SELECT * FROM search_query
        WHERE query_lower LIKE CONCAT(:prefix, '%')
        ORDER BY count DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<SearchQuery> findTopByPrefix(@Param("prefix") String prefix, @Param("limit") int limit);

    @Query(value = "SELECT * FROM search_query ORDER BY count DESC LIMIT :limit", nativeQuery = true)
    List<SearchQuery> findTopOverall(@Param("limit") int limit);

    /**
     * Single-row upsert used as a fallback path (e.g. for an immediate,
     * non-batched write in tests). The batch writer uses
     * {@link #bulkIncrement} instead for real traffic.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO search_query (query, query_lower, count, last_searched_at, created_at)
        VALUES (:query, :queryLower, :increment, :now, :now)
        ON CONFLICT (query) DO UPDATE
          SET count = search_query.count + :increment,
              last_searched_at = :now
        """, nativeQuery = true)
    void upsertIncrement(@Param("query") String query,
                          @Param("queryLower") String queryLower,
                          @Param("increment") long increment,
                          @Param("now") Instant now);

    long countByQueryLowerStartingWith(String prefix);
}
