-- Friendship needs consent: a request stays pending until the addressee accepts it.
CREATE TABLE friend_requests (
    requester_id BIGINT      NOT NULL REFERENCES users (id),
    addressee_id BIGINT      NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (requester_id, addressee_id),
    CHECK (requester_id <> addressee_id)
);

CREATE INDEX friend_requests_addressee_idx ON friend_requests (addressee_id);
