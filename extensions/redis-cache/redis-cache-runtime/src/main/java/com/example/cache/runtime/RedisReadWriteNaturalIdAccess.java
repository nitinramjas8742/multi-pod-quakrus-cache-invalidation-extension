package com.example.cache.runtime;

import org.hibernate.cache.cfg.spi.NaturalIdDataCachingConfig;
import org.hibernate.cache.spi.CacheKeysFactory;
import org.hibernate.cache.spi.DomainDataRegion;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.cache.spi.support.NaturalIdNonStrictReadWriteAccess;

/**
 * Natural-id access for regions annotated {@code READ_WRITE}. See
 * {@link RedisReadWriteEntityAccess} for the rationale.
 */
public class RedisReadWriteNaturalIdAccess extends NaturalIdNonStrictReadWriteAccess {

    public RedisReadWriteNaturalIdAccess(DomainDataRegion region,
                                         CacheKeysFactory keysFactory,
                                         DomainDataStorageAccess storageAccess,
                                         NaturalIdDataCachingConfig config) {
        super(region, keysFactory, storageAccess, config);
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.READ_WRITE;
    }
}
