package com.example.cache.runtime;

import org.hibernate.boot.registry.StandardServiceInitiator;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.jboss.logging.Logger;

import java.util.Map;

public class RedisRegionFactoryInitiator implements StandardServiceInitiator<RegionFactory> {

    private static final Logger LOG = Logger.getLogger(RedisRegionFactoryInitiator.class);

    public static final RedisRegionFactoryInitiator INSTANCE = new RedisRegionFactoryInitiator();

    @Override
    public Class<RegionFactory> getServiceInitiated() {
        return RegionFactory.class;
    }

    @Override
    public RegionFactory initiateService(Map<String, Object> configurationValues, ServiceRegistryImplementor registry) {
        LOG.info(">>> [QUARKUS EXTENSION] Successfully initiated custom MyCustomRegionFactory via RedisRegionFactoryInitiator! <<<");
        return new MyCustomRegionFactory();
    }
}
