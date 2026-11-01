# Redis L2 cache extension (Quarkus + Hibernate)

Cross-pod invalidation for Hibernate's second-level cache.

Each application pod keeps a local Caffeine cache. Redis stores per-key version
counters and per-region epoch counters. A write on any pod bumps the counter,
and other pods evict their local entry on their next read. Redis failures fail
closed: reads go to the database instead of serving possibly stale data.

Works with or without Hibernate multitenancy. Supports `READ_WRITE` and
`NONSTRICT_READ_WRITE`.

## Download

The ready-to-use bundle is committed in this repository:

| File | What it contains |
|---|---|
| [`dist/redis-l2-cache-handoff-1.1.1.zip`](dist/redis-l2-cache-handoff-1.1.1.zip) | The two jars, full source, docs, and the verification script |
| [`dist/redis-l2-cache-extension-repo-1.1.1.zip`](dist/redis-l2-cache-extension-repo-1.1.1.zip) | Maven repository with the two jars only |
| [`dist/redis-l2-cache-extension-source-1.1.1.zip`](dist/redis-l2-cache-extension-source-1.1.1.zip) | Standalone source project |

On GitHub, open the file and click **Download**.

Hand-off zip SHA-256:

```
5339A7457A926C40850F6D40055E10789EB3C3176E0AD130A9A6A6F97BEE2410
```

## Start here

- [`HANDOFF.md`](HANDOFF.md) - how another team integrates it into a Quarkus + Gradle application
- [`README-L2CACHE.md`](README-L2CACHE.md) - design, guarantees, limitations, and verification details
- [`scripts/verify-l2-cache.ps1`](scripts/verify-l2-cache.ps1) - automated two-pod verification

## Requirements

- Java 21
- Quarkus 3.37.0 / Hibernate ORM 7.4.1 (rebuild against another BOM if needed)
- Redis reachable from every application pod

## Build from source

```powershell
.\gradlew build -x test --console=plain
.\gradlew :extensions:redis-cache:redis-cache-runtime:test --console=plain
```

To publish the extension to Maven Local:

```powershell
.\gradlew publishToMavenLocal --console=plain
```

## Running the demo application

The demo app uses environment variables for database credentials. Copy
`.env.example` to `.env` and fill in your local values, or set them in the
current shell:

```powershell
$env:DB_USER = "postgres"
$env:DB_PASSWORD = "your-password"
java -jar build\quarkus-app\quarkus-run.jar
```
