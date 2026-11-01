package com.example.cache.runtime;

import org.hibernate.cache.cfg.spi.CollectionDataCachingConfig;
import org.hibernate.cache.cfg.spi.DomainDataRegionBuildingContext;
import org.hibernate.cache.cfg.spi.DomainDataRegionConfig;
import org.hibernate.cache.cfg.spi.EntityDataCachingConfig;
import org.hibernate.cache.cfg.spi.NaturalIdDataCachingConfig;
import org.hibernate.cache.spi.CacheKeysFactory;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cache.spi.access.CollectionDataAccess;
import org.hibernate.cache.spi.access.EntityDataAccess;
import org.hibernate.cache.spi.access.NaturalIdDataAccess;
import org.hibernate.cache.spi.support.DomainDataRegionImpl;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.cache.spi.support.RegionFactoryTemplate;

/**
 * Region that accepts {@code READ_WRITE} annotations but serves them with the
 * Redis version/epoch invalidation protocol.
 *
 * <p>Hibernate's own {@code READ_WRITE} access stores a lockable wrapper in the
 * cache and mutates it through {@code putIntoCache} on every
 * {@code lockItem}/{@code unlockItem}. Those soft locks only work when the
 * cache is a single shared store. Here the value cache is per-pod, so the lock
 * list on one pod is invisible to the others.
 *
 * <p>Instead this region reuses Hibernate's non-strict access classes, which
 * read through {@link DomainDataStorageAccess#getFromCache}, fill through
 * {@link DomainDataStorageAccess#putFromLoad}, and invalidate through
 * {@link DomainDataStorageAccess#removeFromCache}. That is exactly the
 * protocol implemented by {@link RedisVersionedStorageAccess}: a write bumps a
 * Redis version counter, so the next read on any pod evicts the stale local
 * entry and reloads from the database.
 *
 * <p>Trade-off: the annotation reads {@code READ_WRITE}, but the guarantee is
 * "no stale value survives the next read", not Hibernate's strict
 * cluster-wide soft locking. Concurrent writers are still serialised by the
 * database, not by the cache.
 */
public class RedisDomainDataRegion extends DomainDataRegionImpl {

    public RedisDomainDataRegion(DomainDataRegionConfig regionConfig,
                                 RegionFactoryTemplate regionFactory,
                                 DomainDataStorageAccess storageAccess,
                                 CacheKeysFactory keysFactory,
                                 DomainDataRegionBuildingContext buildingContext) {
        super(regionConfig, regionFactory, storageAccess, keysFactory, buildingContext);
    }

    @Override
    protected EntityDataAccess generateReadWriteEntityAccess(EntityDataCachingConfig config) {
        return new RedisReadWriteEntityAccess(
                this, getEffectiveKeysFactory(), getCacheStorageAccess(), config);
    }

    @Override
    protected NaturalIdDataAccess generateReadWriteNaturalIdAccess(NaturalIdDataCachingConfig config) {
        return new RedisReadWriteNaturalIdAccess(
                this, getEffectiveKeysFactory(), getCacheStorageAccess(), config);
    }

    @Override
    public CollectionDataAccess generateCollectionAccess(CollectionDataCachingConfig config) {
        if (config.getAccessType() == AccessType.READ_WRITE) {
            return new RedisReadWriteCollectionAccess(
                    this, getEffectiveKeysFactory(), getCacheStorageAccess(), config);
        }
        return super.generateCollectionAccess(config);
    }
}
