package com.example.cache.runtime;

import org.hibernate.cache.internal.BasicCacheKeyImplementation;
import org.hibernate.cache.internal.CacheKeyImplementation;
import org.hibernate.cache.internal.NaturalIdCacheKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the tenant isolation of the Redis version key.
 *
 * <p>Hibernate's cache key {@code toString()} is only {@code Entity#id}; the
 * tenant lives in {@code equals}/{@code hashCode}. If the Redis key is built
 * from {@code toString()} alone, two tenants with the same entity id share one
 * version counter and evict each other's entries.
 */
class RedisVersionedStorageAccessTenantTest {

    private static final String REGION = "com.example.Agency";

    private final RedisVersionedStorageAccess access = new RedisVersionedStorageAccess(REGION);

    @Test
    void entityKeySeparatesTenants() {
        Object tenantA = new CacheKeyImplementation(1L, REGION, "tenant_a", 31);
        Object tenantB = new CacheKeyImplementation(1L, REGION, "tenant_b", 31);

        String keyA = access.versionKey(tenantA);
        String keyB = access.versionKey(tenantB);

        assertNotEquals(keyA, keyB);
        assertTrue(keyA.startsWith("l2c:" + REGION + ":v:tenant_a:"));
        assertTrue(keyB.startsWith("l2c:" + REGION + ":v:tenant_b:"));
    }

    @Test
    void naturalIdKeySeparatesTenants() {
        Object tenantA = new NaturalIdCacheKey(new Object[]{"AG1"}, REGION, "tenant_a", 31);
        Object tenantB = new NaturalIdCacheKey(new Object[]{"AG1"}, REGION, "tenant_b", 31);

        assertNotEquals(access.versionKey(tenantA), access.versionKey(tenantB));
        assertTrue(access.versionKey(tenantA).contains("tenant_a"));
    }

    @Test
    void sameTenantAndIdProducesStableKey() {
        Object first = new CacheKeyImplementation(1L, REGION, "tenant_a", 31);
        Object second = new CacheKeyImplementation(1L, REGION, "tenant_a", 31);

        assertEquals(access.versionKey(first), access.versionKey(second));
    }

    @Test
    void singleTenantDeploymentsKeepThePlainFormat() {
        Object key = new CacheKeyImplementation(1L, REGION, null, 31);

        assertEquals("l2c:" + REGION + ":v:" + REGION + "#1", access.versionKey(key));
    }

    @Test
    void hibernateNonTenantKeyUsesThePlainFormat() {
        // Hibernate uses BasicCacheKeyImplementation when no tenant is set.
        // It has no getTenantId(), so the extension must not add a segment.
        Object key = new BasicCacheKeyImplementation(1L, REGION, 31);

        assertTrue(access.versionKey(key).startsWith("l2c:" + REGION + ":v:" + REGION + "#"));
    }

    @Test
    void hibernateLocalKeysAlreadySeparateTenants() {
        // The local Caffeine cache stores the CacheKey object itself, so this
        // equality is what keeps tenant values apart in each pod.
        Object tenantA = new CacheKeyImplementation(1L, REGION, "tenant_a", 31);
        Object tenantB = new CacheKeyImplementation(1L, REGION, "tenant_b", 31);

        assertNotEquals(tenantA, tenantB);
    }
}
