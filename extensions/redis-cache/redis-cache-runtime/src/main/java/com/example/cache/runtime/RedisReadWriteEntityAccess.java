package com.example.cache.runtime;

import org.hibernate.cache.cfg.spi.EntityDataCachingConfig;
import org.hibernate.cache.spi.CacheKeysFactory;
import org.hibernate.cache.spi.DomainDataRegion;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.cache.spi.support.EntityNonStrictReadWriteAccess;

/**
 * Entity access for regions annotated {@code READ_WRITE}.
 *
 * <p>Inherits Hibernate's non-strict behaviour: loads fill the cache through
 * {@code putFromLoad}, inserts and updates invalidate through
 * {@code removeFromCache}. The access type is reported as {@code READ_WRITE}
 * so startup logs and statistics match the annotation.
 */
public class RedisReadWriteEntityAccess extends EntityNonStrictReadWriteAccess {

    public RedisReadWriteEntityAccess(DomainDataRegion region,
                                      CacheKeysFactory keysFactory,
                                      DomainDataStorageAccess storageAccess,
                                      EntityDataCachingConfig config) {
        super(region, keysFactory, storageAccess, config);
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.READ_WRITE;
    }
}
