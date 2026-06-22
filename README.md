# Search Typeahead System

HLD101 assignment submission: a search typeahead system backed by Postgres
(durable storage), a 3-node Redis cache distributed via consistent hashing,
batched writes, and recency-aware trending.

## 1. Quick start

Requires Docker + Docker Compose. Nothing else needs to be installed locally.

```bash
docker compose up --build
```

This starts, in order:
1. `postgres` — durable store, schema auto-applied from `backend/src/main/resources/schema.sql`
2. `redis-node-1`, `redis-node-2`, `redis-node-3` — three independent Redis containers (the distributed cache)
3. `backend` — Spring Boot app; on first boot it bulk-loads `backend/src/main/resources/dataset/queries.csv`
   into Postgres (~1.24M rows; expect roughly 1-3 minutes depending on hardware)
4. `frontend` — React app served by nginx

Once it's up:
- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- Postgres: localhost:5432 (user/pass: `typeahead`/`typeahead`)
- Redis nodes: localhost:6379, 6380, 6381

First boot is slower because of the dataset import. Subsequent
`docker compose up` runs skip the import (the loader checks if the table
is already populated).

### Running without Docker (local dev)

```bash
# 1. Start Postgres and 3 Redis instances , or just run the
#    Docker Compose services you need:
docker compose up postgres redis-node-1 redis-node-2 redis-node-3

# 2. Backend (separate terminal)
cd backend
DB_HOST=localhost REDIS_NODES=localhost:6379,localhost:6380,localhost:6381 \
  mvn spring-boot:run

# 3. Frontend (separate terminal)
cd frontend
npm install
npm run dev
# visit http://localhost:5173
```

## 2. Dataset

**Source**: the public [AOL Search Query Log](https://en.wikipedia.org/wiki/AOL_search_log_release)
(`user-ct-test-collection-02.txt`), a real, widely-used research dataset of
~3.6M anonymized search log rows (`AnonID`, `Query`, `QueryTime`, `ItemRank`,
`ClickURL`).

**Aggregation**: the raw log is per-search-event, not pre-counted, so
`data/aggregate_aol_log.py` group-by-counts it into a `(query, count)` CSV:

```bash
python3 data/aggregate_aol_log.py path/to/user-ct-test-collection-02.txt -o queries.csv
```

Running it against the real log produced **1,244,495 distinct queries**
(well above the 100,000 minimum), saved as
`backend/src/main/resources/dataset/queries.csv` and loaded automatically
on backend startup (`DataLoader.java`).

Cleaning applied during aggregation:
- Header row skipped.
- Empty queries and the AOL log's `-` placeholder for blank searches are dropped.
- Counting is case-insensitive (`iPhone` and `iphone` count as the same
  query); the **first-seen casing** is kept as the display form.
- A light post-filter drops single-character queries (mostly typos/noise).

A second script, `data/generate_sample_dataset.py`, produces a synthetic
105K-row dataset with deliberately clustered, prefix-rich queries (iphone,
java, python, etc.) — useful as a quick fallback if you want a smaller,
faster-loading dataset for local iteration. Swap it in by copying it over
`backend/src/main/resources/dataset/queries.csv`.

## 3. Architecture

```
                        ┌─────────────┐
   keystroke ──debounce─►   React UI   │
                        └──────┬──────┘
                               │ GET /suggest?q=prefix&enhanced=bool
                               ▼
                     ┌───────────────────┐
                     │   Spring Boot API  │
                     └─────────┬─────────┘
                               │
              ┌────────────────┼─────────────────┐
              ▼                                   ▼
   ConsistentHashRing.getNode(prefix)     (on cache miss only)
              │                                   │
   ┌──────────┼──────────┐                        ▼
   ▼          ▼          ▼                 Postgres: search_query
redis-1    redis-2    redis-3              (query, count, last_searched_at)
(one of the 3 owns this prefix's cache entry)
```

**Write path** (`POST /search`):
```
POST /search ──► BatchWriterService.submit(query)   [O(1), returns immediately]
                       │
                       ▼
              in-memory ConcurrentLinkedQueue
                       │
         every 2s OR every 500 items
                       ▼
        aggregate by query in memory (HashMap)
                       │
                       ▼
     one UPSERT per DISTINCT query  ──► Postgres
                       │
                       ▼
        invalidate that query's prefixes in cache
```

### Why Postgres + Redis (not Redis alone)

Redis alone (in-memory) would lose all query-count history on a restart
and can't be queried with arbitrary SQL for ad-hoc analysis/reporting.
Postgres is the durable system of record; Redis is purely a disposable,
regeneratable cache of computed suggestion lists. If every Redis node
were lost right now, the system would just get slower (more cache misses)
until it warmed back up — no data loss, because Redis never holds
anything that isn't also derivable from Postgres.

### Why consistent hashing (not modulo hashing)

With `hash(key) % N`, adding or removing one cache node remaps almost
every key to a different node (since `k % 3` and `k % 4` agree on very
few values of `k`), which wipes out most of the cache and sends a
thundering herd of requests to Postgres at once. Consistent hashing
(`ConsistentHashRing.java`) puts both nodes and keys on a hash ring;
adding/removing a node only remaps the ~1/N slice of keys between its
neighbors, so the rest of the cache survives untouched. We additionally
use **150 virtual nodes per physical node** so the 3 real nodes still end
up with roughly balanced shares of the ring (without virtual nodes, 3
random points on a ring can be very unevenly spaced by chance).

Demonstrate it: `GET /cache/debug?prefix=iph` returns which of the 3 real
Redis nodes owns that prefix, plus whether it's currently a hit. The same
prefix always routes to the same node as long as the node set is stable.

## 4. Trending / recency-aware ranking (the +20% requirement)

Two ranking modes live behind the same `GET /suggest` endpoint, toggled by
`enhanced=true|false` (and by the toggle switch in the UI):

- **Basic** (`enhanced=false`, 60% baseline): suggestions sorted purely by
  `search_query.count` (all-time popularity).
- **Enhanced** (`enhanced=true`): `TrendingService.blendedScore()` combines
  all-time popularity with a recency signal:

  ```
  score = log10(1 + allTimeCount) + 2.0 * recentActivityCount
  ```

  - **Tracking recent activity**: every `POST /search` increments a Redis
    sorted-set member for the current UTC minute (`trend:2026-06-20T14:32`),
    via the *same* consistent hash ring used for the suggestion cache.
  - **Sliding window**: `recentActivityScore()` sums a query's score across
    the last 10 such per-minute buckets — a bounded number of Redis calls,
    not a scan over all history.
  - **Avoiding permanent over-ranking**: each per-minute bucket key carries
    a TTL of `WINDOW_MINUTES + 1` (11 minutes), so a query that spiked once
    and stopped automatically falls out of the recent-activity window on
    its own — no manual cleanup job needed.
  - **Why `log10` on the historical term**: without compressing the
    all-time count, a query with a million historical searches would
    always bury a genuinely-trending new query, since realistic
    demo-scale recent counts (tens to low thousands) can't outweigh raw
    millions. Taking `log10` puts both terms in a comparable range so a
    real recent surge can actually move the ranking.
  - **Cache implication**: enhanced rankings change every minute as the
    window slides, so we don't cache the *final* ranked+scored list —
    instead we cache the same popularity-ordered *candidate* list as
    basic mode (still saving the Postgres round trip) and re-score/re-sort
    on every read. This trades a small amount of CPU (sorting ≤30
    candidates) for always-fresh trending without extra cache invalidation
    logic.
  - **Trade-offs**: this is a step-function (last-10-minutes) window, not a
    smooth exponential decay — simpler to reason about and bounded in cost,
    at the price of a visible "cliff" when an event ages out of the window
    rather than gracefully fading. `RECENCY_WEIGHT` (currently `2.0`) is a
    hand-tuned knob, not derived from data; a production system would
    tune this against real engagement metrics (e.g. suggestion
    click-through rate).

Demonstrate the difference: toggle "All-time popularity" vs
"Recency-aware" in the UI, or call
`GET /suggest?q=iphone&enhanced=false` vs `GET /suggest?q=iphone&enhanced=true`
after running a burst of `POST /search` calls for a normally-low-count query.

## 5. Batch writes (the other +20% requirement)

See `BatchWriterService.java`. Summary:

- `POST /search` never writes to Postgres synchronously — it just enqueues
  the raw query into an in-memory `ConcurrentLinkedQueue` and returns.
- A `@Scheduled` task flushes every 2 seconds, or immediately if the buffer
  reaches 500 pending items (whichever comes first).
- On flush, the buffer is drained and aggregated **in memory** by query
  (`HashMap<String, Long>`) before touching the database, so N search
  requests for the same query in one window become exactly **one**
  `INSERT ... ON CONFLICT DO UPDATE count = count + n` upsert.
- `GET /admin/batch-stats` reports `totalSearchRequests` vs `totalDbWrites`
  and the resulting reduction ratio — this is what you'd cite in the
  performance report.

**Failure trade-off**: the buffer is JVM-heap-only. If the process crashes
between flushes, any increments accumulated since the last successful
flush are lost — counts become approximate, not exactly-once durable.
This is an acceptable trade-off for a popularity *signal* (losing a few
seconds of increments doesn't meaningfully change rankings), in exchange
for a much simpler and faster write path than a durable queue would give.
If stronger durability were required, two upgrade paths: (a) front the
buffer with a write-ahead log or a Kafka topic acknowledged before the
HTTP response returns, or (b) flush more frequently, trading batch size
(and write-reduction ratio) for a smaller blast radius on crash.

## 6. API reference

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/suggest?q=<prefix>&enhanced=<bool>` | Up to 10 prefix-matching suggestions, sorted by count (basic) or blended score (enhanced) |
| `POST` | `/search` `{"query": "..."}` | Returns `{"message": "Searched", "query": "..."}`; buffers the count increment |
| `GET` | `/trending?enhanced=<bool>&limit=<n>` | Top trending queries |
| `GET` | `/cache/debug?prefix=<prefix>` | Which Redis node owns this prefix, and current hit/miss status |
| `GET` | `/admin/batch-stats` | Batch-write reduction statistics |

## 7. Performance notes

- `/suggest` p95 latency: cache hits typically resolve in single-digit
  milliseconds (one Redis GET); cache misses cost one indexed Postgres
  `LIKE 'prefix%'` query plus a Redis SETEX, observable via the
  `latencyMs` field in every `/suggest` response and shown live in the UI's
  System panel.
- Cache hit rate and write-reduction ratio are both exposed live —
  `/admin/batch-stats` for writes; watch the cache ring panel in the UI
  (hit vs miss color) for reads, or poll `/cache/debug` repeatedly for a
  given prefix to see the hit rate climb after the first miss warms it.
- The dataset bulk-load (`DataLoader.java`) uses raw JDBC batched inserts
  (batches of 1,000, manual commit) rather than `JpaRepository.saveAll()`,
  which is materially faster for a 1M+ row one-time import.

## 8. Project structure

```
backend/                Spring Boot app (Java 17)
  src/main/java/com/typeahead/
    config/              Redis cluster wiring, dataset bulk loader
    controller/          REST endpoints
    service/             CacheService, TrendingService, BatchWriterService, SuggestionService
    repository/          Postgres access (JPA + native queries)
    model/                SearchQuery entity
    util/                ConsistentHashRing
  src/main/resources/
    application.yml
    schema.sql
    dataset/queries.csv  bulk-loaded on first boot
frontend/                React (Vite) UI
data/                    Dataset aggregation/generation scripts (run outside Docker)
docker-compose.yml        Postgres + 3 Redis nodes + backend + frontend
```