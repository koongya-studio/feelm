-- S15P21E106-578: align the catalog colors with the approved popcorn artwork.
DO $$
DECLARE
    updated_count integer;
BEGIN
    LOCK TABLE public.popcorn_flavors IN SHARE ROW EXCLUSIVE MODE;

    IF (SELECT count(*) FROM public.popcorn_flavors) <> 8
       OR EXISTS (
            SELECT 1
            FROM (VALUES
                ('FLAVOR_A', 0), ('FLAVOR_B', 1), ('FLAVOR_C', 2), ('FLAVOR_D', 3),
                ('FLAVOR_E', 4), ('FLAVOR_F', 5), ('FLAVOR_G', 6), ('FLAVOR_H', 7)
            ) AS approved(code, cluster_id)
            WHERE NOT EXISTS (
                SELECT 1
                FROM public.popcorn_flavors f
                WHERE f.code = approved.code
                  AND f.cluster_id = approved.cluster_id
            )
       ) THEN
        RAISE EXCEPTION 'Popcorn flavor identity conflict; no colors applied';
    END IF;

    UPDATE public.popcorn_flavors f
    SET color = approved.color
    FROM (VALUES
        ('FLAVOR_A', 0, '#F3DFB2'),
        ('FLAVOR_B', 1, '#5A3623'),
        ('FLAVOR_C', 2, '#CF8F4A'),
        ('FLAVOR_D', 3, '#D6CF9C'),
        ('FLAVOR_E', 4, '#F0E7D3'),
        ('FLAVOR_F', 5, '#E6AA52'),
        ('FLAVOR_G', 6, '#E7A79E'),
        ('FLAVOR_H', 7, '#B78A96')
    ) AS approved(code, cluster_id, color)
    WHERE f.code = approved.code
      AND f.cluster_id = approved.cluster_id;

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    IF updated_count <> 8 THEN
        RAISE EXCEPTION 'Expected to update 8 popcorn flavor colors, updated %; transaction rolled back',
            updated_count;
    END IF;
END $$;
