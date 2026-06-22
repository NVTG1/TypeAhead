package com.typeahead.controller;

import com.typeahead.dto.CacheDebugResponse;
import com.typeahead.service.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import redis.clients.jedis.JedisPool;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CacheDebugController {

    private final CacheService cacheService;
    private final Map<String, JedisPool> redisNodePools;

    /**
     * GET /cache/debug?prefix=<prefix>
     * Shows which physical Redis node consistent hashing assigned this
     * prefix to, and whether it's currently a cache hit - useful for
     * demonstrating the routing logic live in the viva.
     */
    @GetMapping("/cache/debug")
    public CacheDebugResponse debug(@RequestParam String prefix) {
        String normalized = prefix.trim().toLowerCase();
        CacheService.CacheLookup lookup = cacheService.get(normalized);
        return new CacheDebugResponse(
                normalized,
                lookup.nodeId(),
                lookup.hit(),
                cacheService.ringSize(),
                redisNodePools.size()
        );
    }
}
