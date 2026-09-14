-- S15P21E106-381: bootstrap without TMDb access or movie ingestion.
-- Approved source: backend/scripts/samples/ott-provider-mapping.csv.
-- A single DO statement is atomic both in Flyway and when checked with psql.
DO $$
DECLARE
    approved record;
    service_id bigint;
    id_sequence regclass := pg_get_serial_sequence('public.otts', 'id')::regclass;
    catalog_max_id bigint;
    sequence_last_value bigint;
    sequence_is_called boolean;
BEGIN
    LOCK TABLE public.otts IN SHARE ROW EXCLUSIVE MODE;

    FOR approved IN
        SELECT * FROM (VALUES
            (1, 'NETFLIX', '넷플릭스', 8, '/rK1KljqmbvO9HQa1PBFLILWah72.png'),
            (2, 'DISNEY_PLUS', '디즈니+', 337, '/5eZ872CghnHFLB1j8grszbrx0dx.png'),
            (3, 'WATCHA', '왓챠', 97, '/ppQIS7UMtBwJSspSoGqDR8wO1Gr.png'),
            (4, 'WAVVE', '웨이브', 356, '/mh1vBeclG7Eq15TjRnmyustqp3v.png'),
            (5, 'TVING', '티빙', 1883, '/w4ov8Zx73eu2TjHsDs5EmiuYKA4.png')
        ) AS mapping(position, code, name, provider_id, logo_path)
        ORDER BY position
    LOOP
        IF EXISTS (
            SELECT 1 FROM public.otts o
            WHERE (o.code = approved.code OR o.tmdb_provider_id = approved.provider_id)
              AND (o.code <> approved.code OR o.tmdb_provider_id <> approved.provider_id)
        ) THEN
            RAISE EXCEPTION 'OTT seed identity conflict for code %, provider %; no catalog rows applied',
                approved.code, approved.provider_id;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM public.otts o
            WHERE o.code = approved.code AND o.tmdb_provider_id = approved.provider_id
        ) THEN
            -- Legacy explicit IDs may be ahead of the sequence. Consume unused
            -- sequence values without reassigning IDs or moving the sequence back.
            LOOP
                service_id := nextval(id_sequence);
                EXIT WHEN NOT EXISTS (SELECT 1 FROM public.otts WHERE id = service_id);
            END LOOP;
            INSERT INTO public.otts (id, code, name, logo_url, tmdb_provider_id)
            VALUES (service_id, approved.code, approved.name,
                    'https://image.tmdb.org/t/p/original' || approved.logo_path,
                    approved.provider_id);
        END IF;
    END LOOP;

    -- Explicit legacy IDs do not advance an identity sequence. Normalize it against
    -- every catalog row, while preserving a sequence that is already further ahead.
    SELECT MAX(id) INTO catalog_max_id FROM public.otts;
    EXECUTE format('SELECT last_value, is_called FROM %s', id_sequence)
        INTO sequence_last_value, sequence_is_called;

    IF (sequence_is_called AND sequence_last_value < catalog_max_id)
       OR (NOT sequence_is_called AND sequence_last_value <= catalog_max_id) THEN
        PERFORM setval(id_sequence, catalog_max_id, true);
    END IF;
END $$;
