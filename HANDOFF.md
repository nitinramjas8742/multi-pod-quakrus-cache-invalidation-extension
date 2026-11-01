# Redis L2 Cache Extension â€” Hand-off

## What this is

A Quarkus extension that gives Hibernate's second-level cache
cross-pod invalidation through Redis. Each pod keeps a local cache; Redis holds
per-key version counters and per-region epoch counters. A write on any pod bumps
the counter, and other pods evict their local entry on the next read.

Both `READ_WRITE` and `NONSTRICT_READ_WRITE` annotations are supported. For
`READ_WRITE`, the extension supplies its own access classes so that loads still
go through `putFromLoad` and writes through `removeFromCache`; Hibernate's
cluster-wide soft locking is not provided (see Constraints).

Verified with two application instances against one PostgreSQL database and one
Redis instance.

## Coordinates

| Artifact | Coordinates | Purpose |
| --- | --- | --- |
| Runtime | `com.example:redis-cache-runtime:1.1.1` | Add this to the application |
| Deployment | `com.example:redis-cache-deployment:1.1.1` | Resolved automatically by Quarkus |

Do **not** add the deployment artifact as an application dependency.

## Compatibility

* Java 21
* Quarkus 3.37.0
* Hibernate ORM 7.4.1
* Any database supported by Hibernate (verified with PostgreSQL)
* Redis reachable from every pod

For a different Quarkus/Hibernate version, rebuild the extension against that
BOM before use. The extension uses Quarkus/Hibernate internal APIs.

## Integration steps

1. Add the packaged repository to the application build:

   ```gradle
   repositories {
       maven { url = uri('libs/redis-l2-cache-repo') }
       mavenCentral()
   }
   ```

2. Add the runtime dependency:

   ```gradle
   implementation 'com.example:redis-cache-runtime:1.1.1'
   ```

3. Enable second-level caching and configure Redis:

   ```properties
   quarkus.hibernate-orm.second-level-caching-enabled=true
   quarkus.redis.hosts=redis://redis.internal:6379
   ```

4. Mark cached entities and collections with `READ_WRITE` (or
   `NONSTRICT_READ_WRITE`; both work):

   ```java
   @Entity
   @Cacheable
   @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
   public class Customer {
       @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
       @ElementCollection
       public List<String> tags = new ArrayList<>();
   }
   ```

5. Start the application and confirm:

   ```
   Installed features: [..., redis-l2-cache, ...]
   >>> Initializing MyCustomRegionFactory with Redis version/epoch invalidation <<<
   ```

6. Smoke test with two pods: warm both, update on pod A, read on pod B,
   delete on pod A, confirm 404 on pod B.

## Constraints

* Only Hibernate-managed writes invalidate the cache. Native SQL, bulk JPQL,
  other services writing the same tables, and direct DB changes bypass it.
* `READ_WRITE` is honoured as "coherent on the next read". Hibernate's strict
  soft-lock protocol needs one shared lock store; here the value cache is
  per-pod, so no cluster-wide locking is performed. Concurrent writers are
  serialised by the database.
* `TRANSACTIONAL` is not supported (needs a JTA-aware cache) and `READ_ONLY`
  regions are not cached (they fill through `putIntoCache`).
* Only Quarkus's default persistence unit (`<default>`) is supported.
* Query cache is not part of this design (list queries still hit the database).
* Each cache read costs one synchronous Redis `MGET`.
* If Redis is unavailable, reads fall back to the database (fail closed).
  Writes during a Redis outage can lose their invalidation until local entries
  expire (10 minutes).
* Do not combine with another second-level cache region factory on the same
  persistence unit.
* If multiple applications share one Redis, isolate them (separate logical
  database or instance) to avoid region-name collisions.
* Multi-tenancy is optional. With no tenant configured the Redis version key
  is the original `l2c:<region>:v:<key>` and the non-tenant two-pod suite
  passes unchanged. With multitenancy, both the local cache key and the Redis
  version key include the Hibernate tenant id, so tenants never invalidate each
  other even when they share entity ids. A region `clear()` still evicts all
  tenants (conservative). Verified with two pods, two tenants, same id.
* Keep Hibernate's default cache keys factory (`hibernate.cache.keys_factory`
  unset). A custom factory that drops the tenant would also break Hibernate's
  own cache isolation.

## What is in this bundle

```
HANDOFF.md                         this file
README-L2CACHE.md                  full design, setup, and verification details
extension-repo/                    ready-to-use Maven repository
extension-repo-1.1.1.zip           the same repository, zipped
source/                            extension source (runtime + deployment modules)
scripts/verify-l2-cache.ps1        example two-pod verification script (POC-specific REST API)
```

## Using the source instead of the packaged repository

1. Copy `source/extensions/redis-cache` into the consuming repository.
2. Add to its `settings.gradle`:

   ```gradle
   include 'extensions:redis-cache:redis-cache-runtime'
   include 'extensions:redis-cache:redis-cache-deployment'
   ```

3. Depend on the runtime project:

   ```gradle
   implementation project(':extensions:redis-cache:redis-cache-runtime')
   ```

4. Align the Quarkus BOM version in both module `build.gradle` files with the
   consuming project, then rebuild.

## Verification evidence

The `1.1.1` drop-in repository was consumed by a separate Quarkus application
that declared only `com.example:redis-cache-runtime:1.1.1`. The application
started with `redis-l2-cache` in its installed features and served a repeated
read without additional SQL. The two-pod checks (update, delete, fail-closed
Redis outage, epoch invalidation) passed on the source build, and were re-run
with the entities annotated `READ_WRITE`: local hits, cross-pod update, ten
update/read iterations, natural-id lookups, `findById`, collection contents, and
delete propagation all passed.

