package com.example.cache.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import org.hibernate.cache.internal.CacheKeyImplementation;
import org.hibernate.cache.internal.NaturalIdCacheKey;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local near-cache with Redis-backed invalidation.
 *
 * <p>Protocol:
 * <ul>
 *   <li>Each cache key has a monotonically increasing Redis version
 *       ({@code l2c:<region>:v:<tenant>:<key>}).</li>
 *   <li>Each region has a monotonically increasing epoch
 *       ({@code l2c:<region>:e}), used for region-wide invalidations.</li>
 *   <li>A write/remove/evict increments the key version.</li>
 *   <li>A region clear increments the region epoch.</li>
 *   <li>A read compares the local entry's (epoch, version) with Redis.
 *       Any mismatch removes the local entry; Hibernate then reloads from
 *       the database.</li>
 * </ul>
 *
 * <p>The tenant identifier is part of the per-key version name. Hibernate's
 * cache key {@code toString()} is only {@code Entity#id} (the tenant is in
 * {@code equals}/{@code hashCode} but not in the text), so without this the
 * tenants would share one counter and invalidate each other's entries.
 *
 * <p>The version is captured on a cache miss <em>before</em> Hibernate reads
 * the database, and is stored with the value in {@link #putFromLoad}. This
 * closes the usual "load finishes after an invalidation" race: an entry
 * loaded from a stale database snapshot keeps the old version and is evicted
 * on the next read.
 *
 * <p>Redis failures are fail-closed: the local entry is discarded and the
 * read falls back to the database. This avoids serving indefinitely stale
 * data when Redis is unavailable.
 */
public class RedisVersionedStorageAccess implements DomainDataStorageAccess {

    private static final Logger LOG = Logger.getLogger(RedisVersionedStorageAccess.class);

    /** Bound on local cache memory; tune for your entity count. */
    private static final long MAX_LOCAL_ENTRIES = 10_000L;
    private static final Duration LOCAL_EXPIRY = Duration.ofMinutes(10);

    /** Redis version keys are disposable; TTL prevents unbounded growth. */
    private static final Duration REDIS_KEY_TTL = Duration.ofHours(1);

    /** Bound on in-flight load versions tracked per thread. */
    private static final int MAX_PENDING_LOADS = 1_024;

    private record VersionState(long epoch, long version) {
    }

    private record CacheEntry(Object value, long epoch, long version) {
    }

    private final String regionName;

    private final Cache<Object, CacheEntry> localCache = Caffeine.newBuilder()
            .maximumSize(MAX_LOCAL_ENTRIES)
            .expireAfterWrite(LOCAL_EXPIRY)
            .build();

    /**
     * Version captured at a cache miss, consumed by putFromLoad on the same
     * thread. Bounded to avoid leaking entries when an entity is not found
     * or the load fails before putFromLoad is called.
     */
    private final AtomicLong nextFailureWarningNanos = new AtomicLong();

    /**
     * Avoid flooding the log once per entity/collection during a Redis outage.
     * One warning per 10 seconds per region is enough to make the outage
     * visible without hiding the actual request logs.
     */
    private void logRedisFailure(String format, Object... args) {
        long now = System.nanoTime();
        long next = nextFailureWarningNanos.get();
        if (now >= next
                && nextFailureWarningNanos.compareAndSet(next, now + TimeUnit.SECONDS.toNanos(10))) {
            LOG.warnf(format, args);
        } else {
            LOG.debugf(format, args);
        }
    }

    private final ThreadLocal<Map<Object, VersionState>> pendingLoads = ThreadLocal.withInitial(
            () -> new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, VersionState> eldest) {
                    return size() > MAX_PENDING_LOADS;
                }
            });

    public RedisVersionedStorageAccess(String regionName) {
        this.regionName = regionName;
    }

    private ValueCommands<String, String> getValueCommands() {
        RedisCacheService service = RedisCacheService.INSTANCE;
        if (service == null) {
            try {
                if (Arc.container() != null) {
                    InstanceHandle<RedisCacheService> handle = Arc.container().instance(RedisCacheService.class);
                    if (handle.isAvailable()) {
                        service = handle.get();
                    }
                }
            } catch (Exception e) {
                LOG.debugf("RedisCacheService not available yet: %s", e.getMessage());
            }
        }
        return service == null ? null : service.getCommands();
    }

    private KeyCommands<String> getKeyCommands() {
        RedisCacheService service = RedisCacheService.INSTANCE;
        if (service == null) {
            try {
                if (Arc.container() != null) {
                    InstanceHandle<RedisCacheService> handle = Arc.container().instance(RedisCacheService.class);
                    if (handle.isAvailable()) {
                        service = handle.get();
                    }
                }
            } catch (Exception e) {
                LOG.debugf("RedisCacheService not available yet: %s", e.getMessage());
            }
        }
        return service == null ? null : service.getKeyCommands();
    }

    // Package-private so the key format can be asserted without a running app.
    String versionKey(Object key) {
        String tenant = tenantId(key);
        String keyPart = String.valueOf(key);
        return "l2c:" + regionName + ":v:" + (tenant == null ? keyPart : tenant + ":" + keyPart);
    }

    /**
     * Tenant identifier carried by Hibernate's cache key, or {@code null} when
     * the session factory is not multi-tenant.
     *
     * <p>Only some key implementations expose it: entity/collection keys use
     * {@link CacheKeyImplementation}, natural-id keys use
     * {@link NaturalIdCacheKey}, and both omit the tenant from
     * {@code toString()}. {@code BasicCacheKeyImplementation} is used only when
     * the tenant identifier is already {@code null}.
     */
    private static String tenantId(Object key) {
        String tenant = null;
        if (key instanceof CacheKeyImplementation cacheKey) {
            tenant = cacheKey.getTenantId();
        } else if (key instanceof NaturalIdCacheKey naturalIdKey) {
            tenant = naturalIdKey.getTenantId();
        }
        return tenant == null || tenant.isBlank() ? null : tenant;
    }

    private String epochKey() {
        return "l2c:" + regionName + ":e";
    }

    /**
     * One Redis round trip for both the key version and the region epoch.
     *
     * @return null if Redis is unavailable; callers must fail closed.
     */
    private VersionState readState(Object key) {
        ValueCommands<String, String> commands = getValueCommands();
        if (commands == null) {
            return null;
        }
        try {
            String versionKey = versionKey(key);
            String epochKey = epochKey();
            Map<String, String> values = commands.mget(versionKey, epochKey);
            if (values == null) {
                return null;
            }
            long epoch = parse(values.get(epochKey));
            long version = parse(values.get(versionKey));
            if (epoch < 0 || version < 0) {
                // Corrupt value: fail closed rather than trusting a local entry.
                return null;
            }
            return new VersionState(epoch, version);
        } catch (Exception e) {
            logRedisFailure("Failed to read L2 version state from Redis for region [%s] key [%s]: %s",
                    regionName, key, e.getMessage());
            return null;
        }
    }

    /**
     * @return the parsed value, 0 for a missing key, or -1 for a corrupt value.
     */
    private static long parse(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private void incrementKeyVersion(Object key) {
        ValueCommands<String, String> commands = getValueCommands();
        if (commands == null) {
            logRedisFailure("Cannot invalidate region [%s] key [%s]: Redis is unavailable", regionName, key);
            return;
        }
        try {
            String versionKey = versionKey(key);
            commands.incr(versionKey);
            KeyCommands<String> keys = getKeyCommands();
            if (keys != null) {
                keys.expire(versionKey, REDIS_KEY_TTL);
            }
            LOG.debugf("L2C [%s] invalidated key [%s]", regionName, key);
        } catch (Exception e) {
            logRedisFailure("Failed to invalidate region [%s] key [%s] in Redis: %s",
                    regionName, key, e.getMessage());
        }
    }

    private void incrementRegionEpoch() {
        ValueCommands<String, String> commands = getValueCommands();
        if (commands == null) {
            logRedisFailure("Cannot clear region [%s]: Redis is unavailable", regionName);
            return;
        }
        try {
            String epochKey = epochKey();
            commands.incr(epochKey);
            KeyCommands<String> keys = getKeyCommands();
            if (keys != null) {
                keys.expire(epochKey, REDIS_KEY_TTL);
            }
            LOG.debugf("L2C [%s] region epoch incremented", regionName);
        } catch (Exception e) {
            logRedisFailure("Failed to clear region [%s] in Redis: %s", regionName, e.getMessage());
        }
    }

    @Override
    public Object getFromCache(Object key, SharedSessionContractImplementor session) {
        VersionState state = readState(key);
        if (state == null) {
            // Fail closed: without Redis we cannot prove the entry is fresh.
            localCache.invalidate(key);
            pendingLoads.get().remove(key);
            LOG.debugf("L2C [%s] Redis unavailable; bypassing cache for key [%s]", regionName, key);
            return null;
        }

        CacheEntry entry = localCache.getIfPresent(key);
        if (entry == null) {
            pendingLoads.get().put(key, state);
            LOG.debugf("L2C [%s] MISS key [%s] at epoch %d version %d",
                    regionName, key, state.epoch(), state.version());
            return null;
        }

        if (entry.epoch() != state.epoch() || entry.version() != state.version()) {
            localCache.invalidate(key);
            pendingLoads.get().put(key, state);
            LOG.debugf(">>> L2C [%s] STALE key [%s] (local epoch/version %d/%d, redis %d/%d). Evicted. <<<",
                    regionName, key, entry.epoch(), entry.version(), state.epoch(), state.version());
            return null;
        }

        pendingLoads.get().remove(key);
        LOG.debugf(">>> L2C [%s] HIT key [%s] <<<", regionName, key);
        return entry.value();
    }

    @Override
    public void putFromLoad(Object key, Object value, SharedSessionContractImplementor session) {
        // Prefer the version captured before Hibernate read the database.
        VersionState state = pendingLoads.get().remove(key);
        if (state == null) {
            // Some load paths may not call getFromCache first; fall back to
            // the current version. This is safe but has a wider race window.
            state = readState(key);
        }
        if (state == null) {
            LOG.debugf("L2C [%s] not caching key [%s]: Redis unavailable", regionName, key);
            return;
        }
        localCache.put(key, new CacheEntry(value, state.epoch(), state.version()));
        LOG.debugf(">>> L2C [%s] PUT_FROM_LOAD key [%s] epoch %d version %d <<<",
                regionName, key, state.epoch(), state.version());
    }

    @Override
    public void putIntoCache(Object key, Object value, SharedSessionContractImplementor session) {
        // READ_WRITE and NONSTRICT_READ_WRITE regions are mapped to access
        // classes that load through putFromLoad and invalidate through
        // removeFromCache, so this should not be called for them. It can still
        // be reached by a region whose strategy is not mapped (for example a
        // misconfigured READ_ONLY). Treating it as an invalidation guarantees
        // that a value which never went through the version accounting is
        // never served from the local cache.
        removeFromCache(key, session);
    }

    @Override
    public void removeFromCache(Object key, SharedSessionContractImplementor session) {
        localCache.invalidate(key);
        pendingLoads.get().remove(key);
        incrementKeyVersion(key);
        LOG.debugf(">>> L2C [%s] REMOVE key [%s] (broadcast to Redis) <<<", regionName, key);
    }

    @Override
    public boolean contains(Object key) {
        VersionState state = readState(key);
        if (state == null) {
            localCache.invalidate(key);
            return false;
        }
        CacheEntry entry = localCache.getIfPresent(key);
        if (entry == null) {
            return false;
        }
        if (entry.epoch() != state.epoch() || entry.version() != state.version()) {
            localCache.invalidate(key);
            return false;
        }
        return true;
    }

    @Override
    public void evictData(Object key) {
        localCache.invalidate(key);
        pendingLoads.get().remove(key);
        incrementKeyVersion(key);
        LOG.debugf(">>> L2C [%s] EVICT key [%s] (broadcast to Redis) <<<", regionName, key);
    }

    @Override
    public void evictData() {
        localCache.invalidateAll();
        pendingLoads.get().clear();
        incrementRegionEpoch();
        LOG.debugf(">>> L2C [%s] REGION CLEARED (broadcast to Redis) <<<", regionName);
    }

    @Override
    public void release() {
        localCache.invalidateAll();
        pendingLoads.remove();
    }
}
