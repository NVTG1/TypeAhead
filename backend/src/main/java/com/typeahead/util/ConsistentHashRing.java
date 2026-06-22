package com.typeahead.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Consistent hashing ring used to decide which Redis cache node owns a given
 * prefix key.
 *
 * Why consistent hashing instead of simple modulo (hash(key) % numNodes):
 *   - With modulo hashing, adding/removing a node remaps almost every key
 *     (k % 3 vs k % 4 agree on very few keys), causing a near-total cache
 *     wipe and a thundering herd back to Postgres.
 *   - With consistent hashing, adding/removing a node only remaps the
 *     keys that fall in the affected arc of the ring — roughly 1/N of
 *     all keys, not all of them.
 *
 * Virtual nodes:
 *   Each physical node is hashed multiple times (with a suffix #0, #1, ...)
 *   onto the ring. Without virtual nodes, 3 physical nodes can land on the
 *   ring unevenly (e.g. node A might own 80% of the ring by chance). With
 *   ~150 virtual nodes per physical node, load spreads near-uniformly
 *   because the law of large numbers smooths out the random placement.
 *
 * The ring is a TreeMap<hash, physicalNodeId>. To find the owner of a key:
 *   1. Hash the key.
 *   2. Find the first virtual-node hash >= key's hash (tailMap().firstKey()).
 *   3. Wrap around to the smallest hash in the ring if none is found
 *      (the ring is circular).
 */
public class ConsistentHashRing {

    private static final int VIRTUAL_NODES_PER_PHYSICAL_NODE = 150;

    // hash -> physical node id (e.g. "redis-node-1")
    private final SortedMap<Long, String> ring = new ConcurrentSkipListMap<>();

    public ConsistentHashRing(List<String> physicalNodeIds) {
        for (String nodeId : physicalNodeIds) {
            addNode(nodeId);
        }
    }

    public void addNode(String nodeId) {
        for (int i = 0; i < VIRTUAL_NODES_PER_PHYSICAL_NODE; i++) {
            long hash = hash(nodeId + "#VN" + i);
            ring.put(hash, nodeId);
        }
    }

    public void removeNode(String nodeId) {
        for (int i = 0; i < VIRTUAL_NODES_PER_PHYSICAL_NODE; i++) {
            long hash = hash(nodeId + "#VN" + i);
            ring.remove(hash);
        }
    }

    /**
     * Returns the physical node id responsible for the given key.
     */
    public String getNode(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("Consistent hash ring has no nodes");
        }
        long hash = hash(key);
        SortedMap<Long, String> tail = ring.tailMap(hash);
        Long targetHash = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(targetHash);
    }

    /** Exposes ring size (virtual nodes) mainly for debug/demo endpoints. */
    public int ringSize() {
        return ring.size();
    }

    /**
     * MD5-based 64-bit hash. MD5 is used purely as a fast, well-distributed
     * hash function here (NOT for any security/cryptographic purpose) -
     * this is the standard textbook choice for consistent hashing demos.
     */
    private long hash(String key) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(key.getBytes());
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (digest[i] & 0xFF);
            }
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
