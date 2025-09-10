CREATE SEQUENCE IF NOT EXISTS outbox_kafka_id_seq;

CREATE TABLE IF NOT EXISTS outbox_kafka (
    id              BIGINT PRIMARY KEY DEFAULT nextval('outbox_kafka_id_seq'::regclass),
    created         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    processed       TIMESTAMP WITHOUT TIME ZONE NULL,
    topic           CHARACTER VARYING(128) NOT NULL,
    key             CHARACTER VARYING(128) NULL,
    value           BYTEA NOT NULL,
    headers         JSONB NULL
);

CREATE INDEX idx_outbox_kafka_not_processed ON outbox_kafka (id) WHERE processed IS NULL;
CREATE INDEX idx_outbox_kafka_processed ON outbox_kafka (processed);

CREATE TABLE IF NOT EXISTS outbox_kafka_lock (
    id              CHARACTER VARYING(32) PRIMARY KEY,
    owner_id        CHARACTER VARYING(128) NOT NULL,
    valid_until     TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
