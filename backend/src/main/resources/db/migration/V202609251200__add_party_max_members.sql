ALTER TABLE parties
    ADD COLUMN max_members integer NOT NULL DEFAULT 4;

ALTER TABLE parties
    ADD CONSTRAINT ck_parties_max_members CHECK (max_members BETWEEN 2 AND 4);
