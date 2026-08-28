package com.qe.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CacheManagerTest {

    private CacheManager<String, String> cache;

    @BeforeEach
    void setUp() {
        // Capacity limit of 3 entries
        cache = new CacheManager<>(3);
    }

    @Test
    @DisplayName("put and get successfully store and retrieve values")
    void testPutAndGet() {
        cache.put("k1", "v1");
        cache.put("k2", "v2");

        assertEquals(2, cache.size());
        assertTrue(cache.contains("k1"));

        Optional<String> val = cache.get("k1");
        assertTrue(val.isPresent());
        assertEquals("v1", val.get());
    }

    @Test
    @DisplayName("cache evicts eldest entry when capacity is exceeded (LRU)")
    void testLruEviction() {
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3");
        assertEquals(3, cache.size());

        // Access k1 so k2 becomes eldest
        cache.get("k1");

        // Insert k4 -> should evict k2
        cache.put("k4", "v4");

        assertEquals(3, cache.size());
        assertTrue(cache.contains("k1"));
        assertFalse(cache.contains("k2"));
        assertTrue(cache.contains("k3"));
        assertTrue(cache.contains("k4"));
    }

    @Test
    @DisplayName("remove deletes key from cache")
    void testRemove() {
        cache.put("k1", "val1");
        assertEquals(1, cache.size());

        boolean removed = cache.remove("k1");
        assertTrue(removed);
        assertEquals(0, cache.size());
        assertFalse(cache.contains("k1"));
    }

    @Test
    @DisplayName("getHitRatio computes exact ratio of hits vs total accesses")
    void testHitRatio() {
        cache.put("k1", "v1");
        cache.put("k2", "v2");

        // 2 hits, 2 misses -> total 4 accesses -> ratio 0.5
        cache.get("k1"); // Hit 1
        cache.get("k2"); // Hit 2
        cache.get("k3"); // Miss 1
        cache.get("k4"); // Miss 2

        assertEquals(0.5, cache.getHitRatio(), 0.001);
    }

    @Test
    @DisplayName("put throws NullPointerException when key or value is null")
    void testNullGuards() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "val"));
        assertThrows(NullPointerException.class, () -> cache.put("key", null));
    }
}
