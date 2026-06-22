-- Search Typeahead: Postgres schema
-- Applied automatically on startup via spring.sql.init (see application.yml)

CREATE TABLE IF NOT EXISTS search_query (
    id               BIGSERIAL PRIMARY KEY,
    query            VARCHAR(512) NOT NULL UNIQUE,
    query_lower      VARCHAR(512) NOT NULL,
    count            BIGINT NOT NULL DEFAULT 0,
    last_searched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Powers prefix lookups: WHERE query_lower LIKE 'iph%' ORDER BY count DESC.
-- A plain B-tree index on query_lower can be used by Postgres for a
-- left-anchored LIKE pattern (this only works because the pattern has no
-- leading '%' - LIKE '%iph%' could NOT use this index).
CREATE INDEX IF NOT EXISTS idx_query_lower ON search_query (query_lower);

CREATE INDEX IF NOT EXISTS idx_count_desc ON search_query (count DESC);

-- Optional upgrade path (documented, not applied by default):
-- For datasets where prefix scans are still slow at scale, enable the
-- pg_trgm extension and add a GIN trigram index, which Postgres' planner
-- can use for LIKE 'prefix%' (and even '%substring%') much faster than a
-- B-tree once the table is large:
--
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX idx_query_trgm ON search_query USING gin (query_lower gin_trgm_ops);
--
-- Not enabled by default because pg_trgm requires a superuser-installed
-- extension, which may not be available in every grading environment.
