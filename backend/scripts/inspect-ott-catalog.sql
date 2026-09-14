-- Read-only inventory for S15P21E106-381. This does not retire or delete an OTT.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

SELECT o.id AS service_ott_id, o.code, o.name, o.logo_url, o.tmdb_provider_id,
       (SELECT count(*) FROM user_otts u WHERE u.ott_id=o.id) AS subscriptions,
       (SELECT count(*) FROM movie_ott_providers m WHERE m.ott_id=o.id) AS movies,
       (SELECT count(*) FROM watch_records w WHERE w.ott_id=o.id) AS watch_records
FROM otts o ORDER BY o.id;

WITH approved(code, provider_id) AS (VALUES
    ('NETFLIX',8), ('DISNEY_PLUS',337), ('WATCHA',97), ('WAVVE',356), ('TVING',1883)
)
SELECT a.code AS approved_code, a.provider_id AS approved_provider_id,
       o.id AS service_ott_id, o.code AS existing_code, o.tmdb_provider_id AS existing_provider_id,
       CASE WHEN o.id IS NULL THEN 'MISSING'
            WHEN o.code=a.code AND o.tmdb_provider_id=a.provider_id THEN 'MATCH'
            ELSE 'CONFLICT' END AS seed_status
FROM approved a LEFT JOIN otts o ON o.code=a.code OR o.tmdb_provider_id=a.provider_id
ORDER BY a.code, o.id;

SELECT to_regclass('public.flyway_schema_history') IS NOT NULL AS has_flyway_history \gset
\if :has_flyway_history
SELECT version, description, success FROM flyway_schema_history WHERE version='202609142240';
\else
\echo Flyway history is absent. Confirm migration ownership before application startup.
\endif

-- Re-discover references instead of assuming that the current three are exhaustive forever.
SELECT conrelid::regclass AS referencing_table, conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE contype='f' AND confrelid='public.otts'::regclass
ORDER BY conrelid::regclass::text, conname;

SELECT relation::regclass AS table_name, mode, granted
FROM pg_locks
WHERE relation IN ('otts'::regclass, 'user_otts'::regclass,
                   'movie_ott_providers'::regclass, 'watch_records'::regclass)
ORDER BY relation::regclass::text, mode, granted;

COMMIT;
