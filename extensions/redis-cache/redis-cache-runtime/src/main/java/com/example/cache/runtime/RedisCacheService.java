package com.example.cache.runtime;

import io.quarkus.arc.Unremovable;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
@Unremovable
public class RedisCacheService {

    private static final Logger LOG = Logger.getLogger(RedisCacheService.class);

    /**
     * Hibernate may create its RegionFactory before CDI startup observers run.
     * The storage access falls back to Arc lookup; this static reference is
     * only a fast path for the normal case.
     */
    public static volatile RedisCacheService INSTANCE;

    @Inject
    RedisDataSource redisDataSource;

    private volatile ValueCommands<String, String> commands;
    private volatile KeyCommands<String> keyCommands;

    void onStart(@Observes StartupEvent ev) {
        INSTANCE = this;
        LOG.info(">>> RedisCacheService initialized and ready for Hibernate L2 cache invalidation <<<");
    }

    /**
     * @return null when Redis is not available. Callers must treat null as
     *         "cannot verify freshness" and fail closed (bypass the local cache).
     */
    public ValueCommands<String, String> getCommands() {
        ValueCommands<String, String> snapshot = commands;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (commands == null && redisDataSource != null) {
                try {
                    commands = redisDataSource.value(String.class);
                } catch (Exception e) {
                    LOG.errorf("Error getting ValueCommands from RedisDataSource: %s", e.getMessage());
                }
            }
            return commands;
        }
    }

    /**
     * @return null when Redis is not available.
     */
    public KeyCommands<String> getKeyCommands() {
        KeyCommands<String> snapshot = keyCommands;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (keyCommands == null && redisDataSource != null) {
                try {
                    keyCommands = redisDataSource.key(String.class);
                } catch (Exception e) {
                    LOG.errorf("Error getting KeyCommands from RedisDataSource: %s", e.getMessage());
                }
            }
            return keyCommands;
        }
    }
}
