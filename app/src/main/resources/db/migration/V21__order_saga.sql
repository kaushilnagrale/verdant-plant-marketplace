CREATE TABLE order_sagas (
    order_id        UUID PRIMARY KEY REFERENCES orders(id),
    state           TEXT NOT NULL,
    furthest_state  TEXT NOT NULL,
    attempts        INT  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE saga_completed_steps (
    order_id  UUID NOT NULL REFERENCES order_sagas(order_id) ON DELETE CASCADE,
    step_name TEXT NOT NULL,
    at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, step_name)
);

CREATE INDEX order_sagas_ready ON order_sagas (next_attempt_at)
    WHERE state NOT IN ('COMPLETED','FAILED');
