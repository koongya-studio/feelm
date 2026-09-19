CREATE TABLE party_candidate_publications (
    party_id bigint PRIMARY KEY,
    input_id varchar(100) NOT NULL,
    model_version varchar(50) NOT NULL,
    generated_at timestamptz NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_party_candidate_publications_party_id FOREIGN KEY (party_id)
        REFERENCES parties (id) ON DELETE CASCADE,
    CONSTRAINT ck_party_candidate_publications_input_id
        CHECK (input_id ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_party_candidate_publications_model_version
        CHECK (model_version ~ '^[a-z0-9][a-z0-9._-]{0,49}$'),
    CONSTRAINT ck_party_candidate_publications_source_partition
        CHECK (source_partition >= 0),
    CONSTRAINT ck_party_candidate_publications_source_offset
        CHECK (source_offset >= 0)
);
