package com.typeahead.config;

import com.typeahead.util.ConsistentHashRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires up connections to the 3 physical Redis nodes that make up the
 * distributed cache layer, and builds the consistent hash ring used to
 * route prefix keys to nodes.
 *
 * Node addresses come from application.yml (cache.redis-nodes), which in
 * docker-compose.yml point at 3 independent Redis containers
 * (redis-node-1, redis-node-2, redis-node-3) - i.e. these are genuinely
 * separate processes/instances, not one Redis with simulated sharding.
 */
@Configuration
public class RedisClusterConfig {

    @Value("#{'${cache.redis-nodes}'.split(',')}")
    private List<String> redisNodeAddresses; // e.g. "redis-node-1:6379,redis-node-2:6379,redis-node-3:6379"

    /**
     * Map of logical node id ("redis-node-1") -> live JedisPool for that node.
     * Logical ids are what the consistent hash ring deals in; this map is
     * how a logical id gets turned into an actual connection.
     */
    @Bean
    public Map<String, JedisPool> redisNodePools() {
        Map<String, JedisPool> pools = new LinkedHashMap<>();
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);

        int nodeNumber = 1;
        for (String address : redisNodeAddresses) {
            String[] parts = address.trim().split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            String nodeId = "redis-node-" + nodeNumber;
            pools.put(nodeId, new JedisPool(poolConfig, host, port));
            nodeNumber++;
        }
        return pools;
    }

    @Bean
    public ConsistentHashRing consistentHashRing(Map<String, JedisPool> redisNodePools) {
        return new ConsistentHashRing(List.copyOf(redisNodePools.keySet()));
    }
}
