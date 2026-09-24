-- S15P21E106-712: add the approved human-written description for each fixed flavor.
ALTER TABLE public.popcorn_flavors
    ADD COLUMN description text;

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
        RAISE EXCEPTION 'Popcorn flavor description identity conflict; no descriptions applied';
    END IF;

    UPDATE public.popcorn_flavors f
    SET description = approved.description
    FROM (VALUES
        ('FLAVOR_A', 0, '있었던 일을 그대로 담은 이야기에 끌려요'),
        ('FLAVOR_B', 1, '서늘하고 낯선 세계를 즐겨요'),
        ('FLAVOR_C', 2, '웃기면서 씁쓸한 관계 이야기를 봐요'),
        ('FLAVOR_D', 3, '사람과 시대를 깊게 들여다봐요'),
        ('FLAVOR_E', 4, '쫓고 쫓기는 긴장감을 즐겨요'),
        ('FLAVOR_F', 5, '가볍게 웃고 기분 좋아지는 영화를 봐요'),
        ('FLAVOR_G', 6, '상상으로 그린 세계를 여행해요'),
        ('FLAVOR_H', 7, '크고 시원한 모험에 빠져요')
    ) AS approved(code, cluster_id, description)
    WHERE f.code = approved.code
      AND f.cluster_id = approved.cluster_id;

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    IF updated_count <> 8 THEN
        RAISE EXCEPTION 'Expected to update 8 popcorn flavor descriptions, updated %; transaction rolled back',
            updated_count;
    END IF;
END $$;

ALTER TABLE public.popcorn_flavors
    ALTER COLUMN description SET NOT NULL;
