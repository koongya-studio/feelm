ALTER TABLE recommendation_sets
    ADD COLUMN candidate_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN generation_id varchar(100),
    ADD CONSTRAINT ck_recommendation_sets_candidate_version
        CHECK (candidate_version >= 0);

DROP INDEX uix_recommendation_sets_user_input_model;

CREATE UNIQUE INDEX uix_recommendation_sets_generation
    ON recommendation_sets (generation_id)
    WHERE generation_id IS NOT NULL;

ALTER TABLE recommendation_items
    ADD COLUMN flavor_code varchar(30);

ALTER TABLE recommendation_items
    DROP CONSTRAINT uk_recommendation_items_set_position,
    ADD CONSTRAINT uk_recommendation_items_set_type_position
        UNIQUE (set_id, type, position);
