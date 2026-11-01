package com.example.cache.deployment;

import com.example.cache.runtime.RedisCacheRecorder;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.hibernate.orm.deployment.integration.HibernateOrmIntegrationRuntimeConfiguredBuildItem;
import io.quarkus.hibernate.orm.runtime.PersistenceUnitUtil;

public class RedisCacheProcessor {

    private static final String FEATURE = "redis-l2-cache";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    public HibernateOrmIntegrationRuntimeConfiguredBuildItem register(RedisCacheRecorder recorder) {
        // Constructor order is (integrationName, persistenceUnitName).
        // Quarkus calls the implicit default persistence unit "<default>",
        // not "default"; using the wrong name silently disables the custom
        // RegionFactory. Keep these arguments in this order.
        return new HibernateOrmIntegrationRuntimeConfiguredBuildItem(
                "redis-l2-cache", PersistenceUnitUtil.DEFAULT_PERSISTENCE_UNIT_NAME)
                .setInitListener(recorder.initListener());
    }
}
