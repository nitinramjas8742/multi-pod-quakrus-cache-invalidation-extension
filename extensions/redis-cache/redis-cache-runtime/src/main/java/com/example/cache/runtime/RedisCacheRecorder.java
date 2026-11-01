package com.example.cache.runtime;

import io.quarkus.hibernate.orm.runtime.integration.HibernateOrmIntegrationRuntimeInitListener;
import io.quarkus.runtime.annotations.Recorder;
import org.hibernate.boot.registry.StandardServiceInitiator;

import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

@Recorder
public class RedisCacheRecorder {

    public HibernateOrmIntegrationRuntimeInitListener initListener() {
        return new HibernateOrmIntegrationRuntimeInitListener() {
            @Override
            public void contributeRuntimeProperties(BiConsumer<String, Object> propertyCollector) {
            }

            @Override
            public List<StandardServiceInitiator<?>> contributeServiceInitiators() {
                return Collections.singletonList(RedisRegionFactoryInitiator.INSTANCE);
            }
        };
    }
}
