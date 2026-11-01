package com.example.cache.runtime;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.cfg.spi.DomainDataRegionBuildingContext;
import org.hibernate.cache.cfg.spi.DomainDataRegionConfig;
import org.hibernate.cache.spi.DomainDataRegion;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.cache.spi.support.RegionFactoryTemplate;
import org.hibernate.cache.spi.support.StorageAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jboss.logging.Logger;

import java.util.Map;

public class MyCustomRegionFactory extends RegionFactoryTemplate {

    private static final Logger LOG = Logger.getLogger(MyCustomRegionFactory.class);

    @Override
    protected void prepareForUse(SessionFactoryOptions options, Map<String, Object> configValues) {
        LOG.info(">>> Initializing MyCustomRegionFactory with Redis version/epoch invalidation <<<");
        LOG.info(">>> READ_WRITE and NONSTRICT_READ_WRITE regions both use Redis version/epoch invalidation <<<");
    }

    @Override
    protected void releaseFromUse() {
        LOG.info("Releasing MyCustomRegionFactory");
    }

    /**
     * Replaces the default region so that {@code READ_WRITE} annotations get an
     * access implementation that fits a per-pod value cache. Hibernate's stock
     * read-write access relies on a shared soft-lock store, which does not
     * exist here; see {@link RedisDomainDataRegion}.
     */
    @Override
    public DomainDataRegion buildDomainDataRegion(
            DomainDataRegionConfig regionConfig,
            DomainDataRegionBuildingContext buildingContext) {
        verifyStarted();
        return new RedisDomainDataRegion(
                regionConfig,
                this,
                createDomainDataStorageAccess(regionConfig, buildingContext),
                getImplicitCacheKeysFactory(),
                buildingContext);
    }

    @Override
    protected DomainDataStorageAccess createDomainDataStorageAccess(
            DomainDataRegionConfig regionConfig,
            DomainDataRegionBuildingContext buildingContext) {
        return new RedisVersionedStorageAccess(regionConfig.getRegionName());
    }

    @Override
    protected StorageAccess createQueryResultsRegionStorageAccess(
            String regionName,
            SessionFactoryImplementor sessionFactory) {
        // Query cache is not enabled. Keep this local/pass-through so the
        // domain-data version protocol does not corrupt Hibernate's internal
        // query-cache timestamps.
        return new LocalStorageAccess();
    }

    @Override
    protected StorageAccess createTimestampsRegionStorageAccess(
            String regionName,
            SessionFactoryImplementor sessionFactory) {
        return new LocalStorageAccess();
    }
}
