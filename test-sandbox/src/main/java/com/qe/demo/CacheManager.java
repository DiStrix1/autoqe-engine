package com.qe.demo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class CacheManager<K, V> {

    private final int maxCapacity;
    private final Map<K, V> cache;
    private int hits = 0;
    private int misses = 0;

    public CacheManager(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Cache capacity must be greater than zero");
        }
        this.maxCapacity = maxCapacity;
        this.cache = new LinkedHashMap<K, V>(maxCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > CacheManager.this.maxCapacity;
            }
        };
    }

    public synchronized void put(K key, V value) {
        Objects.requireNonNull(key, "Cache key cannot be null");
        Objects.requireNonNull(value, "Cache value cannot be null");
        cache.put(key, value);
    }

    public synchronized Optional<V> get(K key) {
        if (key == null || !cache.containsKey(key)) {
            misses++;
            return Optional.empty();
        }
        hits++;
        return Optional.of(cache.get(key));
    }

    public synchronized boolean contains(K key) {
        if (key == null) {
            return false;
        }
        return cache.containsKey(key);
    }

    public synchronized boolean remove(K key) {
        if (key == null) {
            return false;
        }
        return cache.remove(key) != null;
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void clear() {
        cache.clear();
        hits = 0;
        misses = 0;
    }

    public synchronized double getHitRatio() {
        int total = hits + misses;
        if (total == 0) {
            return 0.0;
        }
        return (double) hits / total;
    }
}
