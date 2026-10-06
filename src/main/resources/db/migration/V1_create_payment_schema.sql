CREATE TABLE orders (
                        id BIGSERIAL PRIMARY KEY,

                        customer_email VARCHAR(255) NOT NULL,

                        total_kobo BIGINT NOT NULL
                            CHECK (total_kobo > 0),

                        status VARCHAR(20) NOT NULL DEFAULT 'UNPAID'
                            CHECK (status IN ('UNPAID', 'PAID')),

                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);


CREATE TABLE payments (
                          id BIGSERIAL PRIMARY KEY,

                          reference VARCHAR(80) NOT NULL UNIQUE,

                          order_id BIGINT NOT NULL
                              REFERENCES orders(id),

                          amount_kobo BIGINT NOT NULL
                              CHECK (amount_kobo > 0),

                          currency CHAR(3) NOT NULL DEFAULT 'NGN',

                          status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                              CHECK (
                                  status IN (
                                             'PENDING',
                                             'PROCESSING',
                                             'SUCCESS',
                                             'FAILED',
                                             'PARTIALLY_REFUNDED',
                                             'REFUNDED'
                                      )
                                  ),

                          idempotency_key VARCHAR(100) NOT NULL UNIQUE,

                          authorization_url TEXT,

                          access_code VARCHAR(100),

                          provider_transaction_id VARCHAR(50),

                          paid_at TIMESTAMPTZ,

                          refunded_kobo BIGINT NOT NULL DEFAULT 0
                              CHECK (refunded_kobo >= 0),

                          created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

                          updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

                          version BIGINT NOT NULL DEFAULT 0,

                          CONSTRAINT refund_not_greater_than_payment
                              CHECK (refunded_kobo <= amount_kobo)
);


-- There should never be two unfinished payments
-- for one order.
CREATE UNIQUE INDEX uq_open_payment_per_order
    ON payments(order_id)
    WHERE status IN ('PENDING', 'PROCESSING');


CREATE INDEX idx_payment_status_created
    ON payments(status, created_at);


CREATE TABLE payment_events (
                                id BIGSERIAL PRIMARY KEY,

                                payment_id BIGINT NOT NULL
                                    REFERENCES payments(id),

                                event_type VARCHAR(50) NOT NULL,

                                from_status VARCHAR(30),

                                to_status VARCHAR(30),

                                source VARCHAR(30) NOT NULL,

                                raw_payload JSONB,

                                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);


CREATE TABLE processed_provider_events (
                                           event_type VARCHAR(80) NOT NULL,

                                           provider_object_id VARCHAR(80) NOT NULL,

                                           processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

                                           PRIMARY KEY (
                                                        event_type,
                                                        provider_object_id
                                               )
);


CREATE TABLE refunds (
                         id BIGSERIAL PRIMARY KEY,

                         payment_id BIGINT NOT NULL
                             REFERENCES payments(id),

                         reference VARCHAR(80) NOT NULL UNIQUE,

                         provider_refund_id VARCHAR(80) UNIQUE,

                         amount_kobo BIGINT NOT NULL
                             CHECK (amount_kobo > 0),

                         reason VARCHAR(200),

                         status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                             CHECK (
                                 status IN (
                                            'PENDING',
                                            'PROCESSING',
                                            'SUCCESS',
                                            'FAILED'
                                     )
                                 ),

                         idempotency_key VARCHAR(100) NOT NULL UNIQUE,

                         created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

                         completed_at TIMESTAMPTZ
);


CREATE INDEX idx_refund_payment
    ON refunds(payment_id);