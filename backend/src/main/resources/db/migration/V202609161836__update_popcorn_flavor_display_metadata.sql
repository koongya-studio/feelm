-- S15P21E106-510: apply the approved display names and colors without changing flavor identity.
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
        RAISE EXCEPTION 'Popcorn flavor metadata identity conflict; no display metadata applied';
    END IF;

    UPDATE public.popcorn_flavors f
    SET display_name = approved.display_name,
        color = approved.color
    FROM (VALUES
        ('FLAVOR_A', 0, '오리지널 솔트', '#F0D8B4'),
        ('FLAVOR_B', 1, '다크초코', '#3C2418'),
        ('FLAVOR_C', 2, '솔티카라멜', '#B46C24'),
        ('FLAVOR_D', 3, '피스타치오', '#B4B46C'),
        ('FLAVOR_E', 4, '쿠키앤크림', '#E4D8B4'),
        ('FLAVOR_F', 5, '체다치즈', '#D87824'),
        ('FLAVOR_G', 6, '스트로베리', '#D89C9C'),
        ('FLAVOR_H', 7, '블루베리', '#A890B4')
    ) AS approved(code, cluster_id, display_name, color)
    WHERE f.code = approved.code
      AND f.cluster_id = approved.cluster_id;

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    IF updated_count <> 8 THEN
        RAISE EXCEPTION 'Expected to update 8 popcorn flavors, updated %; transaction rolled back',
            updated_count;
    END IF;
END $$;
