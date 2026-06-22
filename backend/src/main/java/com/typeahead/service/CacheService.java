package com.typeahead.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typeahead.dto.SuggestionDto;
import com.typeahead.util.ConsistentHashRing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;

/**
 * Distributed suggestion cache. A prefix like "iph" is hashed onto the
 * consistent hash ring to pick exactly one of the 3 Redis nodes; that
 * node alone is read/written for that prefix, so growing the cache
 * (adding nodes) only reshuffles a small slice of keys.
 *
 * Cache invalidation strategy:
 *  - Passive: every entry has a TTL (default 60s) so staleness is
 *    bounded even if nothing actively invalidates it.
 *  - Active: when a query's count changes meaningfully (see
 *    BatchWriterService), we explicitly delete the cached entries for
 *    every prefix of that query so the next read recomputes fresh data
 *    rather than waiting out the TTL.
 */
@Service
@RequiredArgsConstructor
public class CacheService {

    private static final String KEY_PREFIX = "sugg:";
    private static final int TTL_SECONDS = 60;

    private final Map<String, JedisPool> redisNodePools;
    private final ConsistentHashRing ring;
    private final ObjectMapper objectMapper;

    public record CacheLookup(String nodeId, boolean hit, List<SuggestionDto> suggestions) {}

    private String cacheKey(String prefix) {
        return KEY_PREFIX + prefix;
    }

    /** Which physical node owns this prefix, per the consistent hash ring. */
    public String ownerNode(String prefix) {
        return ring.getNode(cacheKey(prefix));
    }

    public CacheLookup get(String prefix) {
        String nodeId = ownerNode(prefix);
        JedisPool pool = redisNodePools.get(nodeId);
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(cacheKey(prefix));
            if (json == null) {
                return new CacheLookup(nodeId, false, null);
            }
            List<SuggestionDto> suggestions = List.of(objectMapper.readValue(json, SuggestionDto[].class));
            return new CacheLookup(nodeId, true, suggestions);
        } catch (Exception e) {
            // Cache failures must never break the request - fall back to
            // treating it as a miss so the caller reads from Postgres.
            return new CacheLookup(nodeId, false, null);
        }
    }

    public void put(String prefix, List<SuggestionDto> suggestions) {
        String nodeId = ownerNode(prefix);
        JedisPool pool = redisNodePools.get(nodeId);
        try (Jedis jedis = pool.getResource()) {
            String json = objectMapper.writeValueAsString(suggestions);
            jedis.setex(cacheKey(prefix), TTL_SECONDS, json);
        } catch (Exception ignored) {
            // Best-effort cache write; a failure here just means the next
            // read is also a miss, which is safe.
        }
    }

    /**
     * Invalidate the cached suggestion list for every prefix of `query`
     * (e.g. for "iphone": "i", "ip", "iph", ... "iphone"). Called after a
     * batch flush updates counts, so trending/popularity changes show up
     * without waiting for TTL expiry.
     */
    public void invalidatePrefixesOf(String query) {
        String lower = query.toLowerCase();
        for (int len = 1; len <= lower.length(); len++) {
            String prefix = lower.substring(0, len);
            String nodeId = ownerNode(prefix);
            JedisPool pool = redisNodePools.get(nodeId);
            try (Jedis jedis = pool.getResource()) {
                jedis.del(cacheKey(prefix));
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    public int ringSize() {
        return ring.ringSize();
    }
}
