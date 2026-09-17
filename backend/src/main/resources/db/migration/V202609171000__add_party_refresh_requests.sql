CREATE TABLE party_refresh_requests (
    party_id bigint PRIMARY KEY,
    request_version bigint NOT NULL DEFAULT 1,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    attempt_count integer NOT NULL DEFAULT 0,
    CONSTRAINT fk_party_refresh_requests_party_id FOREIGN KEY (party_id)
        REFERENCES parties (id) ON DELETE CASCADE,
    CONSTRAINT ck_party_refresh_requests_version CHECK (request_version > 0),
    CONSTRAINT ck_party_refresh_requests_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX ix_party_refresh_requests_due
    ON party_refresh_requests (next_attempt_at, party_id);

CREATE INDEX ix_outbox_party_refresh_pending_order
    ON outbox_events (partition_key, id)
    WHERE event_type = 'PARTY_REFRESH' AND status = 'PENDING';

ALTER TABLE outbox_events DROP CONSTRAINT ck_outbox_events_message_contract;

ALTER TABLE outbox_events ADD CONSTRAINT ck_outbox_events_message_contract CHECK (
    CASE WHEN event_type = 'PARTY_REFRESH' THEN
        topic = 'feelm.party-refresh.v1'
        AND jsonb_typeof(message) = 'object'
        AND message ?& ARRAY['partyId', 'inputId', 'members']
        AND message - 'partyId' - 'inputId' - 'members' = '{}'::jsonb
        AND jsonb_typeof(message -> 'partyId') = 'number'
        AND message ->> 'partyId' = partition_key::text
        AND jsonb_typeof(message -> 'inputId') = 'string'
        AND message ->> 'inputId' LIKE 'sha256:%'
        AND CASE WHEN jsonb_typeof(message -> 'members') = 'array'
                 THEN jsonb_array_length(message -> 'members') BETWEEN 1 AND 4
                 ELSE FALSE END
    ELSE
        jsonb_typeof(message) = 'object'
        AND message ?& ARRAY['eventId', 'eventType', 'occurredAt', 'userId', 'payload']
        AND jsonb_typeof(message -> 'payload') = 'object'
        AND message ->> 'eventId' = event_id::text
        AND message ->> 'eventType' = event_type
        AND message ->> 'userId' = partition_key::text
    END
);
