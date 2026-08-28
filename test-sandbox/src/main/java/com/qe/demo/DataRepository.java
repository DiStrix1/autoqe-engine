package com.qe.demo;

import java.util.*;

public class DataRepository<T> {
    private final Map<String, T> storage = new HashMap<>();

    public void save(String id, T item) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("ID cannot be null or blank");
        }
        if (item == null) {
            throw new IllegalArgumentException("Item cannot be null");
        }
        storage.put(id, item);
    }

    public Optional<T> findById(String id) {
        return Optional.ofNullable(storage.get(id));
    }

    public List<T> findAll() {
        return new ArrayList<>(storage.values());
    }

    public boolean deleteById(String id) {
        return storage.remove(id) != null;
    }

    public void clear() {
        storage.clear();
    }

    public long count() {
        return storage.size();
    }
}