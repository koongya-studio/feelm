-- S15P21E106-466: bind the public popcorn flavors to the fixed S15P21E106-439 top axis.
-- code and cluster_id are immutable identities. Display metadata remains editable after bootstrap.
DO $$
DECLARE
    approved record;
    service_id bigint;
    id_sequence regclass := pg_get_serial_sequence('public.popcorn_flavors', 'id')::regclass;
    catalog_max_id bigint;
    sequence_last_value bigint;
    sequence_is_called boolean;
BEGIN
    LOCK TABLE public.popcorn_flavors IN SHARE ROW EXCLUSIVE MODE;

    -- The service contract contains exactly these eight identities. Do not silently
    -- reinterpret a legacy row or keep a ninth flavor outside the fixed 439 axis.
    IF EXISTS (
        SELECT 1
        FROM public.popcorn_flavors f
        WHERE NOT EXISTS (
            SELECT 1
            FROM (VALUES
                ('FLAVOR_A', 0), ('FLAVOR_B', 1), ('FLAVOR_C', 2), ('FLAVOR_D', 3),
                ('FLAVOR_E', 4), ('FLAVOR_F', 5), ('FLAVOR_G', 6), ('FLAVOR_H', 7)
            ) AS identity(code, cluster_id)
            WHERE identity.code = f.code AND identity.cluster_id = f.cluster_id
        )
    ) THEN
        RAISE EXCEPTION 'Popcorn flavor seed identity conflict; no flavor rows applied';
    END IF;

    FOR approved IN
        SELECT * FROM (VALUES
            (1, 'FLAVOR_A', '오리지널 솔트', '#FBF1CF', 0,
                '["다큐멘터리","현실 기록","음악","역사"]'::jsonb),
            (2, 'FLAVOR_B', '핫칠리', '#DC9A4E', 1,
                '["공포","괴물","초자연","미스터리","SF"]'::jsonb),
            (3, 'FLAVOR_C', '솔티카라멜', '#F0D07C', 2,
                '["드라마 코미디","인물","관계","로맨스","블랙코미디"]'::jsonb),
            (4, 'FLAVOR_D', '다크초코', '#5C3020', 3,
                '["드라마","인물","관계","역사","전쟁"]'::jsonb),
            (5, 'FLAVOR_E', '블랙페퍼', '#F4F1EA', 4,
                '["스릴러","범죄","긴장","미스터리","추적"]'::jsonb),
            (6, 'FLAVOR_F', '갈릭버터', '#C48C50', 5,
                '["코미디","로맨스","가족","액션 코미디","호러 코미디"]'::jsonb),
            (7, 'FLAVOR_G', '허니버터', '#ECAABB', 6,
                '["애니메이션","가족","판타지","모험","상상"]'::jsonb),
            (8, 'FLAVOR_H', '치즈믹스', '#D9C6EA', 7,
                '["액션","모험","SF","판타지","음악"]'::jsonb)
        ) AS mapping(sort_order, code, display_name, color, cluster_id, representative_keywords)
        ORDER BY sort_order
    LOOP
        IF EXISTS (
            SELECT 1
            FROM public.popcorn_flavors f
            WHERE (f.code = approved.code OR f.cluster_id = approved.cluster_id)
              AND (f.code <> approved.code OR f.cluster_id <> approved.cluster_id)
        ) THEN
            RAISE EXCEPTION 'Popcorn flavor seed identity conflict for code %, cluster %; no flavor rows applied',
                approved.code, approved.cluster_id;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM public.popcorn_flavors f
            WHERE f.code = approved.code AND f.cluster_id = approved.cluster_id
        ) THEN
            LOOP
                service_id := nextval(id_sequence);
                EXIT WHEN NOT EXISTS (
                    SELECT 1 FROM public.popcorn_flavors WHERE id = service_id
                );
            END LOOP;
            INSERT INTO public.popcorn_flavors (
                id, code, display_name, color, sort_order, cluster_id, representative_keywords
            ) VALUES (
                service_id, approved.code, approved.display_name, approved.color,
                approved.sort_order, approved.cluster_id, approved.representative_keywords
            );
        END IF;
    END LOOP;

    SELECT MAX(id) INTO catalog_max_id FROM public.popcorn_flavors;
    EXECUTE format('SELECT last_value, is_called FROM %s', id_sequence)
        INTO sequence_last_value, sequence_is_called;

    IF (sequence_is_called AND sequence_last_value < catalog_max_id)
       OR (NOT sequence_is_called AND sequence_last_value <= catalog_max_id) THEN
        PERFORM setval(id_sequence, catalog_max_id, true);
    END IF;
END $$;
