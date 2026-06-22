package com.typeahead.service;

import com.typeahead.dto.SuggestResponse;
import com.typeahead.dto.SuggestionDto;
import com.typeahead.model.SearchQuery;
import com.typeahead.repository.SearchQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core read path for GET /suggest.
 *
 * Flow: cache lookup -> on hit, return immediately -> on miss, query
 * Postgres for the top prefix matches, optionally re-rank with the
 * recency-aware TrendingService, write the result back into the cache,
 * then return it.
 *
 * Basic vs enhanced ranking (assignment section 7) is controlled by the
 * `enhanced` query param on /suggest, so both behaviors live behind the
 * same endpoint as required, and can be demonstrated side by side.
 */
@Service
@RequiredArgsConstructor
public class SuggestionService {

    private static final int MAX_SUGGESTIONS = 10;
    // Pull a slightly larger candidate set from Postgres than we return,
    // so that re-ranking by recency (which can reorder beyond pure count
    // order) still has enough candidates to choose the true top 10 from.
    private static final int CANDIDATE_POOL_SIZE = 30;

    private final SearchQueryRepository repository;
    private final CacheService cacheService;
    private final TrendingService trendingService;

    public SuggestResponse suggest(String rawPrefix, boolean enhanced) {
        long start = System.nanoTime();

        if (rawPrefix == null || rawPrefix.isBlank()) {
            return new SuggestResponse(rawPrefix, enhanced, false, null, elapsedMs(start), List.of());
        }

        String prefix = rawPrefix.trim().toLowerCase();

        // Basic-ranking results are cacheable as-is (count order is
        // stable between writes). Enhanced results depend on a
        // constantly-moving recency score, so we cache the *candidate*
        // popularity-ordered list and re-rank on every read; the cache
        // still saves the Postgres round trip, which is the expensive
        // part.
        CacheService.CacheLookup lookup = cacheService.get(prefix);

        List<SuggestionDto> candidates;
        boolean cacheHit = lookup.hit();
        if (cacheHit) {
            candidates = lookup.suggestions();
        } else {
            String escapedPrefix = escapeLikeWildcards(prefix);
            List<SearchQuery> rows = repository.findTopByPrefix(escapedPrefix, CANDIDATE_POOL_SIZE);
            candidates = rows.stream()
                    .map(r -> new SuggestionDto(r.getQuery(), r.getCount(), r.getCount()))
                    .collect(Collectors.toList());
            cacheService.put(prefix, candidates);
        }

        List<SuggestionDto> result;
        if (enhanced) {
            result = candidates.stream()
                    .map(c -> new SuggestionDto(
                            c.query(),
                            c.count(),
                            trendingService.blendedScore(c.count(), c.query().toLowerCase())))
                    .sorted(Comparator.comparingDouble(SuggestionDto::score).reversed())
                    .limit(MAX_SUGGESTIONS)
                    .collect(Collectors.toList());
        } else {
            result = candidates.stream()
                    .sorted(Comparator.comparingLong(SuggestionDto::count).reversed())
                    .limit(MAX_SUGGESTIONS)
                    .collect(Collectors.toList());
        }

        return new SuggestResponse(rawPrefix, enhanced, cacheHit, lookup.nodeId(), elapsedMs(start), result);
    }

    public List<SuggestionDto> trending(boolean enhanced, int limit) {
        List<SearchQuery> rows = repository.findTopOverall(100);
        return rows.stream()
                .map(r -> {
                    double score = enhanced
                            ? trendingService.blendedScore(r.getCount(), r.getQueryLower())
                            : r.getCount();
                    return new SuggestionDto(r.getQuery(), r.getCount(), score);
                })
                .sorted(Comparator.comparingDouble(SuggestionDto::score).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    private double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    /**
     * Escapes Postgres LIKE wildcard characters (% and _) plus the escape
     * character itself, so a user typing a literal "%" or "_" in their
     * search (e.g. "50% off", "file_name") is matched literally rather
     * than being interpreted as a SQL wildcard. Paired with
     * "LIKE ... ESCAPE '\'" would be the fully strict form; here we rely
     * on Postgres' default escape character backslash being usable as-is
     * since the column isn't using a custom ESCAPE clause - to keep this
     * watertight we escape backslash too.
     */
    private String escapeLikeWildcards(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
