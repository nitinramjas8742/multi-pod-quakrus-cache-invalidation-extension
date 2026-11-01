package com.example.cache.runtime;

import org.hibernate.cache.cfg.spi.CollectionDataCachingConfig;
import org.hibernate.cache.spi.CacheKeysFactory;
import org.hibernate.cache.spi.DomainDataRegion;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cache.spi.support.CollectionNonStrictReadWriteAccess;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;

/**
 * Collection access for regions annotated {@code READ_WRITE}. See
 * {@link RedisReadWriteEntityAccess} for the rationale.
 */
public class RedisReadWriteCollectionAccess extends CollectionNonStrictReadWriteAccess {

    public RedisReadWriteCollectionAccess(DomainDataRegion region,
                                          CacheKeysFactory keysFactory,
                                          DomainDataStorageAccess storageAccess,
                                          CollectionDataCachingConfig config) {
        super(region, keysFactory, storageAccess, config);
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.READ_WRITE;
    }
}
