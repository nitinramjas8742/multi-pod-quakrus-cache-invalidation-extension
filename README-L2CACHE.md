# Redis-backed Hibernate second-level cache (multi-pod)

This project uses a **custom Quarkus extension** that replaces Hibernate's
RegionFactory with a local (Caffeine) near-cache whose entries are invalidated
through **Redis version and epoch counters**. It is not a distributed cache:
each pod keeps its own values locally and only the *invalidation state* is
shared through Redis.

The goal is to answer one question: **does a write on pod A become visible on
pod B?** The verified answer for Hibernate-managed writes is **yes** (see
[Verification results](#verification-results)).

## Why the original implementation did not work

1. **The extension was never discovered.** The deployment module did not run
   Quarkus's annotation processor, so `META-INF/quarkus-build-steps.list` was
   missing and `RedisCacheProcessor` never executed. The app therefore used
   Quarkus's default `QuarkusRegionFactoryInitiator`, not the custom one.
2. **The integration was attached to the wrong persistence unit.** Quarkus
   names the implicit default persistence unit `"<default>"`, not `"default"`.
   With the wrong name the build item is silently ignored.
3. **The invalidation algorithm was wrong for `READ_WRITE`.** Hibernate's stock
   `READ_WRITE` strategy calls `storageAccess.putIntoCache(...)` when loading an
   entity and keeps its soft locks inside the cache entry. With a per-pod value
   cache that stamped a new version on every read and the lock state was never
   visible to other pods. The extension now supplies its own read-write access
   classes that route `READ_WRITE` regions through the same version protocol as
   `NONSTRICT_READ_WRITE` (see `RedisDomainDataRegion`).
4. **Invalidation used JVM wall-clock timestamps.** Clocks can skew or move
   backwards, and a timestamp captured after the database read leaves a
   lost-invalidation race window.

## How the current protocol works

Redis keys (per Hibernate region):

| Key | Meaning |
| --- | --- |
| `l2c:<region>:v:<cacheKey>` | monotonically increasing version for one cache key |
| `l2c:<region>:e` | monotonically increasing epoch for the whole region |

* **Read:** one Redis `MGET` fetches the key version and the region epoch.
  * No local entry: record the `(epoch, version)` in a bounded thread-local
    map and let Hibernate read the database.
  * Local entry with matching `(epoch, version)`: return it (cache hit).
  * Mismatch: evict locally, record the current state, and let Hibernate
    reload from the database.
* **Load:** `putFromLoad` stores the value together with the `(epoch, version)`
  captured *before* the database read. This closes the race where a load that
  started before a write finishes after it.
* **Write/delete/evict:** `removeFromCache`/`evictData(key)` invalidate the
  local entry and `INCR` the key version. Other pods see the new version on
  their next read.
* **Region clear:** `INCR` the region epoch. All pods discard the region.
* **Redis unavailable:** reads fail closed. The local entry is dropped and the
  request goes to the database. Writes still commit, but their invalidation
  counter cannot be updated (see caveats).

Entities and the `Agency.agentCodes` collection use
`CacheConcurrencyStrategy.READ_WRITE`, and the protocol above also works for
`NONSTRICT_READ_WRITE`. Both strategies are supported because the extension
builds its own access classes for `READ_WRITE` regions
(`RedisReadWriteEntityAccess`, `RedisReadWriteNaturalIdAccess`,
`RedisReadWriteCollectionAccess`) from `RedisDomainDataRegion`. Those classes
use Hibernate's non-strict flow: loads through `putFromLoad`, writes through
`removeFromCache`.

`READ_WRITE` here means "coherent on the next read", not Hibernate's strict
cluster-wide soft locking. Hibernate's own read-write access stores a soft-lock
list inside the cache entry; that list lives in the pod's local Caffeine cache,
so another pod cannot see it. Distributed locking is not provided. Concurrent
writers are still serialised by the database.

`TRANSACTIONAL` and `READ_ONLY` are not supported: transactional access assumes
a cache that participates in JTA, and read-only access fills the cache through
`putIntoCache`, which this storage treats as an invalidation.

The query-results and timestamps regions use a plain local map
(`LocalStorageAccess`) because per-key domain versioning does not apply to
them. Query caching is not enabled in this project.

## Configuration

`src/main/resources/application.properties`:

```properties
quarkus.hibernate-orm.second-level-caching-enabled=true
quarkus.redis.hosts=redis://localhost:6379
```

You also need a working Redis:

```powershell
docker run -d --name redis -p 6379:6379 redis
```

## Build and run two pods

```powershell
.\gradlew clean build -x test --console=plain

java -Dquarkus.http.port=8081 -jar build\quarkus-app\quarkus-run.jar > build\app-8081.log 2>&1
java -Dquarkus.http.port=8082 -jar build\quarkus-app\quarkus-run.jar > build\app-8082.log 2>&1
```

Startup should show both of these lines (the feature name and the custom
factory):

```
Installed features: [..., redis-l2-cache, ...]
>>> Initializing MyCustomRegionFactory with Redis version/epoch invalidation <<<
```

## Reusing the extension in another project

The extension is two modules:

| Module | Coordinates |
| --- | --- |
| Runtime (the code the app uses) | `com.example:redis-cache-runtime:1.1.1` |
| Deployment (build steps; resolved automatically) | `com.example:redis-cache-deployment:1.1.1` |

The runtime jar contains `META-INF/quarkus-extension.properties` pointing at the
deployment artifact, and the deployment jar contains `META-INF/quarkus-build-steps.list`.
Quarkus uses those two files to discover and run the extension. You only add the
**runtime** artifact as a dependency; never add the deployment artifact yourself.

**Why not a single jar?** A Quarkus extension is always a runtime artifact plus a
deployment artifact. At build time Quarkus reads the runtime descriptor, resolves
the deployment jar from a repository, and runs its build steps. A lone runtime
jar has no deployment artifact to resolve, so it cannot work as an extension.
The closest thing to "one file" is the zip below, which contains the two jars in
a ready-to-use repository layout.

### Option 1: drop-in repository zip (recommended)

Build the repository and zip:

```powershell
.\gradlew bundleExtensionRepo
```

Outputs:

* `build/extension-repo/` â€“ a normal Maven repository layout
* `build/dist/redis-l2-cache-extension-repo-1.1.1.zip`

Unzip it into the consuming project (for example `libs/redis-l2-cache-repo`),
then point the build at it:

```gradle
repositories {
    maven { url = uri('libs/redis-l2-cache-repo') }
    mavenCentral()
}

dependencies {
    implementation enforcedPlatform('io.quarkus.platform:quarkus-bom:3.37.0')
    implementation 'io.quarkus:quarkus-hibernate-orm-panache'   // or -orm
    implementation 'io.quarkus:quarkus-jdbc-postgresql'        // your DB driver
    implementation 'io.quarkus:quarkus-rest-jackson'           // if you expose REST
    implementation 'com.example:redis-cache-runtime:1.1.1'
}
```

Maven equivalent for the repository:

```xml
<repositories>
  <repository>
    <id>redis-l2-cache-repo</id>
    <url>file://${project.basedir}/libs/redis-l2-cache-repo</url>
  </repository>
</repositories>
```

Notes:

* Keep the whole repository folder. Quarkus resolves the deployment jar from it
  automatically; extracting only `redis-cache-runtime` is not enough.
* The repository is published at version `1.1.1`. When you change the extension,
  bump the version in both module `build.gradle` files **and** in
  `redis-cache-runtime/src/main/resources/META-INF/quarkus-extension.properties`,
  then regenerate the zip. Keep the consumer's version in sync.
* The zip lives under `build/dist`, so copy it somewhere durable before running
  `clean` or handing it over.
* This path was verified with a scratch consumer app that declared only the
  repository above and `com.example:redis-cache-runtime:1.1.1`; the
  extension loaded and a repeated read produced no SQL.

### Option 2: publish to Maven Local (separate repositories)

```powershell
.\gradlew :extensions:redis-cache:redis-cache-runtime:publishToMavenLocal `
          :extensions:redis-cache:redis-cache-deployment:publishToMavenLocal
```

Then in the other project:

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation enforcedPlatform('io.quarkus.platform:quarkus-bom:3.37.0')
    implementation 'io.quarkus:quarkus-hibernate-orm-panache'   // or -orm
    implementation 'io.quarkus:quarkus-jdbc-postgresql'        // your DB driver
    implementation 'io.quarkus:quarkus-rest-jackson'           // if you expose REST
    implementation 'com.example:redis-cache-runtime:1.1.1'
}
```

Maven equivalent:

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>redis-cache-runtime</artifactId>
  <version>1.1.1</version>
</dependency>
```

For a shared repository, add a `publishing { repositories { maven { ... } } }`
block to both extension modules and run `publish` instead of
`publishToMavenLocal`. Publish snapshots to a snapshot repository.

### Option 3: copy the modules into the other project

1. Copy the whole `extensions/redis-cache` directory into the other repository.
2. Add the two modules to its `settings.gradle`:
   ```gradle
   include 'extensions:redis-cache:redis-cache-runtime'
   include 'extensions:redis-cache:redis-cache-deployment'
   ```
3. Depend on the runtime project:
   ```gradle
   implementation project(':extensions:redis-cache:redis-cache-runtime')
   ```
4. Make the Quarkus BOM version in both copied `build.gradle` files match the
   consuming project (see compatibility below).

This works without publishing, but you must keep the copies in sync.

### Required consumer configuration

```properties
quarkus.hibernate-orm.second-level-caching-enabled=true
quarkus.redis.hosts=redis://localhost:6379
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/mydb
quarkus.datasource.username=...
quarkus.datasource.password=...
```

`quarkus-redis-client` is a transitive dependency of the extension, but adding
it explicitly to the consumer is harmless and makes the requirement obvious.

### Required entity annotations

Every cached entity and cached collection must use `READ_WRITE` or
`NONSTRICT_READ_WRITE`. Both are served by the version protocol:

```java
@Entity
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Customer {
    // ...

    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    @ElementCollection
    public List<String> tags = new ArrayList<>();
}
```

`@Cacheable` comes from `jakarta.persistence`, `@Cache` and
`CacheConcurrencyStrategy` from `org.hibernate.annotations`.

Association caches (e.g. `@OneToMany`) use `@Cache` on the collection field in
the same way. Do not use `TRANSACTIONAL` or `READ_ONLY`; `TRANSACTIONAL` needs a
JTA-aware cache and `READ_ONLY` regions are not cached by this extension.

### Confirm it loaded

After starting the consumer app, the log must contain both lines:

```
Installed features: [..., redis-l2-cache, ...]
>>> Initializing MyCustomRegionFactory with Redis version/epoch invalidation <<<
```

A simple smoke test: call an endpoint that loads a cached entity twice. The
second call should not log SQL. This exact flow was verified with a scratch
consumer application that depended only on
`com.example:redis-cache-runtime:1.1.1` from Maven Local.

### Compatibility and coordinate changes

* Built for **Java 21**, **Quarkus 3.37.0**, **Hibernate ORM 7.4.1**. The
  deployment code uses Quarkus's Hibernate integration API, so rebuild the
  extension against the consuming project's Quarkus BOM if the version differs;
  do not assume binary compatibility across Quarkus minors.
* The extension registers itself only for the **default persistence unit**
  (`"<default>"`). If the other project declares a named persistence unit or
  multiple units, change `RedisCacheProcessor` to accept the target unit name
  (for example through a build-time config property). It will not attach
  automatically to named units.
* Do not combine it with another second-level cache region factory (for example
  the Debezium cache extension) in the same persistence unit; only one wins.
* If you rename the extension, keep these three places in sync:
  `group`/`version` in both `build.gradle` files, and
  `deployment-artifact=<group>:redis-cache-deployment:<version>` in
  `redis-cache-runtime/src/main/resources/META-INF/quarkus-extension.properties`.

## Hand-off checklist for another team (Quarkus + Gradle)

This is the short version to give to the consuming team. It assumes the
extension owner has produced a build with matching Quarkus/Hibernate versions.

### 1. What the extension owner hands over

Either:

* the two artifacts in the organisation's Maven repository (Nexus/Artifactory), or
* `build/dist/redis-l2-cache-extension-repo-<version>.zip` from
  `.\gradlew bundleExtensionRepo`.

The current build is release `1.1.1`. For future changes, bump the version in:

1. `extensions/redis-cache/redis-cache-runtime/build.gradle`
2. `extensions/redis-cache/redis-cache-deployment/build.gradle`
3. `extensions/redis-cache/redis-cache-runtime/src/main/resources/META-INF/quarkus-extension.properties`
   (`deployment-artifact=<group>:redis-cache-deployment:<version>`)

Then run `.\gradlew clean bundleExtensionRepo`.

Check that the consumer uses the same Quarkus/Hibernate line. If not, rebuild the
extension against their BOM first.

For a Nexus/Artifactory hand-off, upload the contents of
`build/extension-repo` preserving the `com/...` layout, or add the internal
repository to the extension's `publishing.repositories` block and run `publish`.

### 2. What the consuming team changes

**a. Add the repository** (`build.gradle`; use the internal repo URL instead of
the folder if that is how you distribute it):

```gradle
repositories {
    // Option A: internal Maven repository
    // maven { url = uri('https://nexus.company.com/repository/maven-releases/') }

    // Option B: the unzipped drop-in repository
    maven { url = uri('libs/redis-l2-cache-repo') }

    mavenCentral()
}
```

**b. Add exactly one dependency:**

```gradle
dependencies {
    implementation 'com.example:redis-cache-runtime:1.1.1'
}
```

Do **not** add `redis-cache-deployment`; Quarkus resolves it from the runtime
jar's descriptor.

**c. Enable 2LC and point at the shared Redis:**

```properties
quarkus.hibernate-orm.second-level-caching-enabled=true
quarkus.redis.hosts=redis://redis.internal:6379
```

All pods of the application must use the **same Redis**. If several unrelated
applications share one Redis, give each its own logical database or instance;
otherwise identical entity class names would share invalidation keys.

**d. Change the cache strategy on every entity/collection they want cached:**

```java
@Entity
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Customer {
    // ...

    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    @ElementCollection
    public List<String> tags = new ArrayList<>();
}
```

`READ_WRITE` and `NONSTRICT_READ_WRITE` are both supported. Existing
annotations can stay as they are; only `TRANSACTIONAL` and `READ_ONLY` are not
supported. Entities left unannotated simply are not cached; they are not
broken.

**e. Verify on startup:**

```
Installed features: [..., redis-l2-cache, ...]
>>> Initializing MyCustomRegionFactory with Redis version/epoch invalidation <<<
```

If these lines are absent, the extension or its persistence-unit attachment did
not load.

**f. Verify with two pods:**

1. Start two instances against the same DB and Redis.
2. Read the same entity on both (warm the caches).
3. Update it on pod A.
4. Read it on pod B: it must show the new value.
5. Delete it on pod A and confirm 404 on pod B.

### 3. What they must not do

* Do not add the deployment artifact as an application dependency.
* Do not use `TRANSACTIONAL` or `READ_ONLY` for entities that must be coherent
  across pods; only `READ_WRITE` and `NONSTRICT_READ_WRITE` are served.
* Do not put another second-level cache region factory (e.g. Debezium cache) on
  the same persistence unit.
* Do not use a named persistence unit without adapting
  `RedisCacheProcessor`; only Quarkus's `<default>` unit is attached today.
* Do not expect native SQL, bulk JPQL, or direct database writes to invalidate
  the cache. Only Hibernate-managed writes do.

## Automated verification

With both pods running and Redis reachable:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-l2-cache.ps1
```

The script checks:

1. A second read on the same pod does not issue SQL (local cache is active).
2. A write on pod A is visible on pod B, and pod B reloads from the database.
3. Repeated cross-pod write-then-read iterations produce no stale reads.
4. A deleted row returns 404 on both pods.
5. The mutated test agency is restored to its original values.

Exit code is `0` only when every check passes. Useful parameters:
`-PortA`, `-PortB`, `-AgencyCode`, `-LogA`, `-LogB`, `-RaceIterations`.

### Manual equivalents

```powershell
# Warm both pods
Invoke-RestMethod http://localhost:8081/api/agencies/code/AG1
Invoke-RestMethod http://localhost:8082/api/agencies/code/AG1

# Update on pod A, read on pod B
Invoke-RestMethod http://localhost:8081/api/agencies/code/AG1 -Method PUT `
  -ContentType application/json `
  -Body '{"name":"Changed","email":"c@x.com","status":"ACTIVE","agentCodes":["A1","A2"]}'
Invoke-RestMethod http://localhost:8082/api/agencies/code/AG1   # must show "Changed"

# Inspect invalidation state
docker exec redis redis-cli --scan --pattern "l2c:*"
```

## Verification results

Verified on 2026-10-31 against Quarkus 3.37.0 / Hibernate ORM 7.4.1 with two
JVM instances (ports 8081 and 8082) sharing one PostgreSQL database and one
Redis instance:

* Custom extension loaded (`redis-l2-cache` in installed features; custom
  RegionFactory log line present on both pods).
* Single-pod cache hit: second `GET /api/agencies/code/AG1` produced no SQL.
* Cross-pod update: update on 8081 was immediately visible on 8082; pod 8082
  issued a fresh SQL query after the invalidation.
* 15 consecutive cross-pod update/read iterations: zero stale reads.
* `findById` path and natural-id path both invalidated correctly.
* Delete: a temporary agency warmed on both pods returned 404 on both after
  deletion on 8081.
* Region clear: manually incrementing `l2c:<region>:e` forced pod 8082 to
  evict and reload.
* Redis outage: reads still returned correct data by falling back to the
  database; warnings were throttled instead of logged per request.
* After Redis restart, caching resumed (second read produced no SQL).
* Version keys carry a ~1 hour TTL; they do not grow without bound.
* Re-verified on 2026-11-01 with `READ_WRITE` on entities, natural ids, and a
  collection: cross-pod update, `findById`, collection contents, and delete all
  behaved correctly.
* Multi-tenant isolation verified on 2026-11-01 with two pods and
  schema-per-tenant multitenancy. Tenant A and tenant B stored the same entity
  id (`1`). After writing tenant A on pod 1, pod 2's tenant B read still came
  from its local cache (no SQL, value unchanged), while pod 2's tenant A read
  reloaded and saw the new value (one SQL). Redis contained
  `l2c:...:v:tenant_a:...` style keys, never a shared tenant-less counter.
* Non-tenant deployments re-verified on 2026-11-01 with the same two-pod
  script (`ALL CHECKS PASSED`): hits, cross-pod update, 10-iteration race,
  delete/404. Redis keys kept the original plain format
  (`l2c:<region>:v:<entity>#id`), with no tenant or `null` segment.
* `RedisVersionedStorageAccessTenantTest` covers both modes: 6 tests, 0
  failures, including Hibernate's `BasicCacheKeyImplementation` (the key class
  used when no tenant is configured).

## Known limits

These are real production caveats, not bugs in the current implementation:

* **Only Hibernate-managed writes invalidate.** Native SQL, bulk JPQL
  updates/deletes, other services writing the same tables, or direct database
  changes bypass `removeFromCache` and therefore do not bump versions. If
  those paths exist, use database change capture (e.g. Debezium) or perform
  writes through this application.
* **One synchronous Redis round trip per cache read.** Every hit issues an
  `MGET`. That is the cost of strong cross-pod freshness. For read-heavy
  workloads, consider batching reads or accepting a short local TTL without
  per-hit checks.
* **Writes during a Redis outage can lose an invalidation.** The write itself
  commits; only the counter update fails. Other pods with a warm local entry
  may serve stale data until their entry expires (10 minutes) or is
  invalidated later. Reads during the outage remain correct because they fail
  closed.
* **Regional epoch keys created outside the code** (e.g. a manual `INCR` in
  `redis-cli`) have no TTL. The application sets a 1 hour TTL whenever it
  increments an epoch itself.
* **Query cache is disabled.** `findAll`/list endpoints always execute SQL.
  Enabling it would require a separate clustered query-cache design.
* **Region names must be unique per application** if multiple applications
  share one Redis, because invalidation keys are prefixed only by region name.
* **Multi-tenancy is optional and isolated per tenant.** Hibernate's local
  cache key already contains the tenant id, and the Redis version key is
  `l2c:<region>:v:<tenant>:<key>` (Hibernate's own `toString()` omits the
  tenant, so the extension adds it explicitly). A write for tenant A therefore
  does not invalidate tenant B, even when both use the same entity id. Verified
  with two pods and two tenants. Without multitenancy, Hibernate uses
  `BasicCacheKeyImplementation`, the extension sees no tenant, and the key is
  the original `l2c:<region>:v:<key>`; the non-tenant two-pod suite passes
  unchanged. The region epoch is still shared: a region `clear()` evicts every
  tenant, which is conservative and cannot serve stale data.
* **Hibernate's default cache keys factory is required.** The extension reads
  the tenant from `CacheKeyImplementation` / `NaturalIdCacheKey`. A custom
  `hibernate.cache.keys_factory` that drops the tenant would collapse tenant
  entries; that would also break Hibernate's own local cache isolation.
* **Only the default persistence unit is supported.** The extension attaches
  its integration to Quarkus's `<default>` persistence unit. Named or multiple
  persistence units require adapting `RedisCacheProcessor` to take a unit name.
* **`dbz_publication` / Debezium is not used by this path.** The earlier
  `REPLICA IDENTITY` issue on `agency_agent_codes` was fixed
  (`ALTER TABLE agency_agent_codes REPLICA IDENTITY FULL;`) so updates and
  deletes work even with the publication present.

