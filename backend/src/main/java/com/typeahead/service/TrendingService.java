package com.typeahead.service;

import com.typeahead.util.ConsistentHashRing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ZAddParams;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tracks recent search activity to power recency-aware trending (the +20%
 * "enhanced ranking" requirement), separately from the all-time `count`
 * stored in Postgres.
 *
 * Design (sliding window via per-minute Redis sorted sets):
 *  - On each search, we increment a ZSET member's score in a bucket key
 *    like "trend:2026-06-20T14:32" (current minute), where the member is
 *    the query and the score is how many times it was searched in that
 *    minute.
 *  - Each bucket key gets a short TTL (WINDOW_MINUTES + 1 minutes), so old
 *    buckets disappear on their own - this is what stops a query that
 *    spiked an hour ago from being "stuck" at the top forever. No manual
 *    cleanup job needed.
 *  - To compute "recent activity" for a query, we sum its score across the
 *    last WINDOW_MINUTES bucket keys (a small, bounded number of ZSCORE
 *    calls, not a scan over all history).
 *
 * Final ranking score (used by the enhanced /suggest path):
 *     score = log10(1 + allTimeCount) + RECENCY_WEIGHT * recentCount
 *
 * Why log on the historical term: without it, a query with 1,000,000
 * all-time searches would always bury a genuinely trending new query no
 * matter how hot it is right now, since recentCount realistically maxes
 * out in the hundreds/thousands during a demo. Taking log10 compresses
 * the historical popularity into a comparable range to the recency term,
 * so a real recent surge can actually move the ranking - while still
 * giving long-term-popular queries a meaningful baseline boost over
 * obscure ones. RECENCY_WEIGHT is a tunable knob; we picked 2.0 so that
 * ~20-30 recent searches measurably outranks a one-decade-of-history gap.
 *
 * Trade-off discussed in the README: this is an approximate, last-N-minute
 * window, not an exact decay function (e.g. exponential time decay). It's
 * O(WINDOW_MINUTES) Redis calls per scored query, cheap and easy to reason
 * about for a viva, at the cost of being a step-function rather than a
 * smooth decay.
 */
@Service
@RequiredArgsConstructor
public class TrendingService {

    private static final int WINDOW_MINUTES = 10;
    private static final double RECENCY_WEIGHT = 2.0;
    private static final java.time.format.DateTimeFormatter BUCKET_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final Map<String, JedisPool> redisNodePools;
    private final ConsistentHashRing ring;

    private String bucketKey(java.time.Instant instant) {
        String minute = BUCKET_FMT.withZone(java.time.ZoneOffset.UTC).format(instant);
        return "trend:" + minute;
    }

    /** Record one occurrence of `query` as a recent-activity event. */
    public void recordActivity(String query) {
        String lower = query.toLowerCase();
        String key = bucketKey(java.time.Instant.now());
        String nodeId = ring.getNode(key);
        JedisPool pool = redisNodePools.get(nodeId);
        try (Jedis jedis = pool.getResource()) {
            jedis.zincrby(key, 1.0, lower);
            jedis.expire(key, TimeUnit.MINUTES.toSeconds(WINDOW_MINUTES + 1));
        } catch (Exception ignored) {
            // trending is a best-effort enhancement; never break the write path
        }
    }

    /**
     * Sum of recent-activity score for `query` across the sliding window.
     * Bounded cost: at most WINDOW_MINUTES Redis round trips.
     */
    public double recentActivityScore(String queryLower) {
        double total = 0;
        java.time.Instant now = java.time.Instant.now();
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            String key = bucketKey(now.minusSeconds(60L * i));
            String nodeId = ring.getNode(key);
            JedisPool pool = redisNodePools.get(nodeId);
            try (Jedis jedis = pool.getResource()) {
                Double score = jedis.zscore(key, queryLower);
                if (score != null) total += score;
            } catch (Exception ignored) {
                // skip unreachable node, degrade gracefully
            }
        }
        return total;
    }

    /** Blended score combining all-time popularity with recent activity. */
    public double blendedScore(long allTimeCount, String queryLower) {
        double recent = recentActivityScore(queryLower);
        return Math.log10(1 + allTimeCount) + RECENCY_WEIGHT * recent;
    }
}
