package com.example.cache.runtime;

import org.hibernate.cache.spi.support.StorageAccess;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain in-memory storage for Hibernate's query-results and timestamps
 * regions.
 *
 * <p>The Redis-versioned access is only meaningful for domain data (entities
 * and collections). Query results and Hibernate's internal query timestamps
 * have different invalidation semantics: a query-cache entry is invalidated
 * by a whole table/region change, and the timestamps region stores Hibernate's
 * own timestamp values. Wrapping those in the per-key version protocol would
 * corrupt their meaning.
 *
 * <p>Query caching is not enabled in this project, so these regions are
 * normally unused. If you enable it later, replace this with a proper
 * clustered query cache and broadcast region invalidations.
 */
public class LocalStorageAccess implements StorageAccess {

    private final ConcurrentHashMap<Object, Object> store = new ConcurrentHashMap<>();

    public LocalStorageAccess() {
        // Region name is not needed for a local map, but keep the constructor
        // symmetric with the versioned access used for domain data.
    }

    @Override
    public Object getFromCache(Object key, SharedSessionContractImplementor session) {
        return store.get(key);
    }

    @Override
    public void putIntoCache(Object key, Object value, SharedSessionContractImplementor session) {
        store.put(key, value);
    }

    @Override
    public boolean contains(Object key) {
        return store.containsKey(key);
    }

    @Override
    public void evictData() {
        store.clear();
    }

    @Override
    public void evictData(Object key) {
        store.remove(key);
    }

    @Override
    public void release() {
        store.clear();
    }
}
